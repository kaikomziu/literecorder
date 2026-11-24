package app.literecorder

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
import android.widget.Toast

/** 録画・録音を裏で続けるフォアグラウンドサービス */
class RecorderService : Service() {

    enum class State { IDLE, COUNTDOWN, RECORDING, STOPPING }

    companion object {
        const val ACTION_START = "app.literecorder.START"
        const val ACTION_STOP = "app.literecorder.STOP"
        const val ACTION_PAUSE = "app.literecorder.PAUSE"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "rec"
        private const val NOTIF_ID = 1

        @Volatile var state = State.IDLE
            private set
        @Volatile private var current: Session? = null
        /** カウントダウンの残り秒 */
        @Volatile var countdownLeft = 0
            private set
        var lastUri: Uri? = null
            private set
        var lastName: String? = null
            private set
        var lastMime = "video/mp4"
            private set

        val paused get() = current?.clock?.paused == true
        /** 今の音の大きさ（0〜1、音なしは -1） */
        val level get() = current?.level ?: -1f
        /** 空き容量がこれを下回ったら録画を止める */
        private const val LOW_SPACE = 150L * 1_000_000
        /** 止まっていた時間を除いた経過（ms） */
        fun activeMs() = current?.activeMs() ?: 0L

        /** 画面側の更新用（メインスレッドで呼ぶ） */
        var listener: (() -> Unit)? = null

        fun start(ctx: Context, code: Int, data: Intent?) {
            val i = Intent(ctx, RecorderService::class.java).setAction(ACTION_START)
            if (data != null) i.putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) = send(ctx, ACTION_STOP)
        fun togglePause(ctx: Context) = send(ctx, ACTION_PAUSE)

        private fun send(ctx: Context, action: String) {
            if (state == State.IDLE) return
            ctx.startService(Intent(ctx, RecorderService::class.java).setAction(action))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var settings: Settings? = null
    private var overlay: Overlay? = null
    private var limitHit = false
    private var lowSpace = false
    private var tickCount = 0

    private val ticker = object : Runnable {
        override fun run() {
            val sess = current ?: return
            val s = settings ?: return
            overlay?.update(sess.clock.paused, sess.activeMs(), sess.level)
            // 自動停止（時間・サイズ）
            val overTime = s.limitMin > 0 && sess.activeMs() >= s.limitMin * 60_000L
            val overSize = s.limitMb > 0 && sess.bytes >= s.limitMb * 1_000_000L
            // 空き容量（2 秒ごと）
            if (++tickCount % 10 == 0 && Store.freeBytes() < LOW_SPACE) lowSpace = true
            if (overTime || overSize || lowSpace) {
                limitHit = overTime || overSize
                finish()
                return
            }
            main.postDelayed(this, 200)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_STOP -> finish()
            ACTION_PAUSE -> togglePause()
            else -> if (state == State.IDLE) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun changed() {
        listener?.invoke()
        // クイック設定タイルの表示も更新する
        runCatching { TileService.requestListeningState(this, ComponentName(this, RecordTileService::class.java)) }
    }

    private fun begin(intent: Intent) {
        if (state != State.IDLE) return
        val s = Settings(this)
        settings = s
        val src = s.effectiveAudio
        limitHit = false
        lowSpace = false
        val data = getParcelableExtraCompat(intent)
        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)

        // Android 14 以降は、使う種類を全部指定してから録り始める必要がある
        var type = 0
        if (s.needsProjection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (src.usesMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            fail(getString(R.string.err_notification) + ": " + e.message)
            return
        }

        try {
            check(Store.freeBytes() >= LOW_SPACE * 2) { getString(R.string.err_low_space) }
            if (s.needsProjection) {
                checkNotNull(data) { getString(R.string.err_projection) }
                val mpm = getSystemService(MediaProjectionManager::class.java)
                val proj = checkNotNull(mpm.getMediaProjection(code, data)) { getString(R.string.err_projection) }
                // システムの「共有を停止」から止められたときも保存する
                proj.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        main.post { if (state == State.COUNTDOWN) cancelCountdown() else finish() }
                    }
                }, main)
                projection = proj
            }
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
            return
        }

        if (s.overlay) overlay = Overlay(this, onPause = { togglePause() }, onStop = { finish() })

        // カウントダウン（MP4 のときだけ。音だけなら待つ意味がないのですぐ始める）
        val count = if (s.format.isVideo) s.countdown else 0
        if (count > 0) {
            state = State.COUNTDOWN
            countdownLeft = count
            changed()
            countdownStep()
        } else {
            startSession()
        }
    }

    private fun countdownStep() {
        if (state != State.COUNTDOWN) return
        if (countdownLeft <= 0) {
            overlay?.showCountdown(0)
            // 数字が消えきってから録り始める（写り込み防止）
            main.postDelayed({ if (state == State.COUNTDOWN) startSession() }, 250)
            return
        }
        overlay?.showCountdown(countdownLeft)
        changed()
        countdownLeft--
        main.postDelayed({ countdownStep() }, 1000)
    }

    private fun cancelCountdown() {
        overlay?.hideAll()
        overlay = null
        runCatching { projection?.stop() }
        projection = null
        state = State.IDLE
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        changed()
    }

    private fun startSession() {
        val s = settings ?: return
        val sess = Session(this, s, projection)
        try {
            current = sess
            sess.start()
        } catch (e: Exception) {
            current = null
            Thread { sess.stop() }.start()
            runCatching { projection?.stop() }
            projection = null
            fail(e.message ?: e.javaClass.simpleName)
            return
        }
        state = State.RECORDING
        overlay?.showControls()
        updateNotification()
        main.post(ticker)
        changed()
    }

    private fun togglePause() {
        val sess = current ?: return
        if (state != State.RECORDING) return
        if (sess.clock.paused) sess.clock.resume() else sess.clock.pause()
        overlay?.update(sess.clock.paused, sess.activeMs(), sess.level)
        updateNotification()
        changed()
    }

    private fun fail(msg: String) {
        overlay?.hideAll()
        overlay = null
        state = State.IDLE
        Toast.makeText(this, getString(R.string.toast_start_failed, msg), Toast.LENGTH_LONG).show()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        changed()
    }

    /** 停止と保存。エンコーダの後始末は少し時間がかかるので別スレッドで行う */
    private fun finish() {
        if (state == State.COUNTDOWN) {
            cancelCountdown()
            return
        }
        if (state != State.RECORDING) return
        state = State.STOPPING
        main.removeCallbacks(ticker)
        overlay?.hideAll()
        overlay = null
        changed()
        val sess = current
        val proj = projection
        projection = null
        Thread {
            val saved = sess?.stop() ?: emptyList()
            runCatching { proj?.stop() }
            main.post {
                current = null
                val last = saved.lastOrNull()
                if (last != null) {
                    lastUri = last.uri
                    lastName = last.name
                    lastMime = last.mime
                    val msg = when {
                        lowSpace -> getString(R.string.toast_low_space)
                        limitHit -> getString(R.string.toast_limit)
                        saved.size > 1 -> getString(R.string.toast_saved_parts, saved.size)
                        else -> getString(R.string.toast_saved, last.name)
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, R.string.toast_save_failed, Toast.LENGTH_LONG).show()
                }
                state = State.IDLE
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                changed()
            }
        }.start()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        overlay?.hideAll()
        if (state == State.RECORDING) {
            // 強制終了されそうなときもできるだけ保存する
            current?.stop()
            runCatching { projection?.stop() }
        }
        current = null
        state = State.IDLE
        super.onDestroy()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pausePi = PendingIntent.getService(
            this, 2, Intent(this, RecorderService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val s = settings ?: Settings(this)
        val sess = current
        val isPaused = sess?.clock?.paused == true
        val title = when {
            state == State.COUNTDOWN || sess == null -> getString(R.string.notif_countdown)
            isPaused -> getString(R.string.notif_paused)
            s.format.isVideo -> getString(R.string.notif_rec_video)
            else -> getString(R.string.notif_rec_audio, s.format.name)
        }
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(getString(R.string.notif_tap))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
        if (sess != null) {
            // 一時停止中は時計を止め、再開したら止まっていた分を引いた時刻から進める
            b.setUsesChronometer(!isPaused)
            b.setShowWhen(!isPaused)
            b.setWhen(System.currentTimeMillis() - sess.activeMs())
            b.addAction(
                Notification.Action.Builder(
                    null, getString(if (isPaused) R.string.btn_resume else R.string.btn_pause), pausePi,
                ).build(),
            )
        }
        b.addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), stopPi).build())
        return b.build()
    }

    @Suppress("DEPRECATION")
    private fun getParcelableExtraCompat(i: Intent): Intent? =
        if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else i.getParcelableExtra(EXTRA_DATA)
}
