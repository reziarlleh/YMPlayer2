package dev.petrov.ymplayer2.clips

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.text.TextUtils

/** Native overlay stays above the video surface on Android 15 release builds. */
class ClipControlsView(context: Context, private val controller: ClipWaveController, close: () -> Unit) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { if (latest.playing) { shown = false; display() } }
    private var latest = ClipWaveState()
    private var shown = true
    private val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val bottom = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val back = button("← Назад", close)
    private val heading = label("Клипы · Яндекс Музыка", 18f, true)
    private val status = label("", 16f)
    private val infoBand = DiagonalClipInfoBand(context)
    private val title = label("", 22f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val artist = label("", 16f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val nextTitle = label("", 18f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val nextArtist = label("", 14f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val preview = label("Предпросмотр", 14f)
    private val retry = button("Повторить", controller::retry)
    private val previous = button("◀", controller::previous)
    private val play = button("▶", controller::toggle)
    private val next = button("▶▶", controller::next)

    init {
        isClickable = false
        previous.contentDescription = "Предыдущий клип"
        next.contentDescription = "Следующий клип"
        top.setPadding(dp(12), dp(12), dp(12), dp(8))
        top.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xcc11151e.toInt(), 0x0011151e))
        top.addView(back, LinearLayout.LayoutParams(-2, dp(52)))
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        heading.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        addView(top, LayoutParams(-1, -2, Gravity.TOP))

        bottom.setPadding(dp(20), dp(14), dp(20), dp(16))
        bottom.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xe611151e.toInt(), 0x0011151e))
        bottom.addView(status)
        val infoRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val currentColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            addView(infoLabel("СЕЙЧАС", 0xff73dff2.toInt()))
            addView(title)
            addView(artist)
        }
        val nextColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(12), dp(12), dp(12))
            addView(infoLabel("ДАЛЕЕ", 0xffe3a1d6.toInt()))
            addView(nextTitle)
            addView(nextArtist)
        }
        infoRow.addView(currentColumn, LinearLayout.LayoutParams(0, -2, 3f))
        infoRow.addView(nextColumn, LinearLayout.LayoutParams(0, -2, 2f))
        infoBand.minimumHeight = dp(96)
        infoBand.addView(infoRow, LayoutParams(-1, -2))
        bottom.addView(infoBand, LinearLayout.LayoutParams(-1, -2))
        bottom.addView(preview)
        bottom.addView(retry, LinearLayout.LayoutParams(-2, dp(52)))
        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        actions.addView(previous, LinearLayout.LayoutParams(dp(76), dp(56)))
        actions.addView(play, LinearLayout.LayoutParams(dp(96), dp(64)))
        actions.addView(next, LinearLayout.LayoutParams(dp(76), dp(56)))
        bottom.addView(actions, LinearLayout.LayoutParams(-1, -2))
        addView(bottom, LayoutParams(-1, -2, Gravity.BOTTOM))
        setOnApplyWindowInsetsListener { _, insets ->
            top.setPadding(dp(12), insets.systemWindowInsetTop + dp(8), dp(12), dp(8))
            bottom.setPadding(dp(20), dp(14), dp(20), insets.systemWindowInsetBottom + dp(12))
            insets
        }
        render(latest)
    }

    fun render(state: ClipWaveState) {
        latest = state
        status.text = state.issue ?: if (state.loading) "Загрузка клипов…" else ""
        status.visibility = if (status.text.isNotEmpty()) VISIBLE else GONE
        title.text = state.clip?.title.orEmpty()
        artist.text = state.clip?.artist.orEmpty()
        nextTitle.text = state.nextClip?.title ?: "Следующий клип пока не определён"
        nextArtist.text = state.nextClip?.artist.orEmpty()
        nextArtist.visibility = if (nextArtist.text.isNotEmpty()) VISIBLE else GONE
        infoBand.visibility = if (state.clip != null) VISIBLE else GONE
        preview.visibility = if (state.preview) VISIBLE else GONE
        retry.visibility = if (state.issue != null) VISIBLE else GONE
        previous.isEnabled = state.canGoBack
        play.isEnabled = state.clip != null
        play.text = if (state.playing) "Ⅱ" else "▶"
        play.contentDescription = if (state.playing) "Пауза" else "Воспроизвести"
        next.isEnabled = state.clip != null && !state.loading
        for (control in listOf(previous, play, next)) control.alpha = if (control.isEnabled) 1f else .42f
        if (!state.playing || state.issue != null) shown = true
        display()
        handler.removeCallbacks(hide)
        if (shown && state.playing) handler.postDelayed(hide, 5000)
    }
    fun toggleVisibility() {
        if (latest.issue != null || !latest.playing) return
        shown = !shown
        display()
        handler.removeCallbacks(hide)
        if (shown) handler.postDelayed(hide, 5000)
    }
    private fun display() {
        top.visibility = if (shown) VISIBLE else GONE
        bottom.visibility = if (shown) VISIBLE else GONE
    }
    override fun onDetachedFromWindow() { handler.removeCallbacks(hide); super.onDetachedFromWindow() }

    private fun label(value: String, sp: Float, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = sp; setTextColor(Color.WHITE)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 2
        setPadding(dp(6), dp(4), dp(6), dp(4))
    }
    private fun infoLabel(value: String, color: Int) = label(value, 11f, true).apply {
        setTextColor(color)
        letterSpacing = .12f
    }
    private fun button(value: String, action: () -> Unit) = label(value, 18f, true).apply {
        gravity = Gravity.CENTER
        isClickable = true; isFocusable = true
        contentDescription = value
        fun paint(focused: Boolean) {
            setTextColor(if (focused) 0xff142029.toInt() else Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (focused) 0xff74def1.toInt() else 0x99343a46.toInt())
                cornerRadius = dp(28).toFloat()
            }
        }
        paint(false)
        onFocusChangeListener = OnFocusChangeListener { _, focused -> paint(focused) }
        setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** Two translucent panels share a diagonal edge; the video remains visible underneath. */
private class DiagonalClipInfoBand(context: Context) : FrameLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Path()

    init { setWillNotDraw(false) }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val seamTop = w * .62f
        val seamBottom = w * .58f
        shape.reset()
        shape.moveTo(0f, 0f)
        shape.lineTo(seamTop, 0f)
        shape.lineTo(seamBottom, h)
        shape.lineTo(0f, h)
        shape.close()
        paint.color = 0xdd102b38.toInt()
        canvas.drawPath(shape, paint)
        shape.reset()
        shape.moveTo(seamTop, 0f)
        shape.lineTo(w, 0f)
        shape.lineTo(w, h)
        shape.lineTo(seamBottom, h)
        shape.close()
        paint.color = 0xdd211b2c.toInt()
        canvas.drawPath(shape, paint)
        paint.color = 0xff62dbf1.toInt()
        canvas.drawRect(0f, 0f, resources.displayMetrics.density * 4f, h, paint)
    }
}
