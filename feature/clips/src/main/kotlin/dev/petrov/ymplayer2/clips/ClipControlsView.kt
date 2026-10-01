package dev.petrov.ymplayer2.clips

import android.content.Context
import android.graphics.Canvas
import dev.petrov.ymplayer2.designsystem.skin.ClipPalette
import dev.petrov.ymplayer2.designsystem.skin.PrismSkin
import dev.petrov.ymplayer2.designsystem.skin.clipPalette
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.text.TextUtils

/** Native overlay stays above the video surface on Android 15 release builds. */
class ClipControlsView(context: Context, private val controller: ClipWaveController, private var palette: ClipPalette = PrismSkin.clipPalette(), close: () -> Unit) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { if (latest.playing) { shown = false; display() } }
    private var latest = ClipWaveState()
    private var shown = true
    private var revealKey: Int? = null
    private val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val bottom = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val back = button("← Назад", close)
    private val heading = label("Клипы · Яндекс Музыка", 18f, true)
    private val status = label("", 16f)
    private val infoBand = DiagonalClipInfoBand(context, palette).apply { tag = "clip_info_band" }
    private val title = label("", 22f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val artist = label("", 16f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val nextTitle = label("", 18f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val nextArtist = label("", 14f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val currentCaption = infoLabel("СЕЙЧАС", palette.accent)
    private val nextCaption = infoLabel("ДАЛЕЕ", palette.secondary)
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
            intArrayOf(palette.topShade, palette.clearShade))
        top.addView(back, LinearLayout.LayoutParams(-2, dp(52)))
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        heading.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        addView(top, LayoutParams(-1, -2, Gravity.TOP))

        bottom.setPadding(dp(20), dp(14), dp(20), dp(16))
        bottom.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(palette.bottomShade, palette.clearShade))
        bottom.addView(status)
        val infoRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val currentColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            addView(currentCaption)
            addView(title)
            addView(artist)
        }
        val nextColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(12), dp(12), dp(12))
            addView(nextCaption)
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
        listOf(back, previous, play, next, retry).forEach { it.id = View.generateViewId() }
        previous.nextFocusRightId = play.id
        play.nextFocusLeftId = previous.id
        play.nextFocusRightId = next.id
        next.nextFocusLeftId = play.id
        listOf(previous, play, next).forEach { it.nextFocusUpId = back.id }
        back.nextFocusDownId = play.id
        retry.nextFocusDownId = play.id
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
        val firstClip = latest.clip == null && state.clip != null
        if (latest.clip?.id != state.clip?.id || state.loading) infoBand.setProgress(0L, 0L)
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
        if (firstClip && shown && !isInTouchMode) play.requestFocus()
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
    /** The first navigation press restores a hidden panel; its matching release must not click it. */
    fun handleRemoteKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == revealKey) {
            if (event.action == KeyEvent.ACTION_UP) revealKey = null
            return true
        }
        val mediaAction: (() -> Unit)? = when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> controller::toggle
            KeyEvent.KEYCODE_MEDIA_PLAY -> ({ if (!latest.playing) controller.toggle() })
            KeyEvent.KEYCODE_MEDIA_PAUSE -> controller::pause
            KeyEvent.KEYCODE_MEDIA_NEXT -> controller::next
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> controller::previous
            else -> null
        }
        if (mediaAction != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                mediaAction(); showControls(focus = false)
            }
            return true
        }
        if (code !in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (!shown || !hasFocus()) {
                revealKey = code
                showControls(focus = true)
                return true
            }
            scheduleHide()
        }
        return false
    }
    fun showControls(focus: Boolean = true) {
        val restoreFocus = focus && (!shown || !hasFocus())
        shown = true; display()
        if (restoreFocus) {
            listOf(play, retry, next, previous, back).firstOrNull { it.isEnabled && it.visibility == VISIBLE }?.requestFocus()
        }
        scheduleHide()
    }
    private fun scheduleHide() {
        handler.removeCallbacks(hide)
        if (shown && latest.playing) handler.postDelayed(hide, 5000)
    }
    /** Position polling never reschedules auto-hide or moves D-pad focus. */
    fun updateProgress(positionMs: Long, durationMs: Long) {
        infoBand.setProgress(if (latest.loading || latest.clip == null) 0L else positionMs, durationMs)
    }
    private fun display() {
        top.visibility = if (shown) VISIBLE else GONE
        bottom.visibility = if (shown) VISIBLE else GONE
        if (!shown) (parent as? View)?.requestFocus()
    }
    override fun onDetachedFromWindow() { handler.removeCallbacks(hide); super.onDetachedFromWindow() }

    private fun label(value: String, sp: Float, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = sp; setTextColor(palette.text)
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
        isClickable = true; isFocusable = true; isFocusableInTouchMode = true
        contentDescription = value
        paintButton(this, false)
        onFocusChangeListener = OnFocusChangeListener { _, focused ->
            paintButton(this, focused)
            if (focused) scheduleHide()
        }
        setOnClickListener { action() }
    }
    /** Recolors existing views without touching controller, focus or video position. */
    fun updatePalette(value: ClipPalette) {
        if (palette == value) return
        palette = value
        top.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(palette.topShade, palette.clearShade))
        bottom.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(palette.bottomShade, palette.clearShade))
        listOf(heading, status, title, artist, nextTitle, nextArtist, preview).forEach { it.setTextColor(palette.text) }
        currentCaption.setTextColor(palette.accent)
        nextCaption.setTextColor(palette.secondary)
        listOf(back, retry, previous, play, next).forEach { paintButton(it, it.hasFocus()) }
        infoBand.updatePalette(value)
    }
    private fun paintButton(view: TextView, focused: Boolean) {
        view.setTextColor(if (focused) palette.onAccent else palette.text)
        view.background = GradientDrawable().apply {
            setColor(if (focused) palette.accent else palette.controlSurface)
            cornerRadius = dp(28).toFloat()
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** Two translucent panels share a diagonal edge; the video remains visible underneath. */
private class DiagonalClipInfoBand(context: Context, private var palette: ClipPalette) : FrameLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Path()
    private var progress = 0f

    init { setWillNotDraw(false) }
    fun updatePalette(value: ClipPalette) { palette = value; invalidate() }
    fun setProgress(positionMs: Long, durationMs: Long) {
        val next = if (durationMs > 0L) (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat() else 0f
        if (progress != next) { progress = next; invalidate() }
    }

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
        paint.color = palette.currentPanel
        canvas.drawPath(shape, paint)
        // The diagonal current panel clips the shade, keeping the next title's background untouched.
        if (progress > 0f) {
            val saved = canvas.save()
            canvas.clipPath(shape)
            paint.color = 0x66000000
            canvas.drawRect(0f, 0f, seamTop * progress, h, paint)
            canvas.restoreToCount(saved)
        }
        shape.reset()
        shape.moveTo(seamTop, 0f)
        shape.lineTo(w, 0f)
        shape.lineTo(w, h)
        shape.lineTo(seamBottom, h)
        shape.close()
        paint.color = palette.nextPanel
        canvas.drawPath(shape, paint)
        paint.color = palette.accent
        canvas.drawRect(0f, 0f, resources.displayMetrics.density * 4f, h, paint)
    }
}
