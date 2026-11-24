package app.literecorder

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast

/** 録画・録音を裏で続けるフォアグラウンドサービス */
class RecorderService : Service() {

    enum class State { IDLE, RECORDING, STOPPING }

    companion object {
        const val ACTION_START = "app.literecorder.START"
        const val ACTION_STOP = "app.literecorder.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "rec"
        private const val NOTIF_ID = 1

        @Volatile var state = State.IDLE
            private set
        var startedAt = 0L
            private set
        var lastUri: Uri? = null
            private set
        var lastName: String? = null
            private set
        var lastIsVideo = true
            private set

        /** 画面側の更新用（メインスレッドで呼ぶ） */
        var listener: (() -> Unit)? = null

        fun start(ctx: Context, code: Int, data: Intent?) {
            val i = Intent(ctx, RecorderService::class.java).setAction(ACTION_START)
            if (data != null) i.putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_STOP -> finish()
            else -> if (state == State.IDLE) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        if (state != State.IDLE) return
        val s = Settings(this)
        val src = s.effectiveAudio
        val data = getParcelableExtraCompat(intent)
        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)

        // Android 14 以降は、使う種類を全部指定してから録り始める必要がある
        var type = 0
        if (s.needsProjection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (src.usesMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(NOTIF_ID, buildNotification(s), type)
        } catch (e: Exception) {
            fail("通知を出せませんでした: ${e.message}")
            return
        }

        try {
            if (s.needsProjection) {
                checkNotNull(data) { "画面キャプチャの許可がありません" }
                val mpm = getSystemService(MediaProjectionManager::class.java)
                val proj = checkNotNull(mpm.getMediaProjection(code, data)) { "画面キャプチャを開始できませんでした" }
                // システムの「共有を停止」から止められたときも保存する
                proj.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        main.post { finish() }
                    }
                }, main)
                projection = proj
            }
            val sess = Session(this, s, projection)
            session = sess
            sess.start()
            state = State.RECORDING
            startedAt = System.currentTimeMillis()
            lastIsVideo = s.format == OutFormat.MP4
            listener?.invoke()
        } catch (e: Exception) {
            session?.let { sess -> Thread { sess.stop() }.start() }
            session = null
            runCatching { projection?.stop() }
            projection = null
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(msg: String) {
        state = State.IDLE
        Toast.makeText(this, "開始できませんでした: $msg", Toast.LENGTH_LONG).show()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        listener?.invoke()
    }

    /** 停止と保存。エンコーダの後始末は少し時間がかかるので別スレッドで行う */
    private fun finish() {
        if (state != State.RECORDING) return
        state = State.STOPPING
        listener?.invoke()
        val sess = session
        val proj = projection
        session = null
        projection = null
        Thread {
            val ok = sess?.stop() ?: false
            runCatching { proj?.stop() }
            main.post {
                if (ok && sess != null) {
                    lastUri = sess.uri
                    lastName = sess.fileName
                    Toast.makeText(this, "保存しました: ${sess.fileName}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "保存できませんでした（中身が空でした）", Toast.LENGTH_LONG).show()
                }
                state = State.IDLE
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                listener?.invoke()
            }
        }.start()
    }

    override fun onDestroy() {
        if (state == State.RECORDING) {
            // 強制終了されそうなときもできるだけ保存する
            session?.stop()
            runCatching { projection?.stop() }
            state = State.IDLE
        }
        super.onDestroy()
    }

    private fun buildNotification(s: Settings): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "録画中", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (s.format == OutFormat.MP4) "録画中（MP4）" else "録音中（MP3）"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText("タップでアプリを開く")
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "停止して保存", stopPi).build())
            .build()
    }

    @Suppress("DEPRECATION")
    private fun getParcelableExtraCompat(i: Intent): Intent? =
        if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else i.getParcelableExtra(EXTRA_DATA)
}
