package app.literecorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SysSettings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** メイン画面。AndroidX を使わずコードで組んで軽くしている */
class MainActivity : Activity() {

    companion object {
        const val ACTION_QUICK_START = "app.literecorder.QUICK_START"
        /** ショートカットから: "video"=画面録画 / "audio"=音だけ */
        const val EXTRA_MODE = "mode"
        private const val REQ_PERM = 1
        private const val REQ_CAPTURE = 2
        private const val REQ_PERM_PREVIEW = 3
    }

    private lateinit var s: Settings
    private val ui = Handler(Looper.getMainLooper())
    private var preview: VoicePreview? = null
    /** クイック設定タイルから開かれた（開始後にアプリを閉じる） */
    private var quickLaunch = false

    private lateinit var recDot: View
    private lateinit var pauseBtn: FrameLayout
    private lateinit var pauseIcon: IconView
    private lateinit var recLabel: TextView
    private lateinit var recTime: TextView
    private lateinit var meter: MeterView
    private lateinit var lastRow: LinearLayout
    private lateinit var lastText: TextView
    private lateinit var settingsBox: LinearLayout

    private lateinit var segFormat: Seg
    private lateinit var segKbps: Seg
    private lateinit var segAudio: Seg
    private lateinit var sliderMic: Slider
    private lateinit var sliderInt: Slider
    private lateinit var segNs: Seg
    private lateinit var segVoice: Seg
    private lateinit var sliderPitch: Slider
    private lateinit var previewBtn: TextView
    private lateinit var videoCard: LinearLayout
    private lateinit var segOrient: Seg
    private lateinit var segRes: Seg
    private lateinit var segFps: Seg
    private lateinit var segQuality: Seg
    private lateinit var segCountdown: Seg
    private lateinit var segOverlay: Seg
    private lateinit var segHide: Seg
    private lateinit var segLimitTime: Seg
    private lateinit var segLimitSize: Seg
    private lateinit var segSplit: Seg

    private val kbpsList = listOf(96, 128, 192)
    private val resList = listOf(1080, 720, 480)
    private val countdownList = listOf(0, 3, 5)
    private val limitTimeList = listOf(0, 5, 10, 30, 60)
    private val limitSizeList = listOf(0, 100, 500, 1000)
    private val splitList = listOf(0, 5, 10, 30)

    private val tick = object : Runnable {
        override fun run() {
            refreshState()
            if (RecorderService.state != RecorderService.State.IDLE) ui.postDelayed(this, 120)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Settings(this)
        setContentView(buildUi())
        refreshSettings()
        // 前回強制終了したときの書きかけファイルを片付ける（録画中でなければ）
        if (RecorderService.state == RecorderService.State.IDLE) {
            Thread { runCatching { Store.cleanupPending(applicationContext) } }.start()
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action != ACTION_QUICK_START) return
        i.action = null
        // アイコン長押しのショートカット: 録画か録音かを切り替えてから始める
        when (i.getStringExtra(EXTRA_MODE)) {
            "video" -> s.format = OutFormat.MP4
            "audio" -> if (s.format.isVideo) s.format = OutFormat.MP3
        }
        i.removeExtra(EXTRA_MODE)
        refreshSettings()
        quickLaunch = true
        if (RecorderService.state == RecorderService.State.IDLE) onRecordClick()
    }

    override fun onResume() {
        super.onResume()
        RecorderService.listener = { refreshState(); ui.removeCallbacks(tick); ui.post(tick) }
        refreshSettings()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        RecorderService.listener = null
        ui.removeCallbacks(tick)
        stopPreview()
    }

    private fun t(id: Int, vararg args: Any) = getString(id, *args)
    private fun dp(v: Float) = Ui.dp(this, v)
    private fun dpi(v: Int) = Ui.dpi(this, v)

    // ---------- 録画の開始 ----------

    private fun onRecordClick() {
        when (RecorderService.state) {
            RecorderService.State.RECORDING, RecorderService.State.COUNTDOWN -> { RecorderService.stop(this); return }
            RecorderService.State.STOPPING -> return
            RecorderService.State.IDLE -> Unit
        }
        stopPreview()
        val need = ArrayList<String>()
        if (s.effectiveAudio != AudioSrc.NONE && !granted(Manifest.permission.RECORD_AUDIO)) {
            need += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_PERM) else proceed()
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            REQ_PERM -> {
                if (s.effectiveAudio != AudioSrc.NONE && !granted(Manifest.permission.RECORD_AUDIO)) {
                    Toast.makeText(this, R.string.toast_need_mic, Toast.LENGTH_LONG).show()
                    return
                }
                proceed()
            }
            REQ_PERM_PREVIEW -> if (granted(Manifest.permission.RECORD_AUDIO)) togglePreview()
        }
    }

    private fun proceed() {
        if (s.needsProjection) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        } else {
            RecorderService.start(this, 0, null)
            if (quickLaunch) moveTaskToBack(true)
            quickLaunch = false
        }
    }

    @Deprecated("Activity の標準 API を使う")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, R.string.toast_capture_cancelled, Toast.LENGTH_SHORT).show()
            quickLaunch = false
            return
        }
        RecorderService.start(this, resultCode, data)
        if ((s.format.isVideo && s.hideOnStart) || quickLaunch) moveTaskToBack(true)
        quickLaunch = false
    }

    // ---------- 試し聴き ----------

    private fun togglePreview() {
        if (preview?.isRunning == true) {
            stopPreview()
            return
        }
        if (RecorderService.state != RecorderService.State.IDLE) return
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERM_PREVIEW)
            return
        }
        if (!VoicePreview.headphonesConnected(this)) {
            Toast.makeText(this, R.string.preview_need_headphones, Toast.LENGTH_LONG).show()
            return
        }
        preview = VoicePreview(s).also { it.start() }
        refreshPreviewButton()
    }

    private fun stopPreview() {
        preview?.stop()
        preview = null
        if (::previewBtn.isInitialized) refreshPreviewButton()
    }

    private fun refreshPreviewButton() {
        val on = preview?.isRunning == true
        previewBtn.text = t(if (on) R.string.preview_stop else R.string.preview_start)
        previewBtn.background = Ui.rounded(if (on) Ui.ACCENT else Ui.SEG_BG, dp(10f))
    }

    // ---------- 画面の更新 ----------

    private fun refreshState() {
        val st = RecorderService.state
        val recording = st == RecorderService.State.RECORDING
        val paused = recording && RecorderService.paused
        val video = s.format.isVideo
        val busy = st == RecorderService.State.RECORDING || st == RecorderService.State.COUNTDOWN
        (recDot.background as GradientDrawable).cornerRadius = if (busy) dp(8f) else dp(40f)
        pauseBtn.visibility = if (recording) View.VISIBLE else View.INVISIBLE
        val k = if (paused) IconView.Kind.PLAY else IconView.Kind.PAUSE
        if (pauseIcon.kind != k) {
            pauseIcon.kind = k
            pauseIcon.invalidate()
        }
        pauseBtn.contentDescription = t(if (paused) R.string.btn_resume else R.string.btn_pause)
        recLabel.text = when (st) {
            RecorderService.State.RECORDING -> if (paused) t(R.string.state_paused) else t(R.string.state_tap_stop)
            RecorderService.State.COUNTDOWN -> t(R.string.state_countdown)
            RecorderService.State.STOPPING -> t(R.string.state_saving)
            RecorderService.State.IDLE -> if (video) t(R.string.state_tap_start_video) else t(R.string.state_tap_start_audio)
        }
        recTime.text = when (st) {
            RecorderService.State.RECORDING -> {
                val sec = RecorderService.activeMs() / 1000
                "%02d:%02d".format(sec / 60, sec % 60)
            }
            RecorderService.State.COUNTDOWN -> (RecorderService.countdownLeft + 1).toString()
            else -> s.format.name
        }
        recTime.setTextColor(if (busy) Ui.ACCENT else Ui.TEXT)
        val lv = if (recording) RecorderService.level else -1f
        meter.visibility = if (lv >= 0) View.VISIBLE else View.INVISIBLE
        meter.level = if (paused) 0f else lv.coerceAtLeast(0f)
        recTime.alpha = if (paused) 0.5f else 1f
        settingsBox.alpha = if (st == RecorderService.State.IDLE) 1f else 0.35f
        val name = RecorderService.lastName
        lastRow.visibility = if (name != null) View.VISIBLE else View.GONE
        if (name != null) lastText.text = name
    }

    private fun refreshSettings() {
        val video = s.format.isVideo
        val src = s.effectiveAudio
        segFormat.select(s.format.ordinal)
        segKbps.select(kbpsList.indexOf(s.audioKbps).coerceAtLeast(0))
        segKbps.show(!video)
        segAudio.select(src.ordinal)
        segAudio.setOptionEnabled(AudioSrc.NONE.ordinal, video)
        sliderMic.set(s.micGain)
        sliderMic.show(src.usesMic)
        sliderInt.set(s.internalGain)
        sliderInt.show(src.usesInternal)
        segNs.select(if (s.noiseSuppress) 0 else 1)
        segNs.show(src.usesMic)

        segVoice.select(s.voice.ordinal)
        segVoice.setEnabled(src.usesMic)
        val pitched = s.voice == Voice.HIGH || s.voice == Voice.LOW
        sliderPitch.set(if (s.voice == Voice.LOW) s.pitchDown else s.pitchUp)
        sliderPitch.show(pitched && src.usesMic)
        previewBtn.visibility = if (src.usesMic) View.VISIBLE else View.GONE

        videoCard.visibility = if (video) View.VISIBLE else View.GONE
        segOrient.select(s.orientation)
        segRes.select(resList.indexOf(s.shortSide).coerceAtLeast(0))
        segFps.select(if (s.fps == 60) 1 else 0)
        segQuality.select(s.quality)

        segCountdown.select(countdownList.indexOf(s.countdown).coerceAtLeast(0))
        segCountdown.show(video)
        segOverlay.select(if (s.overlay) 0 else 1)
        segOverlay.setHint(
            when {
                s.overlay && !SysSettings.canDrawOverlays(this) -> t(R.string.overlay_permission)
                s.overlay -> t(R.string.hint_overlay)
                else -> null
            },
            warn = s.overlay && !SysSettings.canDrawOverlays(this),
        )
        segHide.select(if (s.hideOnStart) 0 else 1)
        segHide.show(video)
        segLimitTime.select(limitTimeList.indexOf(s.limitMin).coerceAtLeast(0))
        segLimitSize.select(limitSizeList.indexOf(s.limitMb).coerceAtLeast(0))
        segSplit.select(splitList.indexOf(s.splitMin).coerceAtLeast(0))
        refreshState()
    }

    private fun canEdit(): Boolean {
        if (RecorderService.state == RecorderService.State.IDLE) return true
        Toast.makeText(this, R.string.toast_locked, Toast.LENGTH_SHORT).show()
        return false
    }

    /** 設定を変えて画面に反映する */
    private fun edit(block: () -> Unit) {
        block()
        refreshSettings()
    }

    // ---------- 画面の組み立て ----------

    private fun buildUi(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(TextView(this).apply {
            text = t(R.string.app_name)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.TEXT)
        })
        col.addView(TextView(this).apply {
            text = t(R.string.tagline)
            textSize = 13f
            setTextColor(Ui.SUB)
        })

        // 録画ボタン（右に一時停止ボタン。左は同じ幅の空きで中央をそろえる）
        recDot = View(this).apply { background = Ui.rounded(Ui.ACCENT, dp(40f)) }
        val recButton = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dpi(4), Ui.TEXT)
            }
            addView(recDot, FrameLayout.LayoutParams(dpi(80), dpi(80), Gravity.CENTER))
            isClickable = true
            contentDescription = t(R.string.tile_label)
            setOnClickListener { onRecordClick() }
        }
        pauseIcon = IconView(this, IconView.Kind.PAUSE)
        pauseBtn = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.SEG_BG) }
            addView(pauseIcon, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            isClickable = true
            visibility = View.INVISIBLE
            setOnClickListener { RecorderService.togglePause(this@MainActivity) }
        }
        val recRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(View(context), LinearLayout.LayoutParams(dpi(56), dpi(56)).apply { rightMargin = dpi(24) })
            addView(recButton, LinearLayout.LayoutParams(dpi(120), dpi(120)))
            addView(pauseBtn, LinearLayout.LayoutParams(dpi(56), dpi(56)).apply { leftMargin = dpi(24) })
        }
        col.addView(recRow, Ui.matchWrap(dpi(28)))
        recTime = TextView(this).apply {
            textSize = 22f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        col.addView(recTime, Ui.matchWrap(dpi(12)))
        meter = MeterView(this).apply { visibility = View.INVISIBLE }
        col.addView(meter, LinearLayout.LayoutParams(dpi(140), dpi(6)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dpi(6)
            bottomMargin = dpi(4)
        })
        recLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(Ui.SUB)
            gravity = Gravity.CENTER
        }
        col.addView(recLabel, Ui.matchWrap())

        // 録画一覧
        val libCard = Ui.card(this).apply { setPadding(dpi(16), dpi(12), dpi(16), dpi(12)) }
        lastText = TextView(this).apply { setTextColor(Ui.TEXT); textSize = 14f }
        lastRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpi(10))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = t(R.string.library_last); textSize = 11f; setTextColor(Ui.SUB) })
                addView(lastText)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = t(R.string.open)
                setTextColor(Ui.ACCENT)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dpi(12), dpi(8), dpi(4), dpi(8))
                setOnClickListener { openLast() }
            })
        }
        libCard.addView(lastRow)
        libCard.addView(TextView(this).apply {
            text = t(R.string.library_open_all)
            textSize = 15f
            setTextColor(Ui.TEXT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dpi(4), 0, dpi(4))
            setOnClickListener { startActivity(Intent(this@MainActivity, LibraryActivity::class.java)) }
        }, Ui.matchWrap())
        col.addView(libCard, Ui.matchWrap(dpi(20)))

        // 設定
        settingsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(settingsBox, Ui.matchWrap())
        val ce = { canEdit() }

        // 出力
        val out = Ui.card(this)
        out.addView(Ui.sectionTitle(this, t(R.string.sec_output)))
        segFormat = Seg(this, t(R.string.set_format), listOf("MP4", "MP3", "M4A"), ce) {
            edit { s.format = OutFormat.entries[it] }
        }
        segFormat.setHint(t(R.string.hint_format))
        segKbps = Seg(this, t(R.string.set_audio_quality), kbpsList.map { "${it}kbps" }, ce) {
            edit { s.audioKbps = kbpsList[it] }
        }
        out.addView(segFormat.root)
        out.addView(segKbps.root)
        settingsBox.addView(out, Ui.matchWrap(dpi(12)))

        // 音
        val sound = Ui.card(this)
        sound.addView(Ui.sectionTitle(this, t(R.string.sec_sound)))
        segAudio = Seg(
            this, t(R.string.set_source),
            listOf(t(R.string.src_none), t(R.string.src_mic), t(R.string.src_internal), t(R.string.src_both)), ce,
        ) { edit { s.audio = AudioSrc.entries[it] } }
        sliderMic = Slider(this, { t(R.string.set_mic_vol) + "  $it%" }, 0, 200, ce) { s.micGain = it }
        sliderInt = Slider(this, { t(R.string.set_int_vol) + "  $it%" }, 0, 200, ce) { s.internalGain = it }
        segNs = Seg(this, t(R.string.set_ns), listOf(t(R.string.on), t(R.string.off)), ce) {
            edit { s.noiseSuppress = it == 0 }
        }
        sound.addView(segAudio.root)
        sound.addView(sliderMic.root)
        sound.addView(sliderInt.root)
        sound.addView(segNs.root)
        settingsBox.addView(sound, Ui.matchWrap(dpi(12)))

        // ボイスチェンジャー
        val voice = Ui.card(this)
        voice.addView(Ui.sectionTitle(this, t(R.string.sec_voice)))
        segVoice = Seg(
            this, t(R.string.set_voice),
            listOf(t(R.string.voice_off), t(R.string.voice_high), t(R.string.voice_low), t(R.string.voice_robot), t(R.string.voice_echo)),
            ce,
        ) { edit { s.voice = Voice.entries[it] } }
        sliderPitch = Slider(this, {
            t(R.string.set_pitch, if (s.voice == Voice.LOW) "−$it" else "+$it")
        }, 1, 12, ce) {
            if (s.voice == Voice.LOW) s.pitchDown = it else s.pitchUp = it
        }
        previewBtn = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dpi(11), 0, dpi(11))
            setOnClickListener { togglePreview() }
        }
        voice.addView(segVoice.root)
        voice.addView(sliderPitch.root)
        voice.addView(previewBtn, Ui.matchWrap(dpi(14)))
        refreshPreviewButton()
        settingsBox.addView(voice, Ui.matchWrap(dpi(12)))

        // 映像
        videoCard = Ui.card(this)
        videoCard.addView(Ui.sectionTitle(this, t(R.string.sec_video)))
        segOrient = Seg(
            this, t(R.string.set_orientation),
            listOf(t(R.string.orient_auto), t(R.string.orient_portrait), t(R.string.orient_landscape)), ce,
        ) { edit { s.orientation = it } }
        segOrient.setHint(t(R.string.hint_orientation))
        segRes = Seg(this, t(R.string.set_resolution), resList.map { "${it}p" }, ce) { edit { s.shortSide = resList[it] } }
        segFps = Seg(this, t(R.string.set_fps), listOf("30fps", "60fps"), ce) { edit { s.fps = if (it == 1) 60 else 30 } }
        segQuality = Seg(this, t(R.string.set_quality), listOf(t(R.string.q_low), t(R.string.q_mid), t(R.string.q_high)), ce) {
            edit { s.quality = it }
        }
        videoCard.addView(segOrient.root)
        videoCard.addView(segRes.root)
        videoCard.addView(segFps.root)
        videoCard.addView(segQuality.root)
        settingsBox.addView(videoCard, Ui.matchWrap(dpi(12)))

        // 録画の操作
        val ctl = Ui.card(this)
        ctl.addView(Ui.sectionTitle(this, t(R.string.sec_control)))
        segCountdown = Seg(
            this, t(R.string.set_countdown),
            countdownList.map { if (it == 0) t(R.string.none) else t(R.string.cd_sec, it) }, ce,
        ) { edit { s.countdown = countdownList[it] } }
        segOverlay = Seg(this, t(R.string.set_overlay), listOf(t(R.string.show), t(R.string.hide)), ce) {
            edit { s.overlay = it == 0 }
            if (it == 0 && !SysSettings.canDrawOverlays(this)) {
                // 「他のアプリの上に表示」の設定画面を開く（戻ったら onResume で表示を更新）
                runCatching {
                    startActivity(Intent(SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        }
        segHide = Seg(this, t(R.string.set_hide), listOf(t(R.string.close), t(R.string.keep_open)), ce) {
            edit { s.hideOnStart = it == 0 }
        }
        segLimitTime = Seg(
            this, t(R.string.set_limit_time),
            limitTimeList.map {
                when (it) {
                    0 -> t(R.string.none)
                    60 -> t(R.string.hour_1)
                    else -> t(R.string.min_fmt, it)
                }
            },
            ce,
        ) { edit { s.limitMin = limitTimeList[it] } }
        segLimitSize = Seg(
            this, t(R.string.set_limit_size),
            limitSizeList.map { if (it == 0) t(R.string.none) else if (it >= 1000) "${it / 1000}GB" else "${it}MB" }, ce,
        ) { edit { s.limitMb = limitSizeList[it] } }
        ctl.addView(segCountdown.root)
        ctl.addView(segOverlay.root)
        ctl.addView(segHide.root)
        segSplit = Seg(
            this, t(R.string.set_split),
            splitList.map { if (it == 0) t(R.string.split_off) else t(R.string.split_every, it) }, ce,
        ) { edit { s.splitMin = splitList[it] } }
        segSplit.setHint(t(R.string.hint_split))
        ctl.addView(segLimitTime.root)
        ctl.addView(segLimitSize.root)
        ctl.addView(segSplit.root)
        settingsBox.addView(ctl, Ui.matchWrap(dpi(12)))

        col.addView(TextView(this).apply {
            text = t(R.string.footer_note)
            textSize = 11f
            setTextColor(Ui.SUB)
            setLineSpacing(0f, 1.3f)
        }, Ui.matchWrap(dpi(16)))

        return ScrollView(this).apply {
            isFillViewport = true
            addView(col)
            // Android 15 は全画面表示が標準なので、ステータスバー・ナビバーの分だけ余白をとる
            setOnApplyWindowInsetsListener { _, insets ->
                val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                    val b = insets.getInsets(WindowInsets.Type.systemBars())
                    b.top to b.bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop to insets.systemWindowInsetBottom
                }
                col.setPadding(dpi(20), dpi(20) + top, dpi(20), dpi(32) + bottom)
                insets
            }
        }
    }

    private fun openLast() {
        val u = RecorderService.lastUri ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, RecorderService.lastMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { Toast.makeText(this, R.string.toast_no_app, Toast.LENGTH_SHORT).show() }
    }
}
