package dev.petrov.ymplayer2.playback

import android.app.Notification
import android.content.Context
import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/** Experimental Android 10 OEM bridge compatibility: retain Media3's notification,
 * actions, token, artwork loading and channel, but publish it without an app group. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ClusterMediaNotificationProvider(private val context: Context) : MediaNotification.Provider {
    private val delegate = DefaultMediaNotificationProvider(context)

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val metadata = mediaSession.player.mediaMetadata
        val subText = metadata.albumTitle?.takeIf { it.isNotBlank() }
            ?: metadata.description?.takeIf { it.isNotBlank() }
        return adapt(subText, delegate.createNotification(
            mediaSession, mediaButtonPreferences, actionFactory,
            MediaNotification.Provider.Callback { changed ->
                onNotificationChangedCallback.onNotificationChanged(adapt(subText, changed))
            },
        ))
    }

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
        delegate.notificationChannelInfo

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean =
        delegate.handleCustomCommand(session, action, extras)

    private fun adapt(subText: CharSequence?, source: MediaNotification): MediaNotification {
        val notification = Notification.Builder.recoverBuilder(context, source.notification)
            .setGroup(null)
            .setSubText(subText)
            .build()
        return MediaNotification(source.notificationId, notification)
    }
}
