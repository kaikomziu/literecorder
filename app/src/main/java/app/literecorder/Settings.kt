package app.literecorder

import android.content.Context

/** 出力形式 */
enum class OutFormat { MP4, MP3 }

/** 録る音 */
enum class AudioSrc {
    NONE, MIC, INTERNAL, BOTH;

    val usesMic get() = this == MIC || this == BOTH
    val usesInternal get() = this == INTERNAL || this == BOTH
}

/** ボイスチェンジャー（マイクにだけかかる） */
enum class Voice { OFF, HIGH, LOW, ROBOT, ECHO }

/** 設定（SharedPreferences にそのまま保存） */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private inline fun <reified T : Enum<T>> getEnum(key: String, def: T): T =
        runCatching { enumValueOf<T>(p.getString(key, def.name)!!) }.getOrDefault(def)

    private fun putEnum(key: String, v: Enum<*>) = p.edit().putString(key, v.name).apply()

    var format: OutFormat
        get() = getEnum("format", OutFormat.MP4)
        set(v) = putEnum("format", v)

    var audio: AudioSrc
        get() = getEnum("audio", AudioSrc.BOTH)
        set(v) = putEnum("audio", v)

    var voice: Voice
        get() = getEnum("voice", Voice.OFF)
        set(v) = putEnum("voice", v)

    /** 短い辺の最大ピクセル（1080 / 720 / 480） */
    var shortSide: Int
        get() = p.getInt("short_side", 720)
        set(v) = p.edit().putInt("short_side", v).apply()

    var fps: Int
        get() = p.getInt("fps", 30)
        set(v) = p.edit().putInt("fps", v).apply()

    /** 画質（0=低 1=中 2=高） */
    var quality: Int
        get() = p.getInt("quality", 1)
        set(v) = p.edit().putInt("quality", v).apply()

    var mp3Kbps: Int
        get() = p.getInt("mp3_kbps", 128)
        set(v) = p.edit().putInt("mp3_kbps", v).apply()

    /** 録画開始時にアプリを裏へ回す */
    var hideOnStart: Boolean
        get() = p.getBoolean("hide_on_start", true)
        set(v) = p.edit().putBoolean("hide_on_start", v).apply()

    /** MP3 は音だけなので「音なし」は不可。その場合はマイクにする */
    val effectiveAudio: AudioSrc
        get() = if (format == OutFormat.MP3 && audio == AudioSrc.NONE) AudioSrc.MIC else audio

    /** 画面キャプチャの許可（MediaProjection）が要るか */
    val needsProjection: Boolean
        get() = format == OutFormat.MP4 || effectiveAudio.usesInternal
}
