package dev.petrov.ymplayer2.sidebar

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.min

internal enum class SideBarEdge { LEFT, RIGHT, BOTTOM }

/** The built-in PRISM appearance is independent of the overlay's commands and gestures. */
internal object SideBarAppearance {
    private val surface = Color.rgb(25, 30, 42)
    private val button = Color.rgb(35, 42, 57)
    private val cyan = Color.rgb(103, 220, 245)
    private val pink = Color.rgb(234, 148, 224)

    fun panel(edge: SideBarEdge, density: Float): Drawable = AngledDrawable(edge, density, surface, cyan, 18f)
    fun button(edge: SideBarEdge, density: Float): Drawable = AngledDrawable(edge, density, button, pink, 8f)
    fun handle(edge: SideBarEdge, density: Float): Drawable = AngledDrawable(edge, density, cyan, pink, 7f)

    private class AngledDrawable(
        private val edge: SideBarEdge,
        private val density: Float,
        fill: Int,
        outline: Int,
        private val cutDp: Float,
    ) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; style = Paint.Style.FILL }
        private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = outline; style = Paint.Style.STROKE; strokeWidth = density.coerceAtLeast(1f)
        }
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val inset = outlinePaint.strokeWidth / 2f
            val left = bounds.left + inset
            val top = bounds.top + inset
            val right = bounds.right - inset
            val bottom = bounds.bottom - inset
            val cut = min(cutDp * density, min(right - left, bottom - top) / 3f)
            path.reset()
            when (edge) {
                SideBarEdge.LEFT -> {
                    path.moveTo(left, top)
                    path.lineTo(right, top + cut)
                    path.lineTo(right, bottom - cut)
                    path.lineTo(left, bottom)
                }
                SideBarEdge.RIGHT -> {
                    path.moveTo(left, top + cut)
                    path.lineTo(right, top)
                    path.lineTo(right, bottom)
                    path.lineTo(left, bottom - cut)
                }
                SideBarEdge.BOTTOM -> {
                    path.moveTo(left + cut, top)
                    path.lineTo(right - cut, top)
                    path.lineTo(right, bottom)
                    path.lineTo(left, bottom)
                }
            }
            path.close()
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, outlinePaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            outlinePaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            outlinePaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Required by Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
