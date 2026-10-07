package dev.petrov.ymplayer2

import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import dev.petrov.ymplayer2.clips.*
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.playback.PlaybackOutput
import dev.petrov.ymplayer2.yandex.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Seed and verify run in different processes, with force-stop/reboot controlled by the host script. */
@RunWith(AndroidJUnit4::class)
class SessionRecoveryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val app get() = context.applicationContext as PlayerApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrument.runOnMainSync { result.set(block()) }
        return result.get()
    }
    private fun waitFor(label: String, timeout: Long = 25_000, test: () -> Boolean) {
        val until = System.currentTimeMillis() + timeout
        while (!test() && System.currentTimeMillis() < until) Thread.sleep(40)
        assertTrue(label, test())
    }
    private fun fixtures() = context.contentResolver.call(Uri.parse("content://dev.petrov.ymplayer2.test.control"), "fixtures", null, null)
    private fun prepareMusic() {
        main { app.playback.connect(); app.playback.switchProfile("owner"); app.playback.stop() }
        waitFor("Library/engine") { app.playback.state.value.connected && app.library.state.value.ready && !app.library.state.value.scanning }
        runBlocking { app.library.state.value.roots.forEach { app.library.forgetFolder(it.uri) }; fixtures(); app.library.addFolder(TestMusicProvider.tree.toString(), Source.LOCAL) }
        waitFor("Music fixtures") { app.library.testTracks.size == 2 }
    }
    private fun clipController(): ClipWaveController {
        val file = File(context.cacheDir, "resume-fixture.mp4")
        instrument.context.assets.open("clip-transport/fixture.mp4").use { input -> file.outputStream().use(input::copyTo) }
        val accounts = AccountAuth(DemoCatalog().profiles, object : DeviceAuthApi {
            override val configured = true
            override suspend fun requestCode(profileId: String) = error("unused")
            override suspend fun poll(code: DeviceChallenge) = error("unused")
            override suspend fun account(credentials: OAuthCredentials) = error("unused")
        }, object : AccountStore {
            override suspend fun read(profileId: String) = AccountSession(YandexAccount("fixture", "Fixture"), OAuthCredentials("fixture", null, null))
            override suspend fun write(profileId: String, session: AccountSession?) = Unit
        }, scope)
        main { accounts.activate("owner") }
        waitFor("Fixture auth") { accounts.state.value.phase == AuthPhase.SIGNED_IN }
        val transport = object : ClipTransport {
            override suspend fun get(url: String, token: String) = """{"content":{"streams":[{"stream_type":"mp4","url":"https://strm.yandex.ru/fresh.mp4"}]}}"""
            override suspend fun post(url: String, token: String, body: JSONObject) = when {
                url.endsWith("/new") -> """{"result":{"sessionId":"resume-session","batchId":"batch","list":[{"clipId":"resume-clip","playerId":"resume-player","title":"Resume clip","duration":12}]}}"""
                url.endsWith("/next") -> """{"result":{"list":[]}}"""
                else -> "{}"
            }
        }
        return main {
            val base = DefaultMediaSourceFactory(context)
            val sources = object : MediaSource.Factory by base {
                override fun createMediaSource(item: MediaItem) = base.createMediaSource(item.buildUpon().setUri(Uri.fromFile(file)).build())
            }
            val player = ExoPlayer.Builder(context).setMediaSourceFactory(sources).build().apply { volume = 0f }
            ClipWaveController(YandexClipApi(accounts, transport), "owner", player, scope, saveCheckpoint = {
                app.clipCheckpoints.write("owner", it)
                app.launchState.write("owner", PlaybackOutput.CLIPS, it.playing)
            })
        }
    }

    @Test fun acrossProcessOrBoot() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("recoveryPhase")
        assumeTrue("Run through tools/check-session-recovery.ps1", phase in listOf("seed", "verify"))
        val output = PlaybackOutput.valueOf(args.getString("output")!!)
        val playing = args.getString("playing") == "true"
        if (phase == "seed") {
            prepareMusic()
            when (output) {
                PlaybackOutput.MUSIC -> {
                    val id = app.library.testTracks.first().id
                    main { app.playback.playQueue(listOf(id)); app.playback.seek(4) }
                    waitFor("Decoded music") { app.playback.state.value.playing && app.playback.state.value.positionSeconds >= 4 }
                    if (!playing) main { app.playback.toggle() }
                    main { app.playback.saveForExit() }
                    context.getSharedPreferences("recovery-test", 0).edit().putString("id", id).commit()
                }
                PlaybackOutput.RADIO -> {
                    main { app.accounts.activate("owner"); app.radioCatalog.tab(RadioTab.ALL); app.radioCatalog.search("Европа") }
                    val station = runBlocking { YandexRadioApi(app.accounts).station("europa-plus", "moscow") }
                    main { app.radio.switchProfile("owner"); app.radio.play(station) }
                    waitFor("Live radio", 45_000) { app.radio.state.value.playing }
                    if (!playing) main { app.radio.stop() }
                    main { app.radio.saveForExit() }
                    app.radioNavigation.edit().commit()
                }
                PlaybackOutput.CLIPS -> {
                    val clips = clipController()
                    main { clips.start() }
                    waitFor("Decoded video") { clips.state.value.playing }
                    main { clips.player.seekTo(4_000); if (!playing) clips.pause(); clips.checkpoint(true) }
                    main { app.clipCheckpoints.flush() }
                }
            }
            app.navigation.edit().putString("route", if (output == PlaybackOutput.CLIPS) "clips" else "search").commit()
            main { app.launchState.flush() }
            assertEquals(output, app.launchState.read("owner").output)
            assertEquals(playing, app.launchState.read("owner").playing)
        } else {
            assertEquals(output, app.launchState.read("owner").output)
            assertEquals(playing, app.launchState.read("owner").playing)
            assertEquals(if (output == PlaybackOutput.CLIPS) "clips" else "search", app.navigation.getString("route", ""))
            fixtures()
            if (output == PlaybackOutput.CLIPS) {
                val saved = app.clipCheckpoints.read("owner")!!
                assertEquals("resume-clip", saved.clip.id)
                assertEquals(playing, saved.playing)
                assertTrue(saved.positionMs >= 4_000)
                assertNull(saved.clip.previewUrl)
                assertTrue(main { app.restoreLaunch() })
                val clips = clipController()
                main { clips.start(saved) }
                waitFor("Restored video decoder") { main { clips.player.playbackState == Player.STATE_READY && clips.player.isPlaying == playing } }
                main {
                    assertEquals("resume-clip", clips.player.currentMediaItem?.mediaId)
                    assertTrue(clips.player.currentPosition >= 4_000)
                    assertEquals(playing, clips.player.playWhenReady)
                    assertFalse(app.playback.state.value.playing)
                    assertFalse(app.radio.state.value.ownsOutput)
                    clips.close()
                }
            } else {
                if (output == PlaybackOutput.MUSIC) {
                    // A metadata-only service start must stay silent and retain foreground resume intent.
                    main { app.playback.connect() }
                    waitFor("Silent service checkpoint") { app.playback.state.value.current != null }
                    assertFalse(app.playback.state.value.playing)
                    assertEquals(playing, app.launchState.read("owner").playing)
                }
                val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                compose.onNodeWithTag("nav_search").assertIsSelected()
                if (output == PlaybackOutput.MUSIC) {
                    waitFor("Restored music") { app.playback.state.value.current != null && app.playback.state.value.playing == playing }
                    assertEquals(context.getSharedPreferences("recovery-test", 0).getString("id", ""), app.playback.state.value.current?.id)
                    assertTrue(app.playback.state.value.positionSeconds >= 4)
                    assertFalse(app.radio.state.value.ownsOutput)
                } else {
                    waitFor("Restored station", 45_000) { app.radio.state.value.station != null && app.radio.state.value.playing == playing }
                    assertEquals("europa-plus", app.radio.state.value.station?.slug)
                    assertTrue(app.radio.state.value.ownsOutput)
                    assertFalse(app.playback.state.value.playing)
                    assertEquals(RadioTab.ALL, app.radioCatalog.state.value.tab)
                    assertEquals("Европа", app.radioCatalog.state.value.query)
                }
                main { activity.finish() }
            }
        }
    }

    @Test fun musicPlayingSurvivesOrdinaryExit() = ordinaryExit(false, true)
    @Test fun musicPausedSurvivesOrdinaryExit() = ordinaryExit(false, false)
    @Test fun radioPlayingSurvivesOrdinaryExit() = ordinaryExit(true, true)
    @Test fun radioStoppedSurvivesOrdinaryExit() = ordinaryExit(true, false)
    @Test fun musicResumesAfterServiceDestructionWithoutProcessDeath() = ordinaryExit(false, true, destroyService = true)
    @Test fun radioResumesAfterServiceDestructionWithoutProcessDeath() = ordinaryExit(true, true, destroyService = true)
    @Test fun existingActivityResumesMusicAfterServiceDiesInBackground() = ordinaryExit(false, true, destroyService = true, existingActivity = true)
    private fun ordinaryExit(radio: Boolean, playing: Boolean, destroyService: Boolean = false, existingActivity: Boolean = false) {
        prepareMusic()
        if (radio) {
            val station = runBlocking { YandexRadioApi(app.accounts).station("europa-plus", "moscow") }
            main { app.radio.switchProfile("owner"); app.radio.play(station) }
            waitFor("Live station", 45_000) { app.radio.state.value.playing }
            if (!playing) main { app.radio.stop() }
        } else {
            main { app.playback.playQueue(listOf(app.library.testTracks.first().id)); app.playback.seek(4) }
            waitFor("Music playing") { app.playback.state.value.playing }
            if (!playing) main { app.playback.toggle() }
        }
        app.navigation.edit().putString("route", "search").commit()
        val first = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        main { if (existingActivity) first.moveTaskToBack(true) else first.finish() }
        waitFor("Exit") { if (existingActivity) !(first as MainActivity).lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) else first.isDestroyed }
        if (destroyService) {
            assertTrue(context.stopService(Intent(context, dev.petrov.ymplayer2.playback.AudioService::class.java)))
            waitFor("Service destroyed") { !app.playback.state.value.connected }
        }
        val second = if (existingActivity) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            waitFor("Existing activity returned") { (first as MainActivity).lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
            first
        } else instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        compose.onNodeWithTag("nav_search").assertIsSelected()
        waitFor("Reopen state") { if (radio) app.radio.state.value.playing == playing else app.playback.state.value.playing == playing }
        assertEquals("search", app.navigation.getString("route", ""))
        if (radio) assertEquals("europa-plus", app.radio.state.value.station?.slug)
        else assertTrue(app.playback.state.value.positionSeconds >= 4)
        main { second.finish(); app.radio.release(); app.playback.stop() }
    }

    @Test fun musicPeriodicallySavesProgressWithoutExitCallback() {
        prepareMusic()
        main { app.playback.playQueue(listOf(app.library.testTracks.first().id)) }
        waitFor("Audible progression") { app.playback.state.value.positionSeconds >= 3 }
        waitFor("Periodic disk checkpoint") {
            JSONObject(context.getSharedPreferences("playback", 0).getString("queue:owner", "{}")!!).optInt("position") >= 3
        }
        assertTrue(app.launchState.read("owner").playing)
        main { app.playback.stop() }
    }

    @Test fun resetFixtureState() {
        val enabled = InstrumentationRegistry.getArguments().getString("resetRecovery") == "true"
        assumeTrue("Explicit fixture cleanup only", enabled)
        main { app.radio.release(); app.playback.stop() }
        app.navigation.edit().clear().putString("route", "player").commit()
        app.radioNavigation.edit().clear().commit()
        context.getSharedPreferences("clip-checkpoints", 0).edit().clear().commit()
        app.launchState.write("owner", PlaybackOutput.MUSIC, false)
        app.launchState.flush()
    }

    @Test fun switchingProfileClearsItsOldResumeOwner() {
        prepareMusic()
        app.launchState.write("guest", PlaybackOutput.RADIO, true)
        main { app.playback.switchProfile("guest") }
        waitFor("Selected profile") { app.playback.state.value.profileId == "guest" }
        assertEquals(PlaybackOutput.MUSIC, app.launchState.read("guest").output)
        assertFalse("A profile change restores on pause, rather than resuming its old output", app.launchState.read("guest").playing)
        main { app.playback.switchProfile("owner") }
    }

    @Test fun videoBackgroundPausePreservesIntentButExplicitPauseDoesNot() {
        val clips = clipController()
        try {
            main { clips.start() }
            waitFor("Decoded clip") { clips.state.value.playing }
            main { clips.player.seekTo(3_000); clips.suspendForBackground() }
            assertTrue(app.clipCheckpoints.read("owner")!!.playing)
            assertTrue(app.clipCheckpoints.read("owner")!!.positionMs >= 3_000)
            main { clips.returnFromBackground() }
            waitFor("Resume from background") { clips.state.value.playing }
            main { clips.pause(); clips.suspendForBackground(); clips.returnFromBackground() }
            assertFalse(app.clipCheckpoints.read("owner")!!.playing)
            assertFalse(main { clips.player.playWhenReady })
        } finally { main { clips.close() }; scope.cancel() }
    }

    @Test fun previewOnlyClipRestoresPositionAndPause() {
        val saved = ClipCheckpoint(YandexClip("preview", "Preview", "Artist", "", null,
            "https://strm.yandex.ru/preview.mp4", 12, emptyList(), "batch"), "resume-session", 3_000, false)
        app.clipCheckpoints.write("guest", saved)
        app.clipCheckpoints.flush()
        val restored = app.clipCheckpoints.read("guest")!!
        assertEquals(saved.clip.previewUrl, restored.clip.previewUrl)
        val clips = clipController()
        try {
            main { clips.start(restored) }
            waitFor("Preview decoder") { main { clips.player.playbackState == Player.STATE_READY } }
            main {
                assertEquals("preview", clips.player.currentMediaItem?.mediaId)
                assertTrue(clips.state.value.preview)
                assertTrue(clips.player.currentPosition >= 3_000)
                assertFalse(clips.player.playWhenReady)
            }
        } finally { main { clips.close() }; scope.cancel() }
    }
}
