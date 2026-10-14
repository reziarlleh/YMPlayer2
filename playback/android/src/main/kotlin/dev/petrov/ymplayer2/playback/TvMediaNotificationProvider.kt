package dev.petrov.ymplayer2.playback

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/** Only this user-operated PendingIntent requests navigation; it never requests Play. */
const val ACTION_OPEN_MEDIA_PLAYER = "dev.petrov.ymplayer2.OPEN_MEDIA_PLAYER"

@Suppress("DEPRECATION")
internal fun mediaPlayerActivity(context: Context): PendingIntent {
    val intent = Intent(ACTION_OPEN_MEDIA_PLAYER)
        .setClassName(context.packageName, "dev.petrov.ymplayer2.MainActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    // Android 14 TV notification components can forward the click without sender options.
    // Publish the opt-in with this explicit user-operated activity entry point.
    val options = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic().apply {
        setPendingIntentBackgroundActivityStartMode(if (Build.VERSION.SDK_INT >= 36)
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        if (Build.VERSION.SDK_INT >= 35)
            setPendingIntentCreatorBackgroundActivityStartMode(if (Build.VERSION.SDK_INT >= 36)
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
    }.toBundle().apply {
        // Android 14 already reads this AOSP option; its typed setter entered the public SDK in 35.
        if (Build.VERSION.SDK_INT == 34) putInt("android.activity.pendingIntentCreatorBackgroundActivityStartMode",
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        // Android 14's TV notification sender is a privileged background service,
        // rather than a visible activity. Let Android verify its existing BAL
        // permission; this grants no permission to our app or to the sender.
        if (Build.VERSION.SDK_INT == 34) putBoolean(
            "android.pendingIntent.backgroundActivityAllowedByPermission", true)
    } else null
    // Keep the token stable: cancelling it invalidates the copy retained by the TV's
    // now-playing card even after the app notification has received its replacement.
    return PendingIntent.getActivity(context, 13, intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, options)
}

/** Keep Media3's notification and add the actions read by television notification panels. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvMediaNotificationProvider(private val context: Context,
    private val delegate: MediaNotification.Provider = DefaultMediaNotificationProvider(context),
    private val notificationCreated: (MediaSession) -> Unit = {}) : MediaNotification.Provider {
    override fun createNotification(mediaSession: MediaSession, mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory, onNotificationChangedCallback: MediaNotification.Provider.Callback): MediaNotification {
        val open = mediaSession.sessionActivity ?: mediaPlayerActivity(context)
        val stop = actionFactory.createMediaActionPendingIntent(mediaSession, Player.COMMAND_STOP)
        fun extend(original: MediaNotification): MediaNotification {
            val previous = NotificationCompat.TvExtender(original.notification)
            val notification = NotificationCompat.Builder(context, original.notification)
                // Reading an unextended notification yields flags=0 (unavailable on TV).
                // Start with the default TV-enabled extender, then retain optional TV fields.
                .extend(NotificationCompat.TvExtender()
                    .setChannelId(previous.channelId).setSuppressShowOverApps(previous.isSuppressShowOverApps)
                    .setContentIntent(open).setDeleteIntent(stop))
                .build()
            return MediaNotification(original.notificationId, notification)
        }
        // Artwork arrives later through this callback. Its replacement must retain TV actions too.
        val notification = extend(delegate.createNotification(mediaSession, mediaButtonPreferences, actionFactory) {
            onNotificationChangedCallback.onNotificationChanged(extend(it))
        })
        notificationCreated(mediaSession)
        return notification
    }
    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean =
        delegate.handleCustomCommand(session, action, extras)
    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo = delegate.notificationChannelInfo
}
