package dev.petrov.ymplayer2

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.yandex.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineAdapterTest {
    private suspend fun auth(scope: CoroutineScope): AccountAuth {
        val session = AccountSession(YandexAccount("123", "Fixture"), OAuthCredentials("fixture-token", null, null))
        return AccountAuth(DemoCatalog().profiles, object : DeviceAuthApi {
            override val configured = true
            override suspend fun requestCode(profileId: String) = error("unused")
            override suspend fun poll(code: DeviceChallenge) = error("unused")
            override suspend fun account(credentials: OAuthCredentials) = session.account!!
        }, object : AccountStore {
            override suspend fun read(profileId: String) = session
            override suspend fun write(profileId: String, session: AccountSession?) = Unit
        }, scope).also { it.activate("owner"); yield() }
    }
    @Test fun fullLikesSnapshotResolvesAllBatchesAndRetainsMissingMetadataIds() = runBlocking {
        val batches = mutableListOf<Int>(); var snapshots = 0
        val music = YandexMusicApi(auth(this), MusicTransport { url, token, form ->
            assertEquals("fixture-token", token)
            if (url.endsWith("/tracks")) {
                val ids = form!!.filter { it.first == "track-ids" }.map { it.second.substringBefore(':').toInt() }
                batches += ids.size
                """{"result":[${ids.filter { it != 75 }.reversed().joinToString(",") { """{"id":$it,"title":"T$it","albums":[{"id":7}],"available":true}""" }}]}"""
            } else {
                assertTrue(url.contains("/users/123/likes/tracks?")); snapshots++
                """{"result":{"library":{"tracks":[${(1..75).joinToString(",") { """{"id":$it,"albumId":7}""" }}]}}}"""
            }
        })
        val api = YandexLikedMusicApi(music); val snapshot = api.snapshot("owner")
        assertEquals(listOf(50, 25), batches); assertEquals(1, snapshots)
        assertEquals(75, snapshot.keys.size); assertEquals((1..74).map { "yandex:$it:7" }, snapshot.tracks.map(Track::id))
        assertTrue("75" in api.keys("owner")); assertEquals(2, snapshots); assertEquals(2, batches.size)
    }
    @Test fun malformedSnapshotIsAnErrorRatherThanAnEmptyCollection() = runBlocking {
        for (body in listOf("""{"result":{"library":{}}}""", """{"result":{"library":{"tracks":[{"unexpected":1}]}}}""")) {
            val api = YandexLikedMusicApi(YandexMusicApi(auth(this), MusicTransport { _, _, _ -> body }))
            assertTrue(runCatching { api.snapshot("owner") }.isFailure)
        }
    }
    @Test fun emptyConfirmedCollectionAndDuplicateAlbumReferencesStayTrackScoped() = runBlocking {
        var empty = false
        val api = YandexLikedMusicApi(YandexMusicApi(auth(this), MusicTransport { url, _, form ->
            if (url.endsWith("/tracks")) {
                assertEquals(1, form!!.count { it.first == "track-ids" })
                """{"result":[{"id":1,"title":"One","albums":[{"id":7}]}]}"""
            } else """{"result":{"library":{"tracks":[${if (empty) "" else """{"id":1,"albumId":7},{"id":1,"albumId":8}"""}]}}}"""
        }))
        assertEquals(setOf("1"), api.snapshot("owner").keys)
        empty = true; assertTrue(api.snapshot("owner").keys.isEmpty())
    }
}
