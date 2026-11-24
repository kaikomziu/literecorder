package app.literecorder

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** 画面部品（AndroidX を使わずに軽く組むための小さな道具箱） */
object Ui {
    val ACCENT = Color.parseColor("#FF3B30")
    val TEXT = Color.parseColor("#F2F2F5")
    val SUB = Color.parseColor("#8E8E99")
    val CARD = Color.parseColor("#1A1A20")
    val SEG_BG = Color.parseColor("#26262E")

    fun dp(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density
    fun dpi(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

    fun card(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, dp(ctx, 16f))
        setPadding(dpi(ctx, 16), dpi(ctx, 6), dpi(ctx, 16), dpi(ctx, 14))
    }

    fun sectionTitle(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(TEXT)
        setPadding(0, dpi(ctx, 10), 0, 0)
    }

    fun label(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(SUB)
        setPadding(0, dpi(ctx, 10), 0, dpi(ctx, 6))
    }

    fun hint(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 11f
        setTextColor(SUB)
        setPadding(0, dpi(ctx, 6), 0, 0)
    }

    fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = top }
}

/** セグメント式の選択ボタン列 */
class Seg(
    private val ctx: Context,
    title: String,
    labels: List<String>,
    private val canEdit: () -> Boolean,
    private val onSelect: (Int) -> Unit,
) {
    val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val items = ArrayList<TextView>()
    private val optEnabled = BooleanArray(labels.size) { true }
    private var enabled = true
    private var selected = 0
    private val hintView = Ui.hint(ctx, "").apply { visibility = View.GONE }

    init {
        root.addView(Ui.label(ctx, title))
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(Ui.SEG_BG, Ui.dp(ctx, 10f))
            setPadding(Ui.dpi(ctx, 3), Ui.dpi(ctx, 3), Ui.dpi(ctx, 3), Ui.dpi(ctx, 3))
        }
        labels.forEachIndexed { i, label ->
            val tv = TextView(ctx).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(Ui.dpi(ctx, 2), Ui.dpi(ctx, 9), Ui.dpi(ctx, 2), Ui.dpi(ctx, 9))
                setOnClickListener {
                    if (!enabled || !optEnabled[i] || !canEdit()) return@setOnClickListener
                    onSelect(i)
                }
            }
            items += tv
            row.addView(tv, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        root.addView(row)
        root.addView(hintView)
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

    fun setHint(text: String?, warn: Boolean = false) {
        hintView.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        hintView.text = text
        hintView.setTextColor(if (warn) Ui.ACCENT else Ui.SUB)
    }

    fun show(b: Boolean) {
        root.visibility = if (b) View.VISIBLE else View.GONE
    }

    private fun render() {
        items.forEachIndexed { i, tv ->
            val on = i == selected
            tv.background = if (on) Ui.rounded(Ui.ACCENT, Ui.dp(ctx, 8f)) else null
            tv.setTextColor(if (on) Color.WHITE else Ui.TEXT)
            tv.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            tv.alpha = if (optEnabled[i]) 1f else 0.3f
        }
    }
}

/** 見出しと値つきのスライダー */
class Slider(
    ctx: Context,
    private val title: (Int) -> String,
    private val min: Int,
    max: Int,
    private val canEdit: () -> Boolean,
    private val onChange: (Int) -> Unit,
) {
    val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val label = Ui.label(ctx, "")
    private val bar = SeekBar(ctx).apply {
        this.max = max - min
        progressTintList = ColorStateList.valueOf(Ui.ACCENT)
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
        progressBackgroundTintList = ColorStateList.valueOf(Ui.SEG_BG)
        setPadding(Ui.dpi(ctx, 10), Ui.dpi(ctx, 6), Ui.dpi(ctx, 10), Ui.dpi(ctx, 6))
    }
    private var setting = false

    init {
        root.addView(label)
        root.addView(bar)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                label.text = title(p + min)
                if (fromUser && !setting) onChange(p + min)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        // 録画中は触れないようにする（判定とお知らせは押した瞬間に 1 回だけ）
        var blocked = false
        bar.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) blocked = !canEdit()
            blocked
        }
    }

    fun set(v: Int) {
        setting = true
        bar.progress = v - min
        label.text = title(v)
        setting = false
    }

    fun show(b: Boolean) {
        root.visibility = if (b) View.VISIBLE else View.GONE
    }
}
