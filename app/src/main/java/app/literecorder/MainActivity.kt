package app.literecorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 画面は 1 枚だけ。AndroidX を使わずコードで組んで軽くしている */
class MainActivity : Activity() {

    private lateinit var s: Settings
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var recButton: FrameLayout
    private lateinit var recDot: View
    private lateinit var recLabel: TextView
    private lateinit var recTime: TextView
    private lateinit var lastCard: LinearLayout
    private lateinit var lastText: TextView
    private lateinit var settingsBox: LinearLayout

    private lateinit var segFormat: Seg
    private lateinit var segAudio: Seg
    private lateinit var segVoice: Seg
    private lateinit var segRes: Seg
    private lateinit var segFps: Seg
    private lateinit var segQuality: Seg
    private lateinit var segMp3: Seg
    private lateinit var segHide: Seg
    private lateinit var videoGroup: LinearLayout

    private val tick = object : Runnable {
        override fun run() {
            refreshState()
            if (RecorderService.state == RecorderService.State.RECORDING) ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Settings(this)
        setContentView(buildUi())
        refreshSettings()
    }

    override fun onResume() {
        super.onResume()
        RecorderService.listener = { refreshState(); ui.removeCallbacks(tick); ui.post(tick) }
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        RecorderService.listener = null
        ui.removeCallbacks(tick)
    }

    // ---------- 録画の開始 ----------

    private fun onRecordClick() {
        when (RecorderService.state) {
            RecorderService.State.RECORDING -> { RecorderService.stop(this); return }
            RecorderService.State.STOPPING -> return
            RecorderService.State.IDLE -> Unit
        }
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
        if (requestCode != REQ_PERM) return
        if (s.effectiveAudio != AudioSrc.NONE && !granted(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(this, "音を録るにはマイクの許可が必要です（内部音でも必要です）", Toast.LENGTH_LONG).show()
            return
        }
        proceed()
    }

    private fun proceed() {
        if (s.needsProjection) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        } else {
            RecorderService.start(this, 0, null)
        }
    }

    @Deprecated("Activity の標準 API を使う")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "画面キャプチャがキャンセルされました", Toast.LENGTH_SHORT).show()
            return
        }
        RecorderService.start(this, resultCode, data)
        if (s.format == OutFormat.MP4 && s.hideOnStart) moveTaskToBack(true)
    }

    // ---------- 画面の更新 ----------

    private fun refreshState() {
        val st = RecorderService.state
        val recording = st == RecorderService.State.RECORDING
        val mp4 = s.format == OutFormat.MP4
        (recDot.background as GradientDrawable).cornerRadius = if (recording) dp(8f) else dp(40f)
        recLabel.text = when (st) {
            RecorderService.State.RECORDING -> "タップで停止"
            RecorderService.State.STOPPING -> "保存中…"
            RecorderService.State.IDLE -> if (mp4) "タップで録画開始" else "タップで録音開始"
        }
        recTime.text = if (recording) {
            val sec = (System.currentTimeMillis() - RecorderService.startedAt) / 1000
            "%02d:%02d".format(sec / 60, sec % 60)
        } else {
            if (mp4) "MP4" else "MP3"
        }
        recTime.setTextColor(if (recording) ACCENT else TEXT)
        settingsBox.alpha = if (st == RecorderService.State.IDLE) 1f else 0.35f
        val name = RecorderService.lastName
        lastCard.visibility = if (name != null) View.VISIBLE else View.GONE
        if (name != null) lastText.text = name
    }

    private fun refreshSettings() {
        val mp3 = s.format == OutFormat.MP3
        segFormat.select(s.format.ordinal)
        segAudio.select(s.effectiveAudio.ordinal)
        segAudio.setOptionEnabled(AudioSrc.NONE.ordinal, !mp3)
        segVoice.select(s.voice.ordinal)
        segVoice.setEnabled(s.effectiveAudio.usesMic)
        segRes.select(listOf(1080, 720, 480).indexOf(s.shortSide).coerceAtLeast(0))
        segFps.select(if (s.fps == 60) 1 else 0)
        segQuality.select(s.quality)
        segMp3.select(listOf(96, 128, 192).indexOf(s.mp3Kbps).coerceAtLeast(0))
        segHide.select(if (s.hideOnStart) 0 else 1)
        videoGroup.visibility = if (mp3) View.GONE else View.VISIBLE
        segMp3.root.visibility = if (mp3) View.VISIBLE else View.GONE
        refreshState()
    }

    private fun canEdit(): Boolean {
        if (RecorderService.state == RecorderService.State.IDLE) return true
        Toast.makeText(this, "録画中は設定を変えられません", Toast.LENGTH_SHORT).show()
        return false
    }

    // ---------- 画面の組み立て ----------

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(28), dpi(20), dpi(32))
        }

        col.addView(TextView(this).apply {
            text = "LiteRecorder"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT)
        })
        col.addView(TextView(this).apply {
            text = "超軽量 録画・録音（MP4 / MP3）"
            textSize = 13f
            setTextColor(SUB)
        })

        // 録画ボタン
        recDot = View(this).apply {
            background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(40f) }
        }
        recButton = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dpi(4), TEXT)
            }
            addView(recDot, FrameLayout.LayoutParams(dpi(80), dpi(80), Gravity.CENTER))
            isClickable = true
            contentDescription = "録画ボタン"
            setOnClickListener { onRecordClick() }
        }
        col.addView(recButton, LinearLayout.LayoutParams(dpi(120), dpi(120)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dpi(28)
        })
        recTime = TextView(this).apply {
            textSize = 22f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        col.addView(recTime, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(12) })
        recLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(SUB)
            gravity = Gravity.CENTER
        }
        col.addView(recLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // 最後に保存したファイル
        lastText = TextView(this).apply { setTextColor(TEXT); textSize = 14f }
        lastCard = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = "最後に保存"; textSize = 11f; setTextColor(SUB) })
                addView(lastText)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = "開く"
                setTextColor(ACCENT)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dpi(12), dpi(8), dpi(4), dpi(8))
                setOnClickListener { openLast() }
            })
        }
        col.addView(lastCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(20) })

        // 設定
        settingsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(settingsBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(12) })

        val main = card()
        segFormat = Seg("出力形式", listOf("MP4（画面＋音）", "MP3（音だけ）")) {
            s.format = OutFormat.entries[it]
        }
        segAudio = Seg("録る音", listOf("なし", "マイク", "内部音", "両方")) {
            s.audio = AudioSrc.entries[it]
        }
        segVoice = Seg("ボイスチェンジャー（マイクだけにかかる）", listOf("オフ", "高い声", "低い声", "ロボ", "エコー")) {
            s.voice = Voice.entries[it]
        }
        main.addView(segFormat.root)
        main.addView(segAudio.root)
        main.addView(segVoice.root)
        settingsBox.addView(main, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(12) })

        videoGroup = card()
        segRes = Seg("解像度（短い辺）", listOf("1080p", "720p", "480p")) {
            s.shortSide = listOf(1080, 720, 480)[it]
        }
        segFps = Seg("フレームレート", listOf("30fps", "60fps")) { s.fps = if (it == 1) 60 else 30 }
        segQuality = Seg("画質（ファイルの大きさ）", listOf("低・小さい", "中", "高・大きい")) { s.quality = it }
        segHide = Seg("開始時にアプリを閉じる", listOf("閉じる", "閉じない")) { s.hideOnStart = it == 0 }
        videoGroup.addView(segRes.root)
        videoGroup.addView(segFps.root)
        videoGroup.addView(segQuality.root)
        videoGroup.addView(segHide.root)
        settingsBox.addView(videoGroup, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(12) })

        val mp3Card = card()
        segMp3 = Seg("MP3 の音質", listOf("96kbps", "128kbps", "192kbps")) {
            s.mp3Kbps = listOf(96, 128, 192)[it]
        }
        mp3Card.addView(segMp3.root)
        settingsBox.addView(mp3Card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(12) })

        col.addView(TextView(this).apply {
            text = "保存先: 動画 → Movies/LiteRecorder　音声 → Music/LiteRecorder\n" +
                "内部音は、アプリ側が録音を禁止している音（一部のアプリや通話）は入りません。"
            textSize = 11f
            setTextColor(SUB)
            setLineSpacing(0f, 1.3f)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpi(16) })

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
        val type = if (RecorderService.lastIsVideo) "video/mp4" else "audio/mpeg"
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { Toast.makeText(this, "開けるアプリがありません", Toast.LENGTH_SHORT).show() }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(16f) }
        setPadding(dpi(16), dpi(6), dpi(16), dpi(14))
    }

    /** セグメント式の選択ボタン列 */
    private inner class Seg(title: String, labels: List<String>, private val onSelect: (Int) -> Unit) {
        val root = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        private val items = ArrayList<TextView>()
        private val optEnabled = BooleanArray(labels.size) { true }
        private var enabled = true
        private var selected = 0

        init {
            root.addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 12f
                setTextColor(SUB)
                setPadding(0, dpi(10), 0, dpi(6))
            })
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply { setColor(SEG_BG); cornerRadius = dp(10f) }
                setPadding(dpi(3), dpi(3), dpi(3), dpi(3))
            }
            labels.forEachIndexed { i, label ->
                val tv = TextView(this@MainActivity).apply {
                    text = label
                    textSize = 13f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(dpi(4), dpi(9), dpi(4), dpi(9))
                    setOnClickListener {
                        if (!enabled || !optEnabled[i] || !canEdit()) return@setOnClickListener
                        onSelect(i)
                        refreshSettings()
                    }
                }
                items += tv
                row.addView(tv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            root.addView(row)
        }

        fun select(i: Int) {
            selected = i
            render()
        }

        fun setEnabled(b: Boolean) {
            enabled = b
            root.alpha = if (b) 1f else 0.35f
        }

        fun setOptionEnabled(i: Int, b: Boolean) {
            optEnabled[i] = b
            render()
        }

        private fun render() {
            items.forEachIndexed { i, tv ->
                val on = i == selected
                tv.background = if (on) GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(8f) } else null
                tv.setTextColor(if (on) Color.WHITE else TEXT)
                tv.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
                tv.alpha = if (optEnabled[i]) 1f else 0.3f
            }
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dpi(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_PERM = 1
        private const val REQ_CAPTURE = 2
        private val ACCENT = Color.parseColor("#FF3B30")
        private val TEXT = Color.parseColor("#F2F2F5")
        private val SUB = Color.parseColor("#8E8E99")
        private val CARD = Color.parseColor("#1A1A20")
        private val SEG_BG = Color.parseColor("#26262E")
    }
}
