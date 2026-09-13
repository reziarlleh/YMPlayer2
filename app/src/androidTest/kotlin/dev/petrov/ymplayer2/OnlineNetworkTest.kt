@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.playback.onlineDataSourceFactory
import dev.petrov.ymplayer2.playback.WaveAudioBuffer
import dev.petrov.ymplayer2.core.Source
import dev.petrov.ymplayer2.core.Track
import dev.petrov.ymplayer2.yandex.HttpsMusicTransport
import dev.petrov.ymplayer2.yandex.YandexMusicApi
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in real HTTPS/XML/audio probe. Public preview only; never reads an account or OAuth token. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OnlineNetworkTest {
    @Test fun publicYandexXmlResolvesAndMedia3ReadsActualAudio() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("yandexLiveProbe") == "true")
        val transport = HttpsMusicTransport()
        // Public Titanik used to reproduce the owner's parser failure. No user collection is read.
        val root = JSONObject(transport.request("https://api.music.yandex.net/tracks/26219946/download-info", null, null))
        val variants = root.getJSONArray("result")
        val preview = (0 until variants.length()).map { variants.getJSONObject(it) }
            .first { it.optBoolean("preview") && it.optString("codec") == "mp3" }
        val xml = transport.request(YandexMusicApi.secureUrl(preview.getString("downloadInfoUrl")), null, null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val buffer = WaveAudioBuffer(context)
        var engine: ExoPlayer? = null
        var failure: PlaybackException? = null
        var position = 0L
        var playing = false
        try {
            // Verify the new full-file path with actual public MP3 packets, without a personal account.
            val track = Track("yandex:26219946", "Public preview", "", "", Source.YANDEX, 0, false)
            buffer.prepare("protocol-probe", track) { YandexMusicApi.buildDirectLink(xml) }
            instrumentation.runOnMainSync {
                engine = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(onlineDataSourceFactory(context) { profile, id -> checkNotNull(buffer.uri(profile, id)) })).build().apply {
                    addListener(object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failure = error } })
                    setMediaItem(MediaItem.fromUri(Uri.parse("ymplayer2://yandex/protocol-probe/yandex%3A26219946")))
                    prepare(); play()
                }
            }
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < deadline && position < 2500 && failure == null) {
                Thread.sleep(100)
                instrumentation.runOnMainSync { position = engine!!.currentPosition; playing = engine!!.isPlaying }
            }
            assertNull("Media3 error code=${failure?.errorCode}", failure)
            assertTrue("Real public audio did not advance: $position", playing && position >= 2500)
            val report = JSONObject().put("publicPreview", true).put("credentialsUsed", false)
                .put("verifiedTemporaryBuffer", true)
                .put("realHttpsAudio", true).put("positionMs", position).put("playing", playing)
                .put("androidApi", android.os.Build.VERSION.SDK_INT)
            File(context.getExternalFilesDir(null), "yandex-live-audio.json").writeText(report.toString(2))
        } finally { instrumentation.runOnMainSync { engine?.release() }; buffer.clear() }
    }
}
