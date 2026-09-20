package dev.petrov.ymplayer2

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.yandex.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioQualityAdapterTest {
    private suspend fun selected(scope: CoroutineScope, variants: String, quality: AudioQuality): String {
        val session = AccountSession(YandexAccount("1", "Fixture"), OAuthCredentials("fixture-only", null, null))
        val accounts = AccountAuth(DemoCatalog().profiles, object : DeviceAuthApi {
            override val configured = true
            override suspend fun requestCode(profileId: String) = error("unused")
            override suspend fun poll(code: DeviceChallenge) = error("unused")
            override suspend fun account(credentials: OAuthCredentials) = session.account!!
        }, object : AccountStore {
            override suspend fun read(profileId: String) = session
            override suspend fun write(profileId: String, session: AccountSession?) = Unit
        }, scope).also { it.activate("owner"); yield() }
        var chosen = ""
        val api = YandexMusicApi(accounts, MusicTransport { url, token, _ ->
            if (url.endsWith("download-info")) { assertEquals("fixture-only", token); """{"result":[$variants]}""" }
            else {
                assertNull(token); chosen = url.substringAfterLast('/')
                "<download-info><host>storage.yandex.net</host><path>/audio.mp3</path><ts>1a093e33038</ts><s>salt</s></download-info>"
            }
        })
        assertTrue(api.stream("owner", "yandex:1", quality).contains("/1a093e33038/audio.mp3"))
        return chosen
    }
    private fun variant(rate: Int, codec: String = "mp3", preview: Boolean = false) = """{"codec":"$codec","bitrateInKbps":$rate,"preview":$preview,"downloadInfoUrl":"https://storage.yandex.net/$codec-$rate"}"""
    @Test fun allProfilesUseLegacyThresholdAndSkipPreviews() = runBlocking {
        val rows = listOf(variant(128), variant(192), variant(320), variant(999, preview = true)).joinToString(",")
        for ((quality, rate) in listOf(AudioQuality.AUTO to 320, AudioQuality.ECONOMY to 128, AudioQuality.STANDARD to 192, AudioQuality.HIGH to 320, AudioQuality.MAX to 320))
            assertEquals("mp3-$rate", selected(this, rows, quality))
    }
    @Test fun missingExactRateUsesBestBelowBeforeNearestAbove() = runBlocking {
        assertEquals("mp3-128", selected(this, listOf(variant(128), variant(256)).joinToString(","), AudioQuality.STANDARD))
        assertEquals("mp3-192", selected(this, listOf(variant(320), variant(192)).joinToString(","), AudioQuality.ECONOMY))
        assertEquals("mp3-256", selected(this, listOf(variant(128), variant(256)).joinToString(","), AudioQuality.HIGH))
    }
    @Test fun codecPreferenceDoesNotTurnIntoMp3OnlyOrLosslessPromise() = runBlocking {
        assertEquals("mp3-192", selected(this, listOf(variant(192,"aac"), variant(192)).joinToString(","), AudioQuality.STANDARD))
        assertEquals("aac-192", selected(this, variant(192,"aac"), AudioQuality.ECONOMY))
        assertEquals("aac-320", selected(this, listOf(variant(192), variant(320,"aac"), variant(0,"flac")).joinToString(","), AudioQuality.MAX))
    }
}
