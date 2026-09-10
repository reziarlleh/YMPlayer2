package dev.petrov.ymplayer2.library

import android.content.*
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import dev.petrov.ymplayer2.core.LocalLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Signals trigger a read-only rescan; a mount event never starts playback. */
internal fun monitorStorage(context: Context, scope: CoroutineScope, library: LocalLibrary) {
    val app = context.applicationContext
    val changes = Channel<Unit>(Channel.CONFLATED)
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { changes.trySend(Unit) }
    }
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { changes.trySend(Unit) }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED)
        addAction(Intent.ACTION_MEDIA_EJECT); addAction(Intent.ACTION_MEDIA_REMOVED); addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
        addDataScheme("file")
    }
    if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    else app.registerReceiver(receiver, filter)
    scope.launch {
        try {
            launch {
                library.state.map { state -> state.roots.map { it.uri } }.distinctUntilChanged().collect { roots ->
                    app.contentResolver.unregisterContentObserver(observer)
                    roots.forEach { raw -> runCatching {
                        val tree = Uri.parse(raw)
                        app.contentResolver.registerContentObserver(DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), true, observer)
                    } }
                }
            }
            for (signal in changes) {
                delay(350)
                while (changes.tryReceive().isSuccess) { /* Coalesce a burst from one mount. */ }
                library.refresh()
            }
        } finally {
            app.unregisterReceiver(receiver)
            app.contentResolver.unregisterContentObserver(observer)
            changes.close()
        }
    }
}
