package app.literecorder

import android.content.ContentValues
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Display
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 1 回分の録画・録音。start() で始めて stop() で保存する */
class Session(
    private val ctx: Context,
    private val s: Settings,
    private val projection: MediaProjection?,
) {
    val format = s.format
    val clock = PauseClock()
    var uri: Uri? = null
        private set
    var fileName = ""
        private set
    var startNs = 0L
        private set

    private var pfd: ParcelFileDescriptor? = null
    private var muxer: Muxer? = null
    private var video: VideoEncoder? = null
    private var display: VirtualDisplay? = null
    private var aac: AacEncoder? = null
    private var mp3: Mp3Encoder? = null
    private var mp3Out: BufferedOutputStream? = null
    private var audio: AudioEngine? = null
    @Volatile private var mp3Bytes = 0L

    /** 今までに書いたバイト数（サイズ上限の判定用） */
    val bytes: Long get() = muxer?.bytes ?: mp3Bytes

    /** 止まっていた時間を除いた経過（ms） */
    fun activeMs() = clock.activeMs(startNs)

    fun start() {
        val baseNs = System.nanoTime()
        startNs = baseNs
        val src = s.effectiveAudio
        when (format) {
            OutFormat.MP4 -> startVideo(baseNs, src)
            OutFormat.M4A -> {
                open()
                val m = Muxer(pfd!!.fileDescriptor, 1)
                muxer = m
                val engine = AudioEngine(ctx, s, projection, baseNs, clock) { pcm, n, pts -> aac!!.encode(pcm, n, pts) }
                aac = AacEncoder(AudioEngine.RATE, engine.channels, s.audioKbps * 1000, m)
                audio = engine
                engine.start()
            }
            OutFormat.MP3 -> {
                open()
                val out = BufferedOutputStream(FileOutputStream(pfd!!.fileDescriptor), 64 * 1024)
                mp3Out = out
                val engine = AudioEngine(ctx, s, projection, baseNs, clock) { pcm, n, _ ->
                    mp3Bytes += mp3!!.encode(pcm, n)
                }
                mp3 = Mp3Encoder(engine.channels, AudioEngine.RATE, s.audioKbps, out)
                audio = engine
                engine.start()
            }
        }
    }

    private fun startVideo(baseNs: Long, src: AudioSrc) {
        val proj = checkNotNull(projection) { ctx.getString(R.string.err_projection) }
        open()
        val m = Muxer(pfd!!.fileDescriptor, if (src == AudioSrc.NONE) 1 else 2)
        muxer = m

        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics().also {
            ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        // 向き: 縦・横を選んだら、その向きの大きさで録る（逆向きの画面は黒帯付きで縮小される）
        val long = maxOf(metrics.widthPixels, metrics.heightPixels)
        val short = minOf(metrics.widthPixels, metrics.heightPixels)
        val (w0, h0) = when (s.orientation) {
            1 -> short to long
            2 -> long to short
            else -> metrics.widthPixels to metrics.heightPixels
        }
        val (w, h) = VideoEncoder.fitSize(w0, h0, s.shortSide)
        // 画質: 1 ピクセル・1 フレームあたりのビット数で決める
        val bpp = when (s.quality) { 0 -> 0.05; 2 -> 0.18; else -> 0.1 }
        val bitrate = (w.toLong() * h * s.fps * bpp).toInt().coerceIn(500_000, 20_000_000)
        val v = VideoEncoder(w, h, s.fps, bitrate, m, baseNs / 1000, clock)
        video = v
        display = proj.createVirtualDisplay(
            "LiteRecorder", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, v.surface, null, null,
        )
        v.start()

        if (src != AudioSrc.NONE) {
            val engine = AudioEngine(ctx, s, proj, baseNs, clock) { pcm, n, pts -> aac!!.encode(pcm, n, pts) }
            aac = AacEncoder(AudioEngine.RATE, engine.channels, if (engine.channels == 1) 64_000 else 96_000, m)
            audio = engine
            engine.start()
        }
    }

    /** 止めて保存する。保存できたら true（中身が空なら消す） */
    fun stop(): Boolean {
        clock.resume()
        runCatching { audio?.stop() }
        runCatching { aac?.finish() }
        runCatching { video?.stop() }
        runCatching { display?.release() }
        var ok: Boolean
        if (muxer != null) {
            ok = muxer!!.started && muxer!!.samples > 0
            muxer!!.release()
        } else {
            ok = runCatching { mp3?.finish(); mp3Out?.flush() }.isSuccess && mp3Bytes > 0
            runCatching { mp3Out?.close() }
        }
        runCatching { pfd?.close() }
        val u = uri ?: return false
        if (ok) {
            ok = runCatching {
                ctx.contentResolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }.isSuccess
        }
        if (!ok) runCatching { ctx.contentResolver.delete(u, null, null) }
        return ok
    }

    /** MediaStore に保存先を作る（Movies/LiteRecorder か Music/LiteRecorder） */
    private fun open() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val (ext, mime) = when (format) {
            OutFormat.MP4 -> "mp4" to "video/mp4"
            OutFormat.MP3 -> "mp3" to "audio/mpeg"
            OutFormat.M4A -> "m4a" to "audio/mp4"
        }
        fileName = "LiteRec_$stamp.$ext"
        val video = format.isVideo
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC) + "/LiteRecorder",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (video) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val u = checkNotNull(ctx.contentResolver.insert(collection, values)) { ctx.getString(R.string.err_storage) }
        uri = u
        pfd = checkNotNull(ctx.contentResolver.openFileDescriptor(u, "rw")) { ctx.getString(R.string.err_storage) }
    }
}
