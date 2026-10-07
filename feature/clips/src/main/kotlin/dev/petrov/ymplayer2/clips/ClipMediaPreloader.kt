package dev.petrov.ymplayer2.clips

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl

/** Keeps at most the playing source and three seconds of the next clip in memory. */
@androidx.annotation.OptIn(UnstableApi::class)
class ClipMediaPreloader(
    context: Context,
    private val onPreloaded: (MediaItem) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var currentRank = 0
    private var current: MediaItem? = null
    @Volatile private var next: MediaItem? = null
    @Volatile private var closed = false
    private val builder = DefaultPreloadManager.Builder(context,
        TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> { rank ->
            if (rank == currentRank + 1) DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(3_000L)
            else DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        }).setLoadControl(DefaultLoadControl.Builder()
            .setPlayerTargetBufferBytes("preload", 16 * 1024 * 1024).build())
    val player: ExoPlayer = builder.buildExoPlayer()
    private val manager = builder.build().apply {
        addListener(object : PreloadManagerListener {
            override fun onCompleted(mediaItem: MediaItem) {
                if (!closed && mediaItem == next) onPreloaded(mediaItem)
            }
            override fun onError(exception: PreloadException) {
                main.post {
                    if (!closed && exception.mediaItem == next) {
                        remove(exception.mediaItem)
                        next = null // Playback falls back to a fresh source.
                    }
                }
            }
        })
    }

    fun warmNext(item: MediaItem) {
        if (closed || item == next || item == current) return
        next?.let(manager::remove)
        next = item
        manager.add(item, currentRank + 1)
        manager.setCurrentPlayingIndex(currentRank)
        manager.invalidate()
    }

    fun play(item: MediaItem, positionMs: Long = 0, playing: Boolean = true): Boolean {
        if (closed) return false
        val previous = current
        val preloaded = if (item == next) manager.getMediaSource(item) else null
        if (preloaded != null) player.setMediaSource(preloaded) else player.setMediaItem(item)
        player.seekTo(positionMs.coerceAtLeast(0))
        player.playWhenReady = playing
        player.prepare()
        current = item
        currentRank++
        if (item != next) next?.let(manager::remove)
        next = null
        manager.setCurrentPlayingIndex(currentRank)
        previous?.takeIf { it != item }?.let(manager::remove)
        return preloaded != null
    }

    fun reset() {
        if (closed) return
        manager.reset()
        current = null
        next = null
        currentRank = 0
        manager.setCurrentPlayingIndex(0)
    }

    /** Call after releasing the ExoPlayer built by this manager. */
    fun release() {
        if (closed) return
        closed = true
        manager.release()
        main.removeCallbacksAndMessages(null)
    }
}
