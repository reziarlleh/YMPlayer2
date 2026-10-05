package dev.petrov.ymplayer2

import dev.petrov.ymplayer2.localization.*

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.os.IBinder
import dev.petrov.ymplayer2.core.OfflineState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** User-started download, separate from the audio foreground service. Never auto-starts on boot. */
open class OfflineSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    protected open val offline get() = (application as PlayerApplication).offline
    private var watching: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { offline.cancel(); finish(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, tr(Msg.msg_bd5f65f27353), NotificationManager.IMPORTANCE_LOW))
        val notification = notification(offline.state.value)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, notification)
        if (watching != null) return START_NOT_STICKY
        offline.sync()
        watching = scope.launch {
            combine(offline.state, AppLanguages.state) { state, _ -> state }.collect { state ->
                if (!state.running) finish()
                else if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                    manager.notify(ID, notification(state))
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { offline.cancel(); offline.report(tr(Msg.msg_ca3bbeeee26d)); finish() }
    override fun onDestroy() { if (offline.state.value.running) offline.cancel(); scope.cancel(); super.onDestroy() }
    private fun finish() { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    private fun notification(state: OfflineState): Notification {
        val open = PendingIntent.getActivity(this, 21, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 22, Intent(this, OfflineSyncService::class.java).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher).setContentTitle(tr(Msg.msg_d96f9192c384))
            .setContentText(state.message?.let(::trMessage) ?: tr(Msg.msg_e261fc0388e8)).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS).setProgress(state.total, state.checked, state.total == 0)
            .addAction(Notification.Action.Builder(null, tr(Msg.msg_3396400da0c4), cancel).build()).build()
    }
    companion object {
        private const val CHANNEL = "offline-sync"
        private const val ID = 201
        private const val CANCEL = "dev.petrov.ymplayer2.OFFLINE_CANCEL"
    }
}
