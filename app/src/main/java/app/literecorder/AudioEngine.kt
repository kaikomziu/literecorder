package app.literecorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection

/**
 * マイク・内部音を録って混ぜ、PCM を sink に渡す。
 *
 * 音ズレを防ぐため、録った音には「実際に録れた時刻」（AudioRecord のタイムスタンプ）を付けて貯め、
 * ミキサーはその時刻を基準に、決まった時間軸の上へ並べ直してから書き出す。
 * - 端末の音の時計と System.nanoTime が少しずつずれても、時刻で合わせ直すので遅れが溜まらない
 * - 内部音が届かない時間は無音で埋め、届いた音は正しい時刻の位置に入る
 * - 映像（Surface のタイムスタンプ）と同じ時計なので、映像と音がそろう
 * ボイスチェンジャーはマイクの音にだけ、貯める前にかける。
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
    /** 音ズレ補正（µs）。＋で音を遅らせる */
    private val offsetUs = s.avOffsetMs * 1000L
    private var ns: NoiseSuppressor? = null
    /** 録画中のモニター（イヤホンがつながっているときだけ） */
    private var monitor: android.media.AudioTrack? = null
    private var mic: AudioRecord? = null
    private var internal: AudioRecord? = null
    private val micSrc = Timed(1)
    private val intSrc = Timed(2)
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

        if (src.usesMic && s.monitor && VoicePreview.headphonesConnected(ctx)) {
            monitor = runCatching {
                android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                    )
                    .setBufferSizeInBytes(
                        android.media.AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2,
                    )
                    .setPerformanceMode(android.media.AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build().apply { play() }
            }.getOrNull()
        }

        running = true
        mic?.let { rec -> rec.startRecording(); threads += Thread({ readLoop(rec, micSrc, fx, monitor) }, "lr-mic") }
        internal?.let { rec -> rec.startRecording(); threads += Thread({ readLoop(rec, intSrc, null) }, "lr-int") }
        threads += Thread({ mixLoop() }, "lr-mix")
        threads.forEach { it.start() }
    }

    private fun readLoop(rec: AudioRecord, dst: Timed, fx: VoiceChanger?, mon: android.media.AudioTrack? = null) {
        val buf = ShortArray(2048)
        val ts = AudioTimestamp()
        val ch = dst.ch
        var framesRead = 0L
        while (running) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0) {
                val frames = n / ch
                val now = System.nanoTime()
                // このかたまりの最初のフレームが録れた時刻
                var t = Double.NaN
                if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    t = ts.nanoTime + (framesRead - ts.framePosition) * FRAME_NS
                }
                // タイムスタンプが取れない・おかしいときは「今から読んだ長さぶん前」で代用する
                val exact = !(t.isNaN() || t > now || t < now - 1_000_000_000.0)
                if (!exact) t = now - frames * FRAME_NS
                framesRead += frames
                fx?.process(buf, n)
                dst.push(buf, n, t, exact)
                // モニターは書けるぶんだけ書く（録音を待たせない）
                mon?.write(buf, 0, n, android.media.AudioTrack.WRITE_NON_BLOCKING)
            } else if (n < 0) {
                break
            }
        }
    }

    private fun mixLoop() {
        val micBuf = ShortArray(CHUNK)
        val intBuf = ShortArray(CHUNK * 2)
        val out = ShortArray(CHUNK * channels)
        val chunkNs = CHUNK * FRAME_NS
        // 時間軸の 0 は録音を始めた時刻。各かたまりは「その時刻の音が届き終わるころ」まで待ってから並べる
        val t0 = System.nanoTime().toDouble()
        var k = 0L
        while (running) {
            val chunkStart = t0 + k * chunkNs
            val wait = (chunkStart + chunkNs + WAIT_NS - System.nanoTime()).toLong()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            if (!running) break
            k++

            if (src.usesMic) micSrc.pull(micBuf, CHUNK, chunkStart)
            if (src.usesInternal) intSrc.pull(intBuf, CHUNK, chunkStart)

            // 一時停止していた時間の音は入れない（映像と同じく、録れた時刻で判断する）
            val startNs = chunkStart.toLong()
            if (clock.wasPausedAt(startNs)) continue
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
            val ptsUs = (startNs - sessionBaseNs) / 1000 - clock.pausedUsBefore(startNs) + offsetUs
            if (ptsUs < 0) continue // 補正で録画開始より前になった分は捨てる
            try {
                sink(out, out.size, ptsUs)
            } catch (_: Exception) {
                break
            }
        }
    }

    /** 録音を止めてスレッドの終了を待つ。戻った後は sink が呼ばれない */
    fun stop() {
        running = false
        runCatching { mic?.stop() }
        runCatching { internal?.stop() }
        threads.forEach { it.join(2000) }
        runCatching { monitor?.stop() }
        runCatching { monitor?.release() }
        runCatching { ns?.release() }
        runCatching { mic?.release() }
        runCatching { internal?.release() }
    }

    /**
     * 録れた時刻つきの音のため込み。先頭フレームの時刻（headNs）を覚えておき、
     * 取り出すときは欲しい時刻に合わせて、古すぎる音は捨て、まだ無い部分は無音にする。
     */
    private class Timed(val ch: Int) {
        private val capFrames = RATE * 2 // 2 秒ぶん
        private val b = ShortArray(capFrames * ch)
        private var r = 0          // 読み出し位置（フレーム）
        private var size = 0       // 貯まっているフレーム数
        private var headNs = 0.0   // 先頭フレームが録れた時刻
        private var exactSeen = false // 正確なタイムスタンプを一度でも使ったか
        private var pushes = 0

        @Synchronized
        fun push(src: ShortArray, n: Int, firstNs: Double, exact: Boolean) {
            val frames = n / ch
            // 届いた音の時刻から先頭の時刻を出し直す。タイムスタンプは少し揺れるので、
            // ゆっくり追いかけて（時計のずれだけを直す）、大きく飛んだときだけ一気に合わせる。
            // 録り始めは代わりの時刻しか無いことが多いので、正確な時刻が初めて来たらすぐそれに合わせる
            val measured = firstNs - size * FRAME_NS
            val snap = size == 0 || kotlin.math.abs(measured - headNs) > JUMP_NS || (exact && !exactSeen)
            if (exact) exactSeen = true
            if (!exact && exactSeen) {
                // 正確な時刻が取れているときは、代わりの時刻では動かさない
            } else {
                val alpha = if (pushes < 50) 0.2 else 0.02 // 最初の 1 秒ほどは早めに追いつく
                headNs = if (snap) measured else headNs + (measured - headNs) * alpha
            }
            pushes++
            for (f in 0 until frames) {
                if (size == capFrames) { // あふれたら古い方を捨てる
                    r = (r + 1) % capFrames
                    size--
                    headNs += FRAME_NS
                }
                val w = (r + size) % capFrames
                for (c in 0 until ch) b[w * ch + c] = src[f * ch + c]
                size++
            }
        }

        /**
         * wantNs から frames 分の音を dst に入れる（無い部分は無音）。
         * 時刻のずれが小さいうちは、1 回に数サンプルだけ捨てたり足したりして少しずつ合わせる
         * （1 秒に数 ms 程度なので聞いても分からない）。大きなすき間だけはすぐ無音で埋める。
         */
        @Synchronized
        fun pull(dst: ShortArray, frames: Int, wantNs: Double) {
            dst.fill(0, 0, frames * ch)
            if (size == 0) return
            var diff = Math.round((wantNs - headNs) / FRAME_NS) // ＋なら貯まった音が古い
            val big = kotlin.math.abs(diff) > BIG_FRAMES
            if (!big) {
                diff = when {
                    kotlin.math.abs(diff) <= TOL_FRAMES -> 0
                    diff > 0 -> minOf(diff, STEP_FRAMES.toLong())
                    else -> maxOf(diff, -STEP_FRAMES.toLong())
                }
            }
            if (diff > 0) {
                // 欲しい時刻より前の音は捨てる
                val drop = minOf(diff, size.toLong()).toInt()
                r = (r + drop) % capFrames
                size -= drop
                headNs += drop * FRAME_NS
                diff = 0
            }
            // diff < 0 のとき、その長さだけ先に埋める。大きなすき間は無音、小さな調整は直後の音をくり返す
            val lead = (-diff).coerceAtMost(frames.toLong()).toInt()
            if (!big) for (f in 0 until lead) for (c in 0 until ch) dst[f * ch + c] = b[r * ch + c]
            val take = minOf(frames - lead, size)
            for (f in 0 until take) {
                val i = (r + f) % capFrames
                for (c in 0 until ch) dst[(lead + f) * ch + c] = b[i * ch + c]
            }
            r = (r + take) % capFrames
            size -= take
            headNs += take * FRAME_NS
        }
    }

    companion object {
        const val RATE = 44100
        private const val CHUNK = 1024
        private const val FRAME_NS = 1_000_000_000.0 / RATE
        /** 音が届くまでの待ち時間（入力の遅れ＋読み取りのまとまり） */
        private const val WAIT_NS = 250_000_000L
        /** これより小さい時刻のずれは直さない（約 2ms） */
        private const val TOL_FRAMES = RATE / 500
        /** 1 回（約 23ms）に直すのは最大 4 サンプル */
        private const val STEP_FRAMES = 4
        /** これより大きいずれはすぐ合わせる（約 200ms、内部音が途切れたときなど） */
        private const val BIG_FRAMES = RATE / 5
        /** これより大きく時刻が飛んだら、ゆっくり追わずにすぐ合わせる */
        private const val JUMP_NS = 80_000_000.0

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
