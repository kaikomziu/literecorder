package app.literecorder

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * マイク用の軽量ボイスチェンジャー（モノラル 16bit PCM をその場で書き換える）。
 * - 高い声 / 低い声: 2 本の読み出しヘッドをずらして重ねるディレイ式ピッチシフト
 * - ロボット: リング変調
 * - エコー: フィードバック付きディレイ
 */
class VoiceChanger(private val type: Voice, rate: Int) {
    // ピッチシフト
    private val ratio = when (type) {
        Voice.HIGH -> 1.5
        Voice.LOW -> 0.72
        else -> 1.0
    }
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

    private fun echo(x: Float): Float {
        val past = echo[echoPos]
        echo[echoPos] = x + past * 0.38f
        echoPos = (echoPos + 1) % echo.size
        return x + past * 0.5f
    }
}
