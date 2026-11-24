package app.literecorder

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.Display
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
    var startNs = 0L
        private set

    private val splitUs = s.splitMin * 60_000_000L
    private val baseName = "LiteRec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private var seg: SegmentedMuxer? = null
    private var mp3Out: Mp3Output? = null
    private var video: VideoEncoder? = null
    private var display: VirtualDisplay? = null
    private var aac: AacEncoder? = null
    private var mp3: Mp3Encoder? = null
    private var audio: AudioEngine? = null

    /** 今までに書いたバイト数（サイズ上限の判定用、分割したファイルの合計） */
    val bytes: Long get() = seg?.bytes ?: mp3Out?.bytes ?: 0L

    /** 今の音の大きさ（0〜1）。音を録っていなければ -1 */
    val level: Float get() = audio?.level ?: -1f

    /** 止まっていた時間を除いた経過（ms） */
    fun activeMs() = clock.activeMs(startNs)

    fun start() {
        val baseNs = System.nanoTime()
        startNs = baseNs
        val src = s.effectiveAudio
        when (format) {
            OutFormat.MP4 -> startVideo(baseNs, src)
            OutFormat.M4A -> {
                val out = SegmentedMuxer(ctx, format, baseName, 1, splitUs)
                seg = out
                val engine = AudioEngine(ctx, s, projection, baseNs, clock) { pcm, n, pts -> aac!!.encode(pcm, n, pts) }
                aac = AacEncoder(AudioEngine.RATE, engine.channels, s.audioKbps * 1000, out)
                audio = engine
                engine.start()
            }
            OutFormat.MP3 -> {
                val out = Mp3Output(ctx, baseName, splitUs)
                mp3Out = out
                val engine = AudioEngine(ctx, s, projection, baseNs, clock) { pcm, n, pts ->
                    val enc = mp3!!
                    if (out.rotateIfNeeded(pts)) enc.out = out.stream
                    out.added(enc.encode(pcm, n))
                }
                mp3 = Mp3Encoder(engine.channels, AudioEngine.RATE, s.audioKbps, out.stream)
                audio = engine
                engine.start()
            }
        }
    }

    private fun startVideo(baseNs: Long, src: AudioSrc) {
        val proj = checkNotNull(projection) { ctx.getString(R.string.err_projection) }
        val out = SegmentedMuxer(ctx, format, baseName, if (src == AudioSrc.NONE) 1 else 2, splitUs)
        seg = out

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
        val v = VideoEncoder(w, h, s.fps, bitrate, out, baseNs / 1000, clock)
        video = v
        display = proj.createVirtualDisplay(
            "LiteRecorder", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, v.surface, null, null,
        )
        v.start()

        if (src != AudioSrc.NONE) {
            val engine = AudioEngine(ctx, s, proj, baseNs, clock) { pcm, n, pts -> aac!!.encode(pcm, n, pts) }
            aac = AacEncoder(AudioEngine.RATE, engine.channels, if (engine.channels == 1) 64_000 else 96_000, out)
            audio = engine
            engine.start()
        }
    }

    /** 止めて保存する。保存できたファイルの一覧を返す（空なら失敗） */
    fun stop(): List<Saved> {
        clock.resume()
        runCatching { audio?.stop() }
        runCatching { aac?.finish() }
        runCatching { video?.stop() }
        runCatching { display?.release() }
        runCatching { mp3?.finish() }
        return seg?.let { runCatching { it.finish() }.getOrDefault(it.saved) }
            ?: mp3Out?.let { runCatching { it.finish() }.getOrDefault(it.saved) }
            ?: emptyList()
    }
}
