package app.literecorder

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection

/**
 * マイク・内部音を録って混ぜ、一定間隔で PCM を sink に渡す。
 *
 * 内部音は何も再生していないと届かない端末もあるので、各ソースは別スレッドで FIFO に貯め、
 * ミキサーは時計（System.nanoTime）で一定間隔に取り出す。足りない分は無音で埋める。
 * ボイスチェンジャーはマイクの FIFO に入れる前にだけかける。
 */
class AudioEngine(
    private val src: AudioSrc,
    voice: Voice,
    private val projection: MediaProjection?,
    private val sessionBaseNs: Long,
    /** pcm（インターリーブ 16bit）, short の個数, 録画開始からの µs */
    private val sink: (ShortArray, Int, Long) -> Unit,
) {
    val channels = if (src == AudioSrc.MIC) 1 else 2

    @Volatile private var running = false
    private val fx = if (voice != Voice.OFF) VoiceChanger(voice, RATE) else null
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
            check(mic!!.state == AudioRecord.STATE_INITIALIZED) { "マイクを開けませんでした" }
        }
        if (src.usesInternal) {
            val proj = checkNotNull(projection) { "内部音には画面キャプチャの許可が必要です" }
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
            check(internal!!.state == AudioRecord.STATE_INITIALIZED) { "内部音を開けませんでした" }
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
        var k = 0L
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
            when (src) {
                AudioSrc.MIC -> micBuf.copyInto(out)
                AudioSrc.INTERNAL -> intBuf.copyInto(out)
                else -> for (i in 0 until CHUNK) {
                    val m = micBuf[i].toInt()
                    out[2 * i] = clip(intBuf[2 * i] + m)
                    out[2 * i + 1] = clip(intBuf[2 * i + 1] + m)
                }
            }
            val ptsUs = (captureStartNs - sessionBaseNs) / 1000 + k * CHUNK * 1_000_000L / RATE
            try {
                sink(out, out.size, ptsUs)
            } catch (_: Exception) {
                break
            }
            k++
        }
    }

    /** 録音を止めてスレッドの終了を待つ。戻った後は sink が呼ばれない */
    fun stop() {
        running = false
        runCatching { mic?.stop() }
        runCatching { internal?.stop() }
        threads.forEach { it.join(2000) }
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

        private fun clip(v: Int) = v.coerceIn(-32768, 32767).toShort()
    }
}
