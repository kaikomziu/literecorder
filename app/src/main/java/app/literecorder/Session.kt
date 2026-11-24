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
    var uri: Uri? = null
        private set
    var fileName = ""
        private set

    private var pfd: ParcelFileDescriptor? = null
    private var muxer: Muxer? = null
    private var video: VideoEncoder? = null
    private var display: VirtualDisplay? = null
    private var aac: AacEncoder? = null
    private var mp3: Mp3Encoder? = null
    private var mp3Out: BufferedOutputStream? = null
    private var audio: AudioEngine? = null
    @Volatile private var audioBytes = 0L

    fun start() {
        val baseNs = System.nanoTime()
        val src = s.effectiveAudio
        if (s.format == OutFormat.MP4) {
            val proj = checkNotNull(projection) { "画面キャプチャの許可がありません" }
            open(video = true)
            val m = Muxer(pfd!!.fileDescriptor, if (src == AudioSrc.NONE) 1 else 2)
            muxer = m

            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also {
                ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
            }
            val (w, h) = VideoEncoder.fitSize(metrics.widthPixels, metrics.heightPixels, s.shortSide)
            // 画質: 1 ピクセル・1 フレームあたりのビット数で決める
            val bpp = when (s.quality) { 0 -> 0.05; 2 -> 0.18; else -> 0.1 }
            val bitrate = (w.toLong() * h * s.fps * bpp).toInt().coerceIn(500_000, 20_000_000)
            val v = VideoEncoder(w, h, s.fps, bitrate, m, baseNs / 1000)
            video = v
            display = proj.createVirtualDisplay(
                "LiteRecorder", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, v.surface, null, null,
            )
            v.start()

            if (src != AudioSrc.NONE) {
                val engine = AudioEngine(src, s.voice, proj, baseNs) { pcm, n, pts -> aac!!.encode(pcm, n, pts) }
                aac = AacEncoder(AudioEngine.RATE, engine.channels, if (engine.channels == 1) 64_000 else 96_000, m)
                audio = engine
                engine.start()
            }
        } else {
            open(video = false)
            val out = BufferedOutputStream(FileOutputStream(pfd!!.fileDescriptor), 64 * 1024)
            mp3Out = out
            val engine = AudioEngine(src, s.voice, projection, baseNs) { pcm, n, _ ->
                mp3!!.encode(pcm, n)
                audioBytes += n
            }
            mp3 = Mp3Encoder(engine.channels, AudioEngine.RATE, s.mp3Kbps, out)
            audio = engine
            engine.start()
        }
    }

    /** 止めて保存する。保存できたら true（中身が空なら消す） */
    fun stop(): Boolean {
        runCatching { audio?.stop() }
        runCatching { aac?.finish() }
        runCatching { video?.stop() }
        runCatching { display?.release() }
        var ok: Boolean
        if (muxer != null) {
            ok = muxer!!.started && muxer!!.samples > 0
            muxer!!.release()
        } else {
            ok = runCatching { mp3?.finish(); mp3Out?.flush() }.isSuccess && audioBytes > 0
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
    private fun open(video: Boolean) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        fileName = "LiteRec_$stamp.${if (video) "mp4" else "mp3"}"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "audio/mpeg")
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
        val u = checkNotNull(ctx.contentResolver.insert(collection, values)) { "保存先を作れませんでした" }
        uri = u
        pfd = checkNotNull(ctx.contentResolver.openFileDescriptor(u, "rw")) { "保存先を開けませんでした" }
    }
}
