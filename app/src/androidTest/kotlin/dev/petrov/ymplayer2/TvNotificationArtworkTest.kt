package dev.petrov.ymplayer2

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.playback.TvMediaNotificationProvider
import com.google.common.collect.ImmutableList
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvNotificationArtworkTest {
    @Test fun deferredArtworkPreservesTvActionsMediaTokenAndGenericActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            val player = ExoPlayer.Builder(context).build()
            val open = PendingIntent.getActivity(context, 22, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getBroadcast(context, 23, Intent("fixture-stop").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
            val session = MediaSession.Builder(context, player).setId("tv-artwork-test").setSessionActivity(open).build()
            try {
                lateinit var late: MediaNotification.Provider.Callback
                val base = NotificationCompat.Builder(context, "tv-artwork-fixture")
                    .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Fixture")
                    .setContentIntent(open).setDeleteIntent(stop)
                    .setStyle(MediaStyleNotificationHelper.MediaStyle(session)).build()
                val delegate = object : MediaNotification.Provider {
                    override fun createNotification(mediaSession: MediaSession, preferences: ImmutableList<CommandButton>,
                        actionFactory: MediaNotification.ActionFactory, callback: MediaNotification.Provider.Callback): MediaNotification {
                        late = callback; return MediaNotification(1001, base)
                    }
                    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle) = false
                    override fun getNotificationChannelInfo() = MediaNotification.Provider.NotificationChannelInfo("tv-artwork-fixture", "Fixture")
                }
                val actions = Proxy.newProxyInstance(MediaNotification.ActionFactory::class.java.classLoader,
                    arrayOf(MediaNotification.ActionFactory::class.java)) { _, method, _ ->
                    check(method.name == "createMediaActionPendingIntent"); stop
                } as MediaNotification.ActionFactory
                val updates = mutableListOf<MediaNotification>()
                val first = TvMediaNotificationProvider(context, delegate).createNotification(session, ImmutableList.of(), actions) { updates += it }
                assertEquals(open, NotificationCompat.TvExtender(first.notification).contentIntent)
                assertTrue(NotificationCompat.TvExtender(first.notification).isAvailableOnTv)
                assertEquals(stop, NotificationCompat.TvExtender(first.notification).deleteIntent)
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                late.onNotificationChanged(MediaNotification(1001, NotificationCompat.Builder(context, base).setLargeIcon(bitmap).build()))
                val updated = updates.single()
                assertEquals(1001, updated.notificationId)
                assertNotNull(updated.notification.getLargeIcon())
                assertEquals(open, NotificationCompat.TvExtender(updated.notification).contentIntent)
                assertTrue(NotificationCompat.TvExtender(updated.notification).isAvailableOnTv)
                assertEquals(stop, NotificationCompat.TvExtender(updated.notification).deleteIntent)
                assertEquals(base.contentIntent, updated.notification.contentIntent)
                assertEquals(base.deleteIntent, updated.notification.deleteIntent)
                assertEquals(base.extras.get("android.mediaSession"), updated.notification.extras.get("android.mediaSession"))
                assertNotNull("MediaSession token must survive decoration", updated.notification.extras.get("android.mediaSession"))
            } finally { session.release(); player.release() }
        }
    }
}
