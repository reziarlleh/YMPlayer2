package dev.petrov.ymplayer2.sidebar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.EnumMap

/** User-enabled overlay. K4811 commands stay in K4811Controls; no Accessibility service or boot auto-start. */
class SideBarService : Service() {
    private lateinit var windows: WindowManager
    private lateinit var settings: SideBarSettings
    private val handles = EnumMap<SideBarEdge, View>(SideBarEdge::class.java)
    private var panel: View? = null
    private var edge = SideBarEdge.RIGHT
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The bottom window's width depends on the current display width. Keeping the old
        // window after rotation silently restores a large touch-blocking rectangle.
        if (panel != null) expand(edge)
        else if (handles.isNotEmpty()) { removeHandles(); showHandles() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showHandles() {
        if (panel != null || handles.isNotEmpty()) return
        for (candidate in SideBarEdge.entries) {
            val view = Handle(candidate)
            // A touchable overlay owns ACTION_DOWN within its rectangular window. Keep the
            // actual window small so ordinary edge controls in other apps receive their taps.
            val width = if (candidate == SideBarEdge.BOTTOM) (resources.displayMetrics.widthPixels * .28f).toInt() else dp(8)
            val height = if (candidate == SideBarEdge.BOTTOM) dp(8) else dp(112)
            val params = params(width, height, candidate)
            if (add(view, params)) handles[candidate] = view
        }
        if (handles.isEmpty()) stopSelf()
    }

    private fun expand(target: SideBarEdge) {
        val selected = SideBarButton.entries.filter { it in settings.read().buttons }
        if (selected.isEmpty()) { stopSelf(); return }
        edge = target
        removeHandles(); removePanel()
        val horizontal = edge == SideBarEdge.BOTTOM
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(22), dp(18), dp(22))
            background = SideBarAppearance.panel(edge, resources.displayMetrics.density)
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) scheduleHide()
                false
            }
        }
        val count = selected.size + 1
        val maxRows = if (horizontal) 2 else
            ((resources.displayMetrics.heightPixels / resources.displayMetrics.density * .78f) / 56f).toInt().coerceAtLeast(1)
        val columns = if (horizontal) count.coerceAtMost(4) else ((count + maxRows - 1) / maxRows).coerceAtLeast(1)
        val grid = GridLayout(this).apply { columnCount = columns; orientation = GridLayout.HORIZONTAL }
        selected.forEach { button ->
            grid.addView(button(button.glyph, button.title) {
                scheduleHide()
                perform(button)
            })
        }
        // Collapse is mandatory and always last, regardless of button preferences.
        grid.addView(button("×", "Спрятать сайдбар") { collapse() })
        row.addView(grid)
        if (add(row, params(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, edge))) { panel = row; scheduleHide() }
        else showHandles()
    }

    private fun button(glyph: String, label: String, click: () -> Unit) = TextView(this).apply {
        text = glyph
        textSize = 24f
        setTextColor(SideBarAppearance.iconColor)
        gravity = Gravity.CENTER
        contentDescription = label
        isClickable = true
        isFocusable = true
        background = SideBarAppearance.button(resources.displayMetrics.density)
        setOnClickListener { click() }
        layoutParams = GridLayout.LayoutParams().apply { width = dp(52); height = dp(52); setMargins(dp(2), dp(2), dp(2), dp(2)) }
    }

    private fun perform(button: SideBarButton) {
        try {
            when (button) {
                SideBarButton.VOLUME_UP -> if (!K4811Controls.volumeUp(this))
                    audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                SideBarButton.VOLUME_DOWN -> if (!K4811Controls.volumeDown(this))
                    audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                SideBarButton.MUTE -> if (!K4811Controls.mute(this))
                    audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                SideBarButton.PLAY_PAUSE -> {
                    audio().dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                    audio().dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                }
                SideBarButton.HOME -> if (!K4811Controls.home(this))
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                SideBarButton.BACK -> if (!K4811Controls.back(this))
                    Toast.makeText(this, "«Назад» доступно только на K4811.", Toast.LENGTH_LONG).show()
                SideBarButton.MENU -> if (!K4811Controls.menu(this))
                    Toast.makeText(this, "«Меню» доступно только на K4811.", Toast.LENGTH_LONG).show()
                SideBarButton.SLEEP -> if (K4811Controls.sleep(this)) collapse()
                    else Toast.makeText(this, "«Сон» доступен только на K4811.", Toast.LENGTH_LONG).show()
                SideBarButton.REBOOT -> if (K4811RebootActivity.open(this)) collapse()
                    else Toast.makeText(this, "Подтверждение перезагрузки недоступно на этом устройстве.", Toast.LENGTH_LONG).show()
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

    private fun params(width: Int, height: Int, target: SideBarEdge) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = when (target) {
            SideBarEdge.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
            SideBarEdge.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
            SideBarEdge.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private inner class Handle(private val target: SideBarEdge) : View(this@SideBarService) {
        private var downX = 0f
        private var downY = 0f
        init {
            contentDescription = "Провести от края для открытия панели YMPlayer 2"
            // Keep only the small touch window; the collapsed handle has no visible pixels.
            background = null
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; return true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (when (target) {
                        SideBarEdge.LEFT -> dx > dp(24) && dx > kotlin.math.abs(dy) * 1.12f
                        SideBarEdge.RIGHT -> dx < -dp(24) && -dx > kotlin.math.abs(dy) * 1.12f
                        SideBarEdge.BOTTOM -> dy < -dp(24) && -dy > kotlin.math.abs(dx) * 1.12f
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
