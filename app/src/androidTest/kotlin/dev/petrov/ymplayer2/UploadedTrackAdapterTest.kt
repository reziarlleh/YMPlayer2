@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.yandex.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Protocol regression fixtures, not a claim of access to a reporter's private uploads. */
@RunWith(AndroidJUnit4::class)
class UploadedTrackAdapterTest {
    private val upload = "9a9706da-ea99-4c4c-9afb-e50ae7e2bcaa"
    private val uploaded get() = """{"id":"$upload","title":"Uploaded song","state":"playable","artists":[{"name":"Own artist"}],"albums":[],"metaData":{"album":"Own album","genre":"Rock"},"durationMs":61000}"""
    private suspend fun auth(scope: CoroutineScope): AccountAuth {
        val session = AccountSession(YandexAccount("123", "Fixture"), OAuthCredentials("fixture-token", null, null))
        return AccountAuth(DemoCatalog().profiles, object : DeviceAuthApi {
            override val configured = true
            override suspend fun requestCode(profileId: String): DeviceChallenge = error("unused")
            override suspend fun poll(code: DeviceChallenge): TokenPoll = error("unused")
            override suspend fun account(credentials: OAuthCredentials) = session.account!!
        }, object : AccountStore {
            override suspend fun read(profileId: String) = session
            override suspend fun write(profileId: String, session: AccountSession?) = Unit
        }, scope).also { it.activate("owner"); yield() }
    }
    @Test fun embeddedAndReferencedUploadsMatchEditorAndResolvePlayableStreams() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val api = YandexMusicApi(auth(this), MusicTransport { url, token, form ->
            calls += url
            when {
                url.endsWith("/tracks") -> {
                    assertEquals(listOf("with-positions" to "true", "track-ids" to upload), form)
                    """{"result":[$uploaded]}"""
                }
                url.endsWith("/tracks/$upload/download-info") -> {
                    assertEquals("fixture-token", token)
                    """{"result":[{"codec":"mp3","bitrateInKbps":192,"downloadInfoUrl":"https://storage.yandex.net/info"}]}"""
                }
                url == "https://storage.yandex.net/info" -> {
                    assertNull("OAuth stays on the API origin", token)
                    "<download-info><host>storage.yandex.net</host><path>/upload.mp3</path><ts>abc123</ts><s>salt</s></download-info>"
                }
                else -> """{"result":{"kind":77,"owner":{"uid":123},"title":"Uploads","revision":1,"trackCount":3,"tracks":[{"id":"$upload","track":$uploaded},{"id":"42","track":{"id":42,"title":"Catalog","albums":[{"id":7}]}},{"id":"$upload"}]}}"""
            }
        })
        val result = api.page("owner", MusicRequest(entity = MusicEntity("77", "Uploads", MusicKind.PLAYLISTS, "123")), 0)
        assertEquals(listOf("yandex:$upload", "yandex:42:7", "yandex:$upload"), result.entries.map { it.id })
        val track = result.entries.first().track!!
        assertTrue(track.available); assertEquals("Own artist", track.artist); assertTrue(track.artists.isEmpty())
        assertEquals("Own album", track.album); assertEquals("Rock", track.genre)
        val editor = YandexPlaylistApi(api).load(PlaylistOwner("owner", "123"), CloudPlaylist("77", "123", "Uploads", 3))
        assertEquals(result.entries.map { it.id.removePrefix("yandex:").substringBefore(':') }, editor.tracks.map { it.id })
        assertTrue(api.stream("owner", track.id).endsWith("/abc123/upload.mp3"))
        assertTrue(calls.any { it.endsWith("/tracks/$upload/download-info") })
    }
    @Test fun uploadsDoNotBreakLikedSnapshotAndCurrentLikeState() = runBlocking<Unit> {
        val api = YandexMusicApi(auth(this), MusicTransport { url, _, form -> when {
            url.contains("/dislikes/") -> """{"result":{"library":{"tracks":[]}}}"""
            url.endsWith("/add-multiple") -> {
                assertEquals(listOf("track-ids" to upload), form); """{"result":{"revision":3}}"""
            }
            url.endsWith("/tracks") -> """{"result":[$uploaded,{"id":42,"title":"Catalog"}]}"""
            else -> """{"result":{"library":{"tracks":[{"id":"$upload"},{"id":42}]}}}"""
        } })
        val snapshot = YandexLikedMusicApi(api).snapshot("owner")
        assertEquals(setOf(upload, "42"), snapshot.keys)
        assertEquals(listOf("yandex:$upload", "yandex:42"), snapshot.tracks.map { it.id })
        assertEquals(setOf(upload, "42"), YandexTasteApi(api).taste("owner", TasteKind.TRACK).liked)
        YandexTasteApi(api).react("owner", TasteTarget(TasteKind.TRACK, upload, "Uploaded song"), TasteAction.LIKE)
    }
    @Test fun opaqueIdsCannotInjectPathsOrQueriesIntoStreamRequests() = runBlocking<Unit> {
        var requests = 0
        val api = YandexMusicApi(auth(this), MusicTransport { _, _, _ -> requests++; error("unexpected") })
        for (id in listOf("", "null", "../x", "abc/def", "abc?x=1", "abc#x", "abc%2Fdef", "abc:bad", "abc:7:8", "a".repeat(257))) {
            assertTrue(id, runCatching { api.stream("owner", "yandex:$id") }.isFailure)
        }
        assertEquals(0, requests)
    }
    @Test fun filenameFallbackAndUnavailableUploadStayVisibleWithoutFalsePlayability() = runBlocking<Unit> {
        val api = YandexMusicApi(auth(this), MusicTransport { _, _, _ ->
            """{"result":{"tracks":[{"track":{"id":"$upload","filename":"My song.mp3","state":"processing"}},{"track":{"id":42,"title":"Restricted","available":false}}]}}"""
        })
        val result = api.page("owner", MusicRequest(entity = MusicEntity("77", "Uploads", MusicKind.PLAYLISTS, "123")), 0)
        assertEquals(listOf("My song.mp3", "Restricted"), result.entries.map { it.title })
        assertTrue(result.entries.all { !it.track!!.available })
    }
    @Test fun uploadedIdSurvivesPermanentCacheReloadAndRealAudioPlayback() = runBlocking<Unit> {
        val instrument = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val owner = OfflineOwner("uploaded-cache-fixture", "no-real-account")
        val source = java.io.File(context.cacheDir, "uploaded-cache-fixture.mp4")
        instrument.context.assets.open("clip-transport/fixture.mp4").use { input -> source.outputStream().use(input::copyTo) }
        val extractor = android.media.MediaExtractor()
        val duration: Int
        val mediaDetails: String
        try {
            extractor.setDataSource(source.absolutePath)
            val index = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            duration = (format.getLong(android.media.MediaFormat.KEY_DURATION) / 1_000_000).toInt()
            mediaDetails = "audio=$format firstSample=${extractor.sampleTime} bytes=${source.length()}"
        } finally { extractor.release() }
        val track = Track("yandex:$upload", "Uploaded song", "Own artist", "Own album", Source.YANDEX, duration, false)
        val store = dev.petrov.ymplayer2.offline.LikedFileStore(context)
        val buffer = dev.petrov.ymplayer2.playback.WaveAudioBuffer(context)
        var player: androidx.media3.exoplayer.ExoPlayer? = null
        try {
            store.clear(owner)
            val result = store.sync(owner, track, { android.net.Uri.fromFile(source).toString() }, { true }, {})
            assertFalse(mediaDetails, result.audioFailed); assertNotNull(result.track)
            buffer.prepare("uploaded-cache-fixture", track) { android.net.Uri.fromFile(source).toString() }
            assertNotNull("Temporary audio accepts the same priming samples", buffer.uri("uploaded-cache-fixture", track.id))
            val freshStore = dev.petrov.ymplayer2.offline.LikedFileStore(context)
            assertEquals(track.id, freshStore.catalog(owner).single().id)
            assertEquals(track.id, freshStore.load(owner).single().id)
            freshStore.retain(owner, setOf(upload), { true })
            val uri = freshStore.audio(owner, track.id)
            assertNotNull(uri)
            instrument.runOnMainSync {
                player = androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                    volume = 0f
                    setMediaItem(androidx.media3.common.MediaItem.fromUri(uri!!)); prepare(); play()
                }
            }
            var played = false
            withTimeout(15000) {
                while (!played) {
                    delay(50)
                    instrument.runOnMainSync {
                        assertNull(player!!.playerError)
                        played = player!!.isPlaying && player!!.currentPosition > 500
                    }
                }
            }
            freshStore.retain(owner, emptySet(), { true })
            assertNull(freshStore.audio(owner, track.id)); assertTrue(freshStore.catalog(owner).isEmpty())
            val invalid = java.io.File(context.cacheDir, "uploaded-invalid.mp4")
            try {
                invalid.writeBytes(source.readBytes().take(256).toByteArray())
                val badTrack = track.copy(id = "yandex:invalid-upload")
                assertTrue(store.sync(owner, badTrack, { android.net.Uri.fromFile(invalid).toString() }, { true }, {}).audioFailed)
                assertTrue(runCatching { buffer.prepare("uploaded-cache-fixture", badTrack) { android.net.Uri.fromFile(invalid).toString() } }.isFailure)
                assertNull(buffer.uri("uploaded-cache-fixture", badTrack.id))
            } finally { invalid.delete() }
        } finally { instrument.runOnMainSync { player?.release() }; buffer.clear(); store.clear(owner); source.delete() }
    }
}
