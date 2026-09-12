package dev.petrov.ymplayer2

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.yandex.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class OnlineAdapterTest {
    private suspend fun auth(scope: CoroutineScope): AccountAuth {
        val session = AccountSession(YandexAccount("123", "Fixture"), OAuthCredentials("fixture-token", null, null))
        return AccountAuth(DemoCatalog().profiles, object : DeviceAuthApi {
            override val configured = true
            override suspend fun requestCode(profileId: String): DeviceChallenge = error("not used")
            override suspend fun poll(code: DeviceChallenge): TokenPoll = error("not used")
            override suspend fun account(credentials: OAuthCredentials) = session.account!!
        }, object : AccountStore {
            override suspend fun read(profileId: String) = session
            override suspend fun write(profileId: String, session: AccountSession?) = Unit
        }, scope).also { it.activate("owner"); yield() }
    }
    private fun track(id: Int, available: Boolean = true) = """{"id":$id,"title":"Track $id","durationMs":61000,"available":$available,"artists":[{"id":5,"name":"Artist"}],"albums":[{"id":7,"title":"Album","coverUri":"avatars.yandex.net/get-music/fixture/%%"}]}"""

    @Test fun pagedSearchUsesSingularTypeAndPreservesUnavailableItems() = runBlocking {
        val auth = auth(this)
        val api = YandexMusicApi(auth, MusicTransport { url, token, form ->
            assertTrue(url.contains("type=track&page=1")); assertTrue(url.contains("text=hello+%26+world"))
            assertEquals("fixture-token", token); assertNull(form)
            """{"result":{"tracks":{"total":80,"perPage":20,"results":[${track(1)},${track(2, false)}]}}}"""
        })
        val result = api.page("owner", MusicRequest("hello & world"), 1)
        assertEquals(2, result.nextPage); assertEquals(listOf("yandex:1:7", "yandex:2:7"), result.entries.map { it.id })
        assertFalse(result.entries.last().track!!.available)
        assertTrue(result.entries.all { it.track!!.source == Source.YANDEX && !it.track!!.offline && it.track!!.uri == null })
        assertEquals("https://avatars.yandex.net/get-music/fixture/400x400", result.entries.first().track!!.artworkUri)
    }
    @Test fun entitySearchMapsPlaylistOwnerAndAlbumDetailsKeepDiscOrder() = runBlocking {
        val api = YandexMusicApi(auth(this), MusicTransport { url, _, _ -> when {
            url.contains("type=playlist") -> """{"result":{"playlists":{"total":1,"results":[{"kind":4,"owner":{"uid":55},"title":"Road","trackCount":2}]}}}"""
            url.contains("/users/55/playlists/4") -> """{"result":{"tracks":[{"track":${track(9)}},{"track":${track(3)}}]}}"""
            else -> """{"result":{"id":7,"title":"Album","artists":[{"name":"Fallback"}],"volumes":[[${track(9)}],[${track(3)}]]}}"""
        } })
        val list = api.page("owner", MusicRequest("Road", MusicKind.PLAYLISTS), 0).entries.single().entity!!
        assertEquals("55", list.ownerId)
        assertEquals(listOf("yandex:9:7", "yandex:3:7"), api.page("owner", MusicRequest(entity = list), 0).entries.map { it.id })
        assertEquals(listOf("yandex:9:7", "yandex:3:7"), api.page("owner", MusicRequest(entity = MusicEntity("7", "Album", MusicKind.ALBUMS)), 0).entries.map { it.id })
    }
    @Test fun playlistMixedReferencesAndBatchRepliesKeepOriginalOrder() = runBlocking {
        val api = YandexMusicApi(auth(this), MusicTransport { url, _, form ->
            if (url.endsWith("/tracks")) {
                assertEquals(listOf("with-positions" to "true", "track-ids" to "3:7", "track-ids" to "1:7"), form)
                """{"result":[${track(1)},${track(3)}]}"""
            } else """{"result":{"tracks":[{"id":3,"albumId":7},{"track":${track(2)}},{"id":1,"albumId":7}]}}"""
        })
        val result = api.page("owner", MusicRequest(entity = MusicEntity("4", "List", MusicKind.PLAYLISTS, "55")), 0)
        assertEquals(listOf("yandex:3:7", "yandex:2:7", "yandex:1:7"), result.entries.map { it.id })
    }
    @Test fun likedCollectionOnlyResolvesOnePageOfMetadata() = runBlocking {
        var requested = 0
        val rows = (1..75).joinToString(",") { """{"id":$it,"albumId":7}""" }
        val api = YandexMusicApi(auth(this), MusicTransport { url, _, form ->
            if (url.endsWith("/tracks")) {
                requested = form!!.count { it.first == "track-ids" }
                assertEquals("51:7", form.first { it.first == "track-ids" }.second)
                """{"result":[${track(51)}]}"""
            } else {
                assertTrue(url.contains("/users/123/likes/tracks"))
                """{"result":{"library":{"tracks":[$rows]}}}"""
            }
        })
        val result = api.page("owner", MusicRequest(collection = true), 1)
        assertEquals(25, requested); assertNull(result.nextPage)
    }
    @Test fun artistPagerControlsNextPage() = runBlocking {
        val api = YandexMusicApi(auth(this), MusicTransport { url, _, _ ->
            assertTrue(url.contains("/artists/5/tracks?page=1&page-size=50"))
            """{"result":{"pager":{"total":101,"perPage":50},"tracks":[${track(1)}]}}"""
        })
        assertEquals(2, api.page("owner", MusicRequest(entity = MusicEntity("5", "Artist", MusicKind.ARTISTS)), 1).nextPage)
    }
    @Test fun streamRejectsPreviewAndSendsNoOAuthToInfoServer() = runBlocking {
        val api = YandexMusicApi(auth(this), MusicTransport { url, token, _ ->
            if (url.endsWith("download-info")) {
                assertEquals("fixture-token", token)
                """{"result":[{"codec":"mp3","bitrateInKbps":999,"preview":true,"downloadInfoUrl":"https://evil.example/x"},{"codec":"mp3","bitrateInKbps":320,"downloadInfoUrl":"https://storage.yandex.net/info"}]}"""
            } else {
                assertEquals("https://storage.yandex.net/info", url); assertNull(token)
                "<download-info><host>storage.yandex.net</host><path>/abc.mp3</path><ts>123</ts><s>salt</s></download-info>"
            }
        })
        val result = api.stream("owner", "yandex:1:7")
        assertEquals("https://storage.yandex.net/get-mp3/" + java.security.MessageDigest.getInstance("MD5")
            .digest("XGRlBW9FXlekgbPrRHuSiAabc.mp3salt".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } + "/123/abc.mp3", result)
    }
    @Test fun previewOnlyAndUntrustedXmlAreRejected() = runBlocking {
        val api = YandexMusicApi(auth(this), MusicTransport { _, _, _ -> """{"result":[{"codec":"mp3","preview":true}]}""" })
        assertEquals(MusicFailure.UNAVAILABLE, (runCatching { api.stream("owner", "yandex:1") }.exceptionOrNull() as MusicException).failure)
        assertTrue(runCatching { YandexMusicApi.buildDirectLink("<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///secret'>]><root>&x;</root>") }.isFailure)
        assertFalse(isYandexMediaUrl("https://storage.yandex.net.evil.example/info"))
        assertFalse(isYandexMediaUrl("https://user:password@storage.yandex.net/info"))
        assertTrue(runCatching { YandexMusicApi.secureUrl("file:///secret") }.isFailure)
    }
    @Test fun directLinkPreservesHexTimestampFromLiveYandexResponse() {
        // Live public XML for Titanik, 2026-09-12: ts=1a093e33038. 1.x treats it as text.
        for (ts in listOf("1a093e33038", "0001A093E33038")) {
            val xml = "<download-info><host>api.music.yandex.net</host><path>/abc.mp3</path><ts>$ts</ts><s>salt</s></download-info>"
            assertEquals("https://api.music.yandex.net/get-mp3/2f148641ff189e7401af984377ed413d/$ts/abc.mp3", YandexMusicApi.buildDirectLink(xml))
        }
    }
    @Test fun defaultAudioSelectionMatchesLegacyAutoIncludingOtherCodecs() = runBlocking {
        for ((variants, expected) in listOf(
            """{"codec":"aac","bitrateInKbps":192,"downloadInfoUrl":"https://storage.yandex.net/aac"}""" to "aac",
            """{"codec":"aac","bitrateInKbps":320,"downloadInfoUrl":"https://storage.yandex.net/aac"},{"codec":"mp3","bitrateInKbps":320,"downloadInfoUrl":"https://storage.yandex.net/mp3"}""" to "mp3",
            """{"codec":"aac","bitrateInKbps":320,"downloadInfoUrl":"https://storage.yandex.net/aac"},{"codec":"mp3","bitrateInKbps":192,"downloadInfoUrl":"https://storage.yandex.net/mp3"}""" to "aac"
        )) {
            var fetched = false
            val api = YandexMusicApi(auth(this), MusicTransport { url, token, _ ->
                if (url.endsWith("download-info")) """{"result":[$variants]}""" else {
                    assertEquals("https://storage.yandex.net/$expected", url); assertNull(token); fetched = true
                    "<download-info><host>storage.yandex.net</host><path>/abc.mp3</path><ts>1a093e33038</ts><s>salt</s></download-info>"
                }
            })
            assertTrue(api.stream("owner", "yandex:1:7").contains("/1a093e33038/abc.mp3")); assertTrue(fetched)
        }
    }
    @Test fun opaqueTimestampCannotChangeUriStructure() {
        for (ts in listOf("", "../a", "a/b", "a?x=1", "a#x", "a%2fb", "a b")) {
            val xml = "<download-info><host>storage.yandex.net</host><path>/abc.mp3</path><ts>$ts</ts><s>salt</s></download-info>"
            assertTrue(runCatching { YandexMusicApi.buildDirectLink(xml) }.isFailure)
        }
    }
    @Test fun transientHtmlErrorsAreClassifiedBeforeParsingAndRedirectsDoNotLeakOAuth() = runBlocking {
        for ((code, expected) in listOf(503 to MusicFailure.NETWORK, 429 to MusicFailure.NETWORK, 403 to MusicFailure.ACCESS, 404 to MusicFailure.UNAVAILABLE, 302 to MusicFailure.RESPONSE)) {
            val transport = HttpsMusicTransport { object : HttpsURLConnection(URL("https://api.music.yandex.net/test")) {
                override fun getResponseCode() = code
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun connect() = Unit
                override fun getCipherSuite() = "fixture"
                override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
                override fun getServerCertificates(): Array<java.security.cert.Certificate> = emptyArray()
            } }
            assertEquals(expected, (runCatching { transport.request("https://api.music.yandex.net/test", "fixture", null) }.exceptionOrNull() as MusicException).failure)
        }
        var opened = false
        val transport = HttpsMusicTransport { opened = true; error("should not open") }
        assertTrue(runCatching { transport.request("https://storage.yandex.net/test", "fixture", null) }.isFailure)
        assertFalse(opened)
    }
}
