package dev.petrov.ymplayer2.clips

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

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
    private val title = label("", 22f, true)
    private val artist = label("", 16f)
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
        bottom.addView(title)
        bottom.addView(artist)
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
        title.visibility = if (state.clip != null) VISIBLE else GONE
        artist.text = state.clip?.artist.orEmpty()
        artist.visibility = if (artist.text.isNotEmpty()) VISIBLE else GONE
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
