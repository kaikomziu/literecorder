package app.literecorder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings as SysSettings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** 白い図形アイコン（一時停止・再生・停止）。画像を持たずに描くので軽い */
class IconView(ctx: Context, var kind: Kind) : View(ctx) {
    enum class Kind { PAUSE, PLAY, STOP }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = minOf(w, h) * 0.42f
        val cx = w / 2
        val cy = h / 2
        when (kind) {
            Kind.PAUSE -> {
                val bw = s * 0.32f
                c.drawRoundRect(RectF(cx - s / 2, cy - s / 2, cx - s / 2 + bw, cy + s / 2), 3f, 3f, paint)
                c.drawRoundRect(RectF(cx + s / 2 - bw, cy - s / 2, cx + s / 2, cy + s / 2), 3f, 3f, paint)
            }
            Kind.PLAY -> {
                path.reset()
                path.moveTo(cx - s * 0.4f, cy - s / 2)
                path.lineTo(cx + s * 0.55f, cy)
                path.lineTo(cx - s * 0.4f, cy + s / 2)
                path.close()
                c.drawPath(path, paint)
            }
            Kind.STOP -> c.drawRoundRect(RectF(cx - s * 0.42f, cy - s * 0.42f, cx + s * 0.42f, cy + s * 0.42f), 4f, 4f, paint)
        }
    }
}

/** 音の大きさの横棒（大きすぎると赤くなる） */
class MeterView(ctx: Context) : View(ctx) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG)
    var level = 0f
        set(v) {
            val c = v.coerceIn(0f, 1f)
            if (c != field) {
                field = c
                invalidate()
            }
        }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val r = h / 2
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), r, r, bg)
        // 小さい音も見えるよう平方根で持ち上げる
        val w = width * kotlin.math.sqrt(level)
        if (w < 1f) return
        fg.color = when {
            level > 0.9f -> 0xFFFF3B30.toInt()
            level > 0.5f -> 0xFFFFCC00.toInt()
            else -> 0xFF34C759.toInt()
        }
        c.drawRoundRect(RectF(0f, 0f, w, h), r, r, fg)
    }
}

/**
 * 他のアプリの上に出す小さな操作ボタン（一時停止 / 停止＋経過時間）と、開始前のカウントダウン。
 * 「他のアプリの上に表示」の許可がないときは何もしない。
 */
class Overlay(private val ctx: Context, private val onPause: () -> Unit, private val onStop: () -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val density = ctx.resources.displayMetrics.density
    private var controls: View? = null
    private var controlsParams: WindowManager.LayoutParams? = null
    private var pauseIcon: IconView? = null
    private var timeText: TextView? = null
    private var meter: MeterView? = null
    private var countdown: TextView? = null

    val allowed get() = SysSettings.canDrawOverlays(ctx)

    private fun dp(v: Int) = (v * density).toInt()

    private fun params(touchable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    )

    /** カウントダウンの数字を出す（n=0 で消す） */
    fun showCountdown(n: Int) {
        if (!allowed) return
        if (n <= 0) {
            countdown?.let { runCatching { wm.removeView(it) } }
            countdown = null
            return
        }
        val tv = countdown ?: TextView(ctx).apply {
            textSize = 64f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xB3000000.toInt()) }
            val size = dp(150)
            minWidth = size
            minHeight = size
            wm.addView(this, params(false).apply { gravity = Gravity.CENTER })
            countdown = this
        }
        tv.text = n.toString()
    }

    /** 操作ボタンを出す */
    @SuppressLint("ClickableViewAccessibility")
    fun showControls() {
        if (!allowed || controls != null) return
        val pill = DragLayout(ctx).apply {
            background = GradientDrawable().apply { setColor(0xD91C1C1E.toInt()); cornerRadius = dp(26).toFloat() }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(10), dp(4))
        }
        val pause = IconView(ctx, IconView.Kind.PAUSE).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
            contentDescription = ctx.getString(R.string.btn_pause)
            setOnClickListener { onPause() }
        }
        val stop = IconView(ctx, IconView.Kind.STOP).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFF3B30.toInt()) }
            contentDescription = ctx.getString(R.string.notif_stop)
            setOnClickListener { onStop() }
        }
        val time = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), 0, 0, 0)
            text = "00:00"
        }
        val meterView = MeterView(ctx)
        val timeCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(time)
            addView(meterView, LinearLayout.LayoutParams(dp(52), dp(4)).apply { leftMargin = dp(8); topMargin = dp(3) })
        }
        row.addView(pause, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(stop, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(6) })
        row.addView(timeCol)
        pill.addView(row)
        val p = params(true).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ctx.resources.displayMetrics.widthPixels - dp(170)
            y = ctx.resources.displayMetrics.heightPixels / 3
            alpha = 0.92f
        }
        pill.onDrag = { dx, dy ->
            p.x += dx
            p.y += dy
            runCatching { wm.updateViewLayout(pill, p) }
        }
        runCatching { wm.addView(pill, p) }.onFailure { return }
        controls = pill
        controlsParams = p
        pauseIcon = pause
        timeText = time
        meter = meterView
    }

    fun update(paused: Boolean, activeMs: Long, level: Float = -1f) {
        meter?.let {
            it.visibility = if (level < 0) View.GONE else View.VISIBLE
            it.level = if (paused) 0f else level
        }
        pauseIcon?.let {
            val k = if (paused) IconView.Kind.PLAY else IconView.Kind.PAUSE
            if (it.kind != k) {
                it.kind = k
                it.contentDescription = ctx.getString(if (paused) R.string.btn_resume else R.string.btn_pause)
                it.invalidate()
            }
        }
        val sec = activeMs / 1000
        timeText?.text = "%02d:%02d".format(sec / 60, sec % 60)
        timeText?.alpha = if (paused) 0.5f else 1f
    }

    fun hideAll() {
        showCountdown(0)
        controls?.let { runCatching { wm.removeView(it) } }
        controls = null
        pauseIcon = null
        timeText = null
        meter = null
    }

    /** 指を動かしたらドラッグ、動かさなければ中のボタンのクリックとして扱う */
    private class DragLayout(ctx: Context) : FrameLayout(ctx) {
        var onDrag: ((Int, Int) -> Unit)? = null
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; lastX = e.rawX; lastY = e.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> if (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop) {
                    dragging = true
                    return true
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.rawX; lastY = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    onDrag?.invoke((e.rawX - lastX).toInt(), (e.rawY - lastY).toInt())
                    lastX = e.rawX
                    lastY = e.rawY
                }
            }
            return true
        }
    }
}
