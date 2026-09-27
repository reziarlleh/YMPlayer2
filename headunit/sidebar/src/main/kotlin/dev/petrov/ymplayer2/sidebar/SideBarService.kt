package dev.petrov.ymplayer2.sidebar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.EnumMap

/** User-enabled overlay. No vendor broadcasts, Accessibility service, or boot auto-start. */
class SideBarService : Service() {
    private enum class Edge { LEFT, RIGHT, BOTTOM }
    private lateinit var windows: WindowManager
    private lateinit var settings: SideBarSettings
    private val handles = EnumMap<Edge, View>(Edge::class.java)
    private var panel: View? = null
    private var edge = Edge.RIGHT
    private val hide = Runnable { collapse() }

    override fun onCreate() {
        super.onCreate()
        settings = SideBarSettings(this)
        windows = getSystemService(WindowManager::class.java)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Панель YMPlayer 2", NotificationManager.IMPORTANCE_LOW))
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val content = launch?.let { PendingIntent.getActivity(this, 0, it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Панель YMPlayer 2")
            .setContentText("Проведите от края экрана, чтобы открыть")
            .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!settings.read().enabled || !settings.hasPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            SHOW -> expand(edge)
            TOGGLE -> if (panel == null) expand(edge) else collapse()
            REFRESH -> if (panel == null) { removeHandles(); showHandles() } else expand(edge)
            else -> if (panel == null && handles.isEmpty()) showHandles()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removePanel(); removeHandles()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showHandles() {
        if (panel != null || handles.isNotEmpty()) return
        for (candidate in Edge.entries) {
            val view = Handle(candidate)
            val width = if (candidate == Edge.BOTTOM) (resources.displayMetrics.widthPixels * .6f).toInt() else dp(28)
            val height = if (candidate == Edge.BOTTOM) dp(28) else (resources.displayMetrics.heightPixels * .6f).toInt()
            val params = params(width, height, candidate)
            if (add(view, params)) handles[candidate] = view
        }
        if (handles.isEmpty()) stopSelf()
    }

    private fun expand(target: Edge) {
        edge = target
        removeHandles(); removePanel()
        val horizontal = edge == Edge.BOTTOM
        val row = LinearLayout(this).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = roundRect(Color.argb(235, 20, 22, 37), Color.argb(180, 104, 218, 244), dp(18))
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) scheduleHide()
                false
            }
        }
        SideBarButton.entries.filter { it in settings.read().buttons }.forEach { button ->
            row.addView(button(button.glyph, button.title) {
                scheduleHide()
                perform(button)
            })
        }
        // Collapse is mandatory and always last, regardless of button preferences.
        row.addView(button("×", "Свернуть") { collapse() })
        val width = if (horizontal) ViewGroup.LayoutParams.WRAP_CONTENT else dp(68)
        val height = if (horizontal) dp(68) else ViewGroup.LayoutParams.WRAP_CONTENT
        if (add(row, params(width, height, edge))) { panel = row; scheduleHide() }
        else showHandles()
    }

    private fun button(glyph: String, label: String, click: () -> Unit) = TextView(this).apply {
        text = glyph
        textSize = 24f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        contentDescription = label
        isClickable = true
        isFocusable = true
        background = roundRect(Color.argb(100, 68, 58, 80), Color.argb(120, 190, 168, 205), dp(14))
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
    }

    private fun perform(button: SideBarButton) {
        try {
            when (button) {
                SideBarButton.VOLUME_UP -> audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                SideBarButton.VOLUME_DOWN -> audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                SideBarButton.MUTE -> audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                SideBarButton.HOME -> startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (_: Exception) { Toast.makeText(this, "${button.title}: действие недоступно", Toast.LENGTH_SHORT).show() }
    }

    private fun audio(): AudioManager = getSystemService(AudioManager::class.java)
    private fun scheduleHide() {
        panel?.removeCallbacks(hide)
        if (settings.read().autoHide) panel?.postDelayed(hide, 8_000L)
    }
    private fun collapse() { removePanel(); showHandles() }
    private fun removePanel() { panel?.removeCallbacks(hide); panel?.let(::remove); panel = null }
    private fun removeHandles() { handles.values.forEach(::remove); handles.clear() }
    private fun add(view: View, parameters: WindowManager.LayoutParams): Boolean = try {
        windows.addView(view, parameters); true
    } catch (_: RuntimeException) { false }
    private fun remove(view: View) { try { windows.removeView(view) } catch (_: RuntimeException) {} }

    private fun params(width: Int, height: Int, target: Edge) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = when (target) {
            Edge.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
            Edge.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
            Edge.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
    }
    private fun roundRect(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radius.toFloat(); setStroke(dp(1), stroke)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private inner class Handle(private val target: Edge) : View(this@SideBarService) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(105, 104, 218, 244); strokeWidth = dp(2).toFloat() }
        private var downX = 0f
        private var downY = 0f
        init { contentDescription = "Открыть панель YMPlayer 2" }
        override fun onDraw(canvas: Canvas) {
            when (target) {
                Edge.LEFT -> canvas.drawLine(1f, 0f, 1f, height.toFloat(), paint)
                Edge.RIGHT -> canvas.drawLine(width - 2f, 0f, width - 2f, height.toFloat(), paint)
                Edge.BOTTOM -> canvas.drawLine(0f, height - 2f, width.toFloat(), height - 2f, paint)
            }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; return true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (when (target) {
                        Edge.LEFT -> dx > dp(32) && dx > kotlin.math.abs(dy) * 1.12f
                        Edge.RIGHT -> dx < -dp(32) && -dx > kotlin.math.abs(dy) * 1.12f
                        Edge.BOTTOM -> dy < -dp(32) && -dy > kotlin.math.abs(dx) * 1.12f
                    }) expand(target)
                    return true
                }
                MotionEvent.ACTION_UP -> return true
            }
            return super.onTouchEvent(event)
        }
    }

    companion object {
        private const val CHANNEL = "ymplayer2_sidebar"
        private const val ID = 2202
        private const val SHOW = "dev.petrov.ymplayer2.sidebar.SHOW"
        private const val TOGGLE = "dev.petrov.ymplayer2.sidebar.TOGGLE"
        private const val REFRESH = "dev.petrov.ymplayer2.sidebar.REFRESH"
        fun start(context: Context, show: Boolean = false) {
            context.startForegroundService(Intent(context, SideBarService::class.java).setAction(if (show) SHOW else null))
        }
        fun toggle(context: Context) {
            context.startForegroundService(Intent(context, SideBarService::class.java).setAction(TOGGLE))
        }
        fun refresh(context: Context) {
            context.startForegroundService(Intent(context, SideBarService::class.java).setAction(REFRESH))
        }
        fun stop(context: Context) { context.stopService(Intent(context, SideBarService::class.java)) }
    }
}
