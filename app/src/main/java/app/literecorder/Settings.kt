package app.literecorder

import android.content.Context

/** 出力形式 */
enum class OutFormat {
    MP4, MP3, M4A;

    val isVideo get() = this == MP4
}

/** 録る音 */
enum class AudioSrc {
    NONE, MIC, INTERNAL, BOTH;

    val usesMic get() = this == MIC || this == BOTH
    val usesInternal get() = this == INTERNAL || this == BOTH
}

/** ボイスチェンジャー（マイクにだけかかる） */
enum class Voice { OFF, HIGH, LOW, ROBOT, ECHO, RADIO, PHONE, ALIEN }

/** 設定（SharedPreferences にそのまま保存） */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private inline fun <reified T : Enum<T>> getEnum(key: String, def: T): T =
        runCatching { enumValueOf<T>(p.getString(key, def.name)!!) }.getOrDefault(def)

    private fun putEnum(key: String, v: Enum<*>) = p.edit().putString(key, v.name).apply()
    private fun int(key: String, def: Int) = p.getInt(key, def)
    private fun setInt(key: String, v: Int) = p.edit().putInt(key, v).apply()

    var format: OutFormat
        get() = getEnum("format", OutFormat.MP4)
        set(v) = putEnum("format", v)

    var audio: AudioSrc
        get() = getEnum("audio", AudioSrc.BOTH)
        set(v) = putEnum("audio", v)

    var voice: Voice
        get() = getEnum("voice", Voice.OFF)
        set(v) = putEnum("voice", v)

    /** 高い声のずらし幅（半音、1〜12） */
    var pitchUp: Int
        get() = int("pitch_up", 7)
        set(v) = setInt("pitch_up", v)

    /** 低い声のずらし幅（半音、1〜12） */
    var pitchDown: Int
        get() = int("pitch_down", 6)
        set(v) = setInt("pitch_down", v)

    /** 実際にかける半音数（高い声は +、低い声は -） */
    val semitones: Int
        get() = when (voice) {
            Voice.HIGH -> pitchUp
            Voice.LOW -> -pitchDown
            else -> 0
        }

    /** マイク・内部音の音量（%、0〜200） */
    var micGain: Int
        get() = int("mic_gain", 100)
        set(v) = setInt("mic_gain", v)

    var internalGain: Int
        get() = int("int_gain", 100)
        set(v) = setInt("int_gain", v)

    /** 音ズレ補正（ms、-500〜+500）。＋で音を遅らせる */
    var avOffsetMs: Int
        get() = int("av_offset_ms", 0)
        set(v) = setInt("av_offset_ms", v)

    var noiseSuppress: Boolean
        get() = p.getBoolean("noise_suppress", false)
        set(v) = p.edit().putBoolean("noise_suppress", v).apply()

    /** 向き（0=開始時の向き 1=縦 2=横） */
    var orientation: Int
        get() = int("orientation", 0)
        set(v) = setInt("orientation", v)

    /** 短い辺の最大ピクセル（1080 / 720 / 480） */
    var shortSide: Int
        get() = int("short_side", 720)
        set(v) = setInt("short_side", v)

    var fps: Int
        get() = int("fps", 30)
        set(v) = setInt("fps", v)

    /** 画質（0=低 1=中 2=高） */
    var quality: Int
        get() = int("quality", 1)
        set(v) = setInt("quality", v)

    /** MP3 / M4A の音質（kbps） */
    var audioKbps: Int
        get() = int("mp3_kbps", 128)
        set(v) = setInt("mp3_kbps", v)

    /** 録画開始前のカウントダウン（秒、0=なし） */
    var countdown: Int
        get() = int("countdown", 3)
        set(v) = setInt("countdown", v)

    /** 画面に浮かぶ操作ボタンを出す */
    var overlay: Boolean
        get() = p.getBoolean("overlay", false)
        set(v) = p.edit().putBoolean("overlay", v).apply()

    /** 録画開始時にアプリを裏へ回す */
    var hideOnStart: Boolean
        get() = p.getBoolean("hide_on_start", true)
        set(v) = p.edit().putBoolean("hide_on_start", v).apply()

    /** 自動停止する時間（分、0=なし） */
    var limitMin: Int
        get() = int("limit_min", 0)
        set(v) = setInt("limit_min", v)

    /** 自動停止するサイズ（MB、0=なし） */
    var limitMb: Int
        get() = int("limit_mb", 0)
        set(v) = setInt("limit_mb", v)

    /** 録画中に自分の声（エフェクト後）をイヤホンで聞く */
    var monitor: Boolean
        get() = p.getBoolean("monitor", false)
        set(v) = p.edit().putBoolean("monitor", v).apply()

    /** 分割保存の間隔（分、0=分割しない） */
    var splitMin: Int
        get() = int("split_min", 0)
        set(v) = setInt("split_min", v)

    /** 音だけの形式では「音なし」は不可。その場合はマイクにする */
    val effectiveAudio: AudioSrc
        get() = if (!format.isVideo && audio == AudioSrc.NONE) AudioSrc.MIC else audio

    /** 画面キャプチャの許可（MediaProjection）が要るか */
    val needsProjection: Boolean
        get() = format.isVideo || effectiveAudio.usesInternal
}
