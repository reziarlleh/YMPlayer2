package dev.petrov.ymplayer2.clips

import android.content.Context
import android.graphics.Matrix
import android.view.TextureView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

/** A plain texture has no PlayerView shutter; native controls stay above the video layer. */
fun clipVideoView(context: Context, player: ExoPlayer): TextureView = TextureView(context).apply {
    var size = player.videoSize
    fun updateScreenAwake() {
        // TextureView has no PlayerView policy. Keep TV dreams off during playback/buffering;
        // pause, failure and background suspension restore Android's normal idle behaviour.
        keepScreenOn = player.playWhenReady && player.playerError == null &&
            player.playbackState in listOf(Player.STATE_BUFFERING, Player.STATE_READY)
    }
    fun fit() {
        if (width == 0 || height == 0 || size.width == 0 || size.height == 0) return
        val videoRatio = size.width * size.pixelWidthHeightRatio / size.height
        val viewRatio = width.toFloat() / height
        val scaleX = if (viewRatio > videoRatio) videoRatio / viewRatio else 1f
        val scaleY = if (viewRatio < videoRatio) viewRatio / videoRatio else 1f
        setTransform(Matrix().apply { setScale(scaleX, scaleY, width / 2f, height / 2f) })
    }
    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit(); post { fit() } }
    player.addListener(object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { updateScreenAwake() }
        override fun onVideoSizeChanged(videoSize: VideoSize) { size = videoSize; fit() }
        override fun onRenderedFirstFrame() { size = player.videoSize; fit() }
    })
    updateScreenAwake()
    player.setVideoTextureView(this)
}
