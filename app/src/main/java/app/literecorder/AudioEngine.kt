package app.literecorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection

/**
 * マイク・内部音を録って混ぜ、一定間隔で PCM を sink に渡す。
 *
 * 内部音は何も再生していないと届かない端末もあるので、各ソースは別スレッドで FIFO に貯め、
 * ミキサーは時計（System.nanoTime）で一定間隔に取り出す。足りない分は無音で埋める。
 * ボイスチェンジャーはマイクの FIFO に入れる前にだけかける。
 * 一時停止中は取り出したデータを捨て、時刻はサンプル数から作るので映像と自然にそろう。
 */
class AudioEngine(
    private val ctx: Context,
    private val s: Settings,
    private val projection: MediaProjection?,
    private val sessionBaseNs: Long,
    private val clock: PauseClock,
    /** pcm（インターリーブ 16bit）, short の個数, 録画開始からの µs */
    private val sink: (ShortArray, Int, Long) -> Unit,
) {
    private val src = s.effectiveAudio
    val channels = if (src == AudioSrc.MIC) 1 else 2

    @Volatile private var running = false
    /** 今の音の大きさ（0〜1、ピークをゆっくり下げる）。レベルメーター用 */
    @Volatile var level = 0f
        private set
    private val fx = if (s.voice != Voice.OFF) VoiceChanger(s.voice, RATE, s.semitones) else null
    private val micGain = s.micGain / 100f
    private val intGain = s.internalGain / 100f
    private var ns: NoiseSuppressor? = null
    private var mic: AudioRecord? = null
    private var internal: AudioRecord? = null
    private val micFifo = Fifo(RATE / 3, 1)         // 約 0.33 秒ぶん（モノラル）
    private val intFifo = Fifo(RATE / 3 * 2, 2)     // 約 0.33 秒ぶん（ステレオ）
    private val threads = ArrayList<Thread>()

    /** 失敗したら例外（許可がない・内部音が取れない など） */
    @SuppressLint("MissingPermission")
    fun start() {
        if (src.usesMic) {
            mic = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(fmt(AudioFormat.CHANNEL_IN_MONO))
                .setBufferSizeInBytes(bufSize(AudioFormat.CHANNEL_IN_MONO))
                .build()
            check(mic!!.state == AudioRecord.STATE_INITIALIZED) { ctx.getString(R.string.err_mic) }
            // ノイズ除去は端末が対応しているときだけ（Android 標準のエフェクトなので軽い）
            if (s.noiseSuppress && NoiseSuppressor.isAvailable()) {
                ns = runCatching { NoiseSuppressor.create(mic!!.audioSessionId)?.apply { enabled = true } }.getOrNull()
            }
        }
        if (src.usesInternal) {
            val proj = checkNotNull(projection) { ctx.getString(R.string.err_projection) }
            val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            internal = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(fmt(AudioFormat.CHANNEL_IN_STEREO))
                .setBufferSizeInBytes(bufSize(AudioFormat.CHANNEL_IN_STEREO))
                .build()
            check(internal!!.state == AudioRecord.STATE_INITIALIZED) { ctx.getString(R.string.err_internal) }
        }

        running = true
        mic?.let { rec -> rec.startRecording(); threads += Thread({ readLoop(rec, micFifo, fx) }, "lr-mic") }
        internal?.let { rec -> rec.startRecording(); threads += Thread({ readLoop(rec, intFifo, null) }, "lr-int") }
        threads += Thread({ mixLoop() }, "lr-mix")
        threads.forEach { it.start() }
    }

    private fun readLoop(rec: AudioRecord, fifo: Fifo, fx: VoiceChanger?) {
        val buf = ShortArray(2048)
        while (running) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0) {
                fx?.process(buf, n)
                fifo.push(buf, n)
            } else if (n < 0) {
                break
            }
        }
    }

    private fun mixLoop() {
        val micBuf = ShortArray(CHUNK)
        val intBuf = ShortArray(CHUNK * 2)
        val out = ShortArray(CHUNK * channels)
        val chunkNs = CHUNK * 1_000_000_000L / RATE
        val captureStartNs = System.nanoTime()
        // 読み取りスレッドが少し貯めるのを待ってから取り出す（途切れ防止）
        val t0 = captureStartNs + PREBUFFER_NS
        var k = 0L        // 時計上のチャンク番号
        var emitted = 0L  // 実際に書き出したチャンク数（一時停止中は進まない）
        while (running) {
            val target = t0 + k * chunkNs
            val wait = target - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            if (!running) break

            if (src.usesMic) {
                val got = micFifo.pull(micBuf, CHUNK)
                micBuf.fill(0, got, CHUNK)
            }
            if (src.usesInternal) {
                val got = intFifo.pull(intBuf, CHUNK * 2)
                intBuf.fill(0, got, CHUNK * 2)
            }
            k++
            if (clock.paused) continue
            when (src) {
                AudioSrc.MIC -> for (i in 0 until CHUNK) out[i] = clip(micBuf[i] * micGain)
                AudioSrc.INTERNAL -> for (i in 0 until CHUNK * 2) out[i] = clip(intBuf[i] * intGain)
                else -> for (i in 0 until CHUNK) {
                    val m = micBuf[i] * micGain
                    out[2 * i] = clip(intBuf[2 * i] * intGain + m)
                    out[2 * i + 1] = clip(intBuf[2 * i + 1] * intGain + m)
                }
            }
            var peak = 0
            for (i in 0 until out.size) {
                val v = out[i].toInt()
                if (v > peak) peak = v else if (-v > peak) peak = -v
            }
            level = maxOf(peak / 32768f, level * 0.85f)
            val ptsUs = (captureStartNs - sessionBaseNs) / 1000 + emitted * CHUNK * 1_000_000L / RATE
            try {
                sink(out, out.size, ptsUs)
            } catch (_: Exception) {
                break
            }
            emitted++
        }
    }

    /** 録音を止めてスレッドの終了を待つ。戻った後は sink が呼ばれない */
    fun stop() {
        running = false
        runCatching { mic?.stop() }
        runCatching { internal?.stop() }
        threads.forEach { it.join(2000) }
        runCatching { ns?.release() }
        runCatching { mic?.release() }
        runCatching { internal?.release() }
    }

    /** frame: 1 フレームの short 数。あふれたときはフレーム単位で捨てて L/R がずれないようにする */
    private class Fifo(private val cap: Int, private val frame: Int) {
        private val b = ShortArray(cap)
        private var r = 0
        private var size = 0

        @Synchronized
        fun push(src: ShortArray, n: Int) {
            for (i in 0 until n) {
                if (size == cap) { // あふれたら古い方を捨てて遅延が伸びないようにする
                    r = (r + frame) % cap
                    size -= frame
                }
                b[(r + size) % cap] = src[i]
                size++
            }
        }

        @Synchronized
        fun pull(dst: ShortArray, n: Int): Int {
            val m = minOf(n, size)
            for (i in 0 until m) dst[i] = b[(r + i) % cap]
            r = (r + m) % cap
            size -= m
            return m
        }
    }

    companion object {
        const val RATE = 44100
        private const val CHUNK = 1024
        private const val PREBUFFER_NS = 60_000_000L

        private fun fmt(mask: Int) = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(mask)
            .build()

        private fun bufSize(mask: Int) =
            maxOf(AudioRecord.getMinBufferSize(RATE, mask, AudioFormat.ENCODING_PCM_16BIT) * 2, 16384)

        private fun clip(v: Float) = v.toInt().coerceIn(-32768, 32767).toShort()
    }
}
