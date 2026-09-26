package dev.petrov.ymplayer2

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.yandex.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class ClipProtocolTest {
    private suspend fun auth(scope: CoroutineScope) = AccountAuth(DemoCatalog().profiles, object : DeviceAuthApi {
        override val configured = true
        override suspend fun requestCode(profileId: String) = error("unused")
        override suspend fun poll(code: DeviceChallenge) = error("unused")
        override suspend fun account(credentials: OAuthCredentials) = error("unused")
    }, object : AccountStore {
        override suspend fun read(profileId: String) = AccountSession(YandexAccount("123", "Fixture"), OAuthCredentials("fixture", null, null))
        override suspend fun write(profileId: String, session: AccountSession?) = Unit
    }, scope).also { it.activate("owner"); yield() }

    private class FakeTransport : ClipTransport {
        val calls = mutableListOf<Pair<String, JSONObject?>>()
        var reply: (String) -> String = { error("Unexpected URL $it") }
        override suspend fun get(url: String, token: String): String {
            assertEquals("fixture", token); calls += url to null; return reply(url)
        }
        override suspend fun post(url: String, token: String, body: JSONObject): String {
            assertEquals("fixture", token); calls += url to body; return reply(url)
        }
    }

    @Test fun sessionQueueAndFeedbackFollowWorkingOneXWireFormat() = runBlocking {
        val transport = FakeTransport().apply { reply = { url -> when {
            url.endsWith("/new") -> """{"result":{"sessionId":"s-1","batchId":"b-1","list":[{"type":"track","data":{"clipId":"ignored"}},{"type":"clip","data":{"clipId":"c-1","playerId":"p-1","title":"One","duration":12,"artists":[{"name":"Artist"}],"trackIds":["7:8"],"thumbnail":"avatars.yandex.net/cover/%%"}}]}}"""
            url.endsWith("/next") -> """{"result":{"batchId":"b-2","list":[{"clipId":"c-2","previewUrl":"https://strm.yandex.ru/preview.mp4"}]}}"""
            url.endsWith("/feedback") -> ""
            else -> error(url)
        } } }
        val api = YandexClipApi(auth(this), transport)
        val session = api.start("owner")
        assertEquals("s-1", session.id)
        val clip = session.batch.clips.single()
        assertEquals("c-1", clip.id)
        assertEquals("Artist", clip.artist)
        assertEquals(listOf("7:8"), clip.trackIds)
        assertEquals("https://avatars.yandex.net/cover/1280x720", clip.thumbnailUrl)
        assertEquals("clip", transport.calls[0].second!!.getJSONArray("supportedTypes").getString(0))
        val next = api.next("owner", session.id, listOf("c-1", "c-1", ""))
        assertEquals("c-2", next.clips.single().id)
        val queue = transport.calls[1].second!!.getJSONArray("queue")
        assertEquals(1, queue.length())
        assertEquals("clip", queue.getJSONObject(0).getString("type"))
        assertEquals("c-1", queue.getJSONObject(0).getString("id"))
        api.feedback("owner", session.id, clip, ClipFeedback.SKIPPED, 4.5f)
        val feedback = transport.calls[2].second!!
        assertEquals("b-1", feedback.getString("batchId"))
        assertEquals("playableItemSkip", feedback.getJSONObject("event").getString("type"))
        assertEquals("c-1", feedback.getJSONObject("event").getJSONObject("playable").getString("id"))
        assertEquals(4.5, feedback.getJSONObject("event").getDouble("totalPlayedSeconds"), 0.01)
    }

    @Test fun streamPrefersUnprotectedHlsThenDashAndFallsBackToPreview() = runBlocking {
        val transport = FakeTransport().apply { reply = { url -> when {
            "/player/" in url -> """{"content":{"actual_episode":{"streams":[{"stream_type":"hls","url":"https://strm.yandex.ru/drm.m3u8","drmConfig":{"key":"x"}},{"stream_type":"dash","url":"https://strm.yandex.ru/free.mpd"}]},"streams":[{"stream_type":"hls","url":"https://strm.yandex.ru/other.m3u8"}]}}"""
            else -> error(url)
        } } }
        val api = YandexClipApi(auth(this), transport)
        val clip = YandexClip("c", "One", "Artist", "player 1", null, "https://strm.yandex.ru/preview.mp4", 4, emptyList(), "b")
        assertEquals("https://strm.yandex.ru/free.mpd", api.stream("owner", clip).url)
        assertTrue(transport.calls.single().first.contains("player%201.json?service=ya-music"))
        transport.reply = { """{"content":{"streams":[{"stream_type":"hls","url":"https://strm.yandex.ru/drm.m3u8","drmConfig":{"key":"x"}}]}}""" }
        assertEquals(true, api.stream("owner", clip).preview)
        transport.reply = { """{"content":{"streams":[{"stream_type":"hls","url":"https://evil.example/video.m3u8"}]}}""" }
        assertTrue(api.stream("owner", clip).preview)
    }

    @Test fun tokenIsRestrictedToExactClipOriginsAndRedirectsAreDisabled() = runBlocking {
        val addresses = mutableListOf<String>()
        val transport = HttpsClipTransport { address -> object : HttpsURLConnection(URL(address)) {
            val body = ByteArrayOutputStream()
            override fun getOutputStream() = body
            override fun getInputStream() = """{"result":{}}""".byteInputStream()
            override fun getResponseCode(): Int {
                assertEquals("OAuth fixture", getRequestProperty("Authorization"))
                assertFalse(instanceFollowRedirects)
                assertEquals("YandexMusicAndroidTVKPHD/2.256.1", getRequestProperty("X-Yandex-Music-Client"))
                addresses += address
                return 200
            }
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun connect() = Unit
            override fun getCipherSuite() = "fixture"
            override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
            override fun getServerCertificates(): Array<java.security.cert.Certificate> = emptyArray()
        } }
        transport.get("https://frontend.vh.yandex.ru/player/1.json", "fixture")
        transport.post("https://api.music.yandex.net/rotor/combined/session/new", "fixture", JSONObject().put("queue", org.json.JSONArray()))
        assertEquals(2, addresses.size)
        for (bad in listOf("https://api.music.yandex.net.evil.example/x", "http://api.music.yandex.net/x",
            "https://api.music.yandex.net:444/x", "https://evil.example/x")) {
            try { transport.get(bad, "fixture"); fail("Accepted $bad") } catch (_: MusicException) { }
        }
        assertEquals(2, addresses.size)
    }
}
