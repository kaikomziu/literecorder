package app.literecorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor

/**
 * ボイスチェンジャーの試し聴き。マイクの声にエフェクトをかけて、そのままイヤホンに流す。
 * スピーカーだとハウリングするので、イヤホンがつながっているときだけ使う。
 */
class VoicePreview(private val s: Settings) {
    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning get() = running

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "lr-preview").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val rate = AudioEngine.RATE
        val minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minIn * 2)
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            running = false
            return
        }
        val ns = if (s.noiseSuppress && NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull()
        } else null
        val minOut = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(minOut)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        val buf = ShortArray(256)
        var key = ""
        var fx: VoiceChanger? = null
        try {
            rec.startRecording()
            track.play()
            while (running) {
                // 設定が変わったらエフェクトを作り直す（試し聴き中に切り替えられる）
                val k = "${s.voice}/${s.semitones}/${s.micGain}"
                if (k != key) {
                    key = k
                    fx = if (s.voice != Voice.OFF) VoiceChanger(s.voice, rate, s.semitones) else null
                }
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                fx?.process(buf, n)
                val g = s.micGain / 100f
                if (g != 1f) for (i in 0 until n) buf[i] = (buf[i] * g).toInt().coerceIn(-32768, 32767).toShort()
                track.write(buf, 0, n)
            }
        } catch (_: Exception) {
        } finally {
            running = false
            runCatching { rec.stop() }
            runCatching { track.stop() }
            ns?.release()
            rec.release()
            track.release()
        }
    }

    companion object {
        /** イヤホン（有線・Bluetooth・USB）がつながっているか */
        fun headphonesConnected(ctx: Context): Boolean {
            val am = ctx.getSystemService(AudioManager::class.java)
            val types = mutableSetOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET,
            )
            if (android.os.Build.VERSION.SDK_INT >= 31) types += AudioDeviceInfo.TYPE_BLE_HEADSET
            return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
        }
    }
}
