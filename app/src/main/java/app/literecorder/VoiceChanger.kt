package app.literecorder

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * マイク用の軽量ボイスチェンジャー（モノラル 16bit PCM をその場で書き換える）。
 * - 高い声 / 低い声: 2 本の読み出しヘッドをずらして重ねるディレイ式ピッチシフト（幅は半音単位）
 * - ロボット: リング変調
 * - エコー: フィードバック付きディレイ
 * - ラジオ: 帯域を絞って軽く歪ませる
 * - 電話: さらに狭い帯域＋ビット数・サンプリングを落とす
 * - 宇宙人: 少し高い声＋ゆっくりしたリング変調
 */
class VoiceChanger(private val type: Voice, rate: Int, semitones: Int = 0) {
    // ピッチシフト（半音 n 個ぶん = 2^(n/12) 倍）
    private val ratio = when (type) {
        Voice.HIGH, Voice.LOW -> 2.0.pow(semitones / 12.0)
        Voice.ALIEN -> 2.0.pow(5 / 12.0)
        else -> 1.0
    }

    // ラジオ・電話の帯域フィルター
    private val hp = Biquad.highPass(rate, if (type == Voice.PHONE) 300.0 else 500.0)
    private val lp = Biquad.lowPass(rate, if (type == Voice.PHONE) 3400.0 else 3000.0)
    private var holdCount = 0
    private var held = 0f
    private val alienStep = 2 * PI * 35.0 / rate
    private val win = rate * 0.04          // 窓の長さ（約 40ms）
    private val ring = FloatArray(4096)    // 窓より長いリングバッファ
    private var w = 0
    private var phase = 0.0

    // ロボット
    private val ringStep = 2 * PI * 70.0 / rate
    private var ringPh = 0.0

    // エコー
    private val echo = FloatArray((rate * 0.22).toInt())
    private var echoPos = 0

    fun process(s: ShortArray, n: Int) {
        if (type == Voice.OFF) return
        for (i in 0 until n) {
            val x = s[i] / 32768f
            val y = when (type) {
                Voice.HIGH, Voice.LOW -> pitch(x)
                Voice.ROBOT -> robot(x)
                Voice.ECHO -> echo(x)
                Voice.RADIO -> radio(x)
                Voice.PHONE -> phone(x)
                Voice.ALIEN -> alien(x)
                Voice.OFF -> x
            }
            s[i] = (y * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
        }
    }

    private fun pitch(x: Float): Float {
        ring[w] = x
        // 遅延量 d を (1 - ratio) ずつ動かすと、読み出し速度が ratio 倍になる
        phase += 1.0 - ratio
        if (phase < 0) phase += win
        if (phase >= win) phase -= win
        val d2 = (phase + win / 2) % win
        // 三角窓で、遅延が飛ぶ瞬間（d=0 / d=win）は音量 0 にして継ぎ目を消す
        val g1 = 1.0 - abs(2.0 * phase / win - 1.0)
        val g2 = 1.0 - abs(2.0 * d2 / win - 1.0)
        val y = tap(phase) * g1 + tap(d2) * g2
        w = (w + 1) % ring.size
        return y.toFloat()
    }

    /** d サンプル前の値を線形補間で読む */
    private fun tap(d: Double): Double {
        var pos = w - d
        if (pos < 0) pos += ring.size
        val i0 = pos.toInt() % ring.size
        val i1 = (i0 + 1) % ring.size
        val f = pos - pos.toInt()
        return ring[i0] * (1 - f) + ring[i1] * f
    }

    private fun robot(x: Float): Float {
        ringPh += ringStep
        if (ringPh > 2 * PI) ringPh -= 2 * PI
        return (x * sin(ringPh) * 1.6).toFloat()
    }

    private fun radio(x: Float): Float {
        val y = lp.run(hp.run(x))
        return (kotlin.math.tanh(y * 3.0) / kotlin.math.tanh(3.0) * 0.8).toFloat()
    }

    private fun phone(x: Float): Float {
        val y = lp.run(hp.run(x)) * 1.6f
        // 4 サンプルに 1 回だけ値を取り、6 ビットに丸める（昔の電話っぽい粗さ）
        if (holdCount++ % 4 == 0) held = (Math.round(y * 32f) / 32f).coerceIn(-1f, 1f)
        return held
    }

    private fun alien(x: Float): Float {
        val p = pitch(x)
        ringPh += alienStep
        if (ringPh > 2 * PI) ringPh -= 2 * PI
        return (p * (0.55 + 0.45 * sin(ringPh))).toFloat() * 1.2f
    }

    private fun echo(x: Float): Float {
        val past = echo[echoPos]
        echo[echoPos] = x + past * 0.38f
        echoPos = (echoPos + 1) % echo.size
        return x + past * 0.5f
    }
}

/** 2 次の IIR フィルター（RBJ の式）。ラジオ・電話の声の帯域を絞るのに使う */
class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun run(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x.toDouble(); y2 = y1; y1 = y
        return y.toFloat()
    }

    companion object {
        private const val Q = 0.7071

        fun lowPass(rate: Int, f: Double): Biquad {
            val w = 2 * PI * f / rate
            val alpha = sin(w) / (2 * Q)
            val c = kotlin.math.cos(w)
            val a0 = 1 + alpha
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun highPass(rate: Int, f: Double): Biquad {
            val w = 2 * PI * f / rate
            val alpha = sin(w) / (2 * Q)
            val c = kotlin.math.cos(w)
            val a0 = 1 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
    }
}
