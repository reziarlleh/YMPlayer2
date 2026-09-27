package dev.petrov.ymplayer2.sidebar

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import kotlin.math.min

internal enum class SideBarEdge { LEFT, RIGHT, BOTTOM }

/** The built-in PRISM appearance is independent of the overlay's commands and gestures. */
internal object SideBarAppearance {
    fun panel(edge: SideBarEdge, density: Float): Drawable = AngledDrawable(
        edge, density, Color.argb(190, 12, 16, 23), Color.argb(220, 255, 255, 255), 20f)

    fun button(density: Float): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.argb(48, 255, 255, 255))
        setStroke(density.coerceAtLeast(1f).toInt(), Color.argb(190, 255, 255, 255))
    }

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
