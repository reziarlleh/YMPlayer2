package dev.petrov.ymplayer2

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.petrov.ymplayer2.localization.trMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** User-selected export or cache move; one cancellable data-sync notification, independent of audio. */
class FileTransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph get() = application as PlayerApplication
    private var watching: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { graph.cacheLocation.cancel(); graph.audioExporter.cancel(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, trMessage("Файловые операции"), NotificationManager.IMPORTANCE_LOW))
        val first = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, first)
        if (graph.cacheLocation.state.value.moving || graph.audioExporter.state.value.running) return START_NOT_STICKY
        watching?.cancel()
        when (intent?.action) {
            MOVE -> graph.cacheLocation.move(intent.getStringExtra("destination"))
            EXPORT -> intent.getStringExtra("destination")?.let(graph.audioExporter::start)
        }
        watching = scope.launch {
            combine(graph.cacheLocation.state, graph.audioExporter.state, dev.petrov.ymplayer2.localization.AppLanguages.state) { location, export, _ -> location.moving || export.running }
                .collect { running -> if (!running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) } else manager.notify(ID, notification()) }
        }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val cache = graph.cacheLocation.state.value; val export = graph.audioExporter.state.value
        val cancel = PendingIntent.getService(this, ID, Intent(this, FileTransferService::class.java).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, ID, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = trMessage(if (cache.moving) "Перенос офлайн-кэша" else "Экспорт MP3")
        val detail = if (cache.moving) "${cache.copiedBytes / 1048576} / ${cache.totalBytes / 1048576} MB" else "${export.completed} / ${export.total} · ${export.current}"
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher).setContentTitle(title).setContentText(detail)
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(if (cache.moving) 100 else export.total, if (cache.moving && cache.totalBytes > 0) (cache.copiedBytes * 100 / cache.totalBytes).toInt() else export.completed,
                if (cache.moving) cache.totalBytes == 0L else export.total == 0)
            .addAction(Notification.Action.Builder(null, trMessage("Отмена"), cancel).build()).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { graph.cacheLocation.cancel(); graph.audioExporter.cancel() }
    override fun onDestroy() { graph.cacheLocation.cancel(); graph.audioExporter.cancel(); scope.cancel(); super.onDestroy() }
    companion object {
        const val MOVE = "dev.petrov.ymplayer2.MOVE_CACHE"
        const val EXPORT = "dev.petrov.ymplayer2.EXPORT_MP3"
        private const val CANCEL = "dev.petrov.ymplayer2.CANCEL_TRANSFER"
        private const val CHANNEL = "file-transfers"
        private const val ID = 202
    }
}
