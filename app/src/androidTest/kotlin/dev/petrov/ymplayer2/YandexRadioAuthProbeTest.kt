package dev.petrov.ymplayer2

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.AccountSession
import dev.petrov.ymplayer2.core.OAuthCredentials
import dev.petrov.ymplayer2.core.YandexAccount
import dev.petrov.ymplayer2.yandex.KeystoreAccountStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class YandexRadioAuthProbeTest {
    private val token = "fixture-private-radio-token"
    @Test fun liveFmRejectsAnonymousAndInvalidTokenFromAndroid() = runBlocking {
        val http = RadioProbeHttp()
        assertEquals(401, http.get(YandexRadioAuthProbe.FM + YandexRadioAuthProbe.STATIONS, null).status)
        assertEquals(401, http.get(YandexRadioAuthProbe.FM + YandexRadioAuthProbe.STATIONS, YandexRadioAuthProbe.INVALID).status)
    }
    private fun fixture(fmUid: String? = "1234567", publicCollection: Boolean = false): RadioProbeTransport = RadioProbeTransport { url, credential ->
        when {
            url.startsWith(YandexRadioAuthProbe.MUSIC) -> RadioProbeResponse(200, """{"result":{"account":{"uid":"1234567","login":"private-login"}}}""")
            url.endsWith(YandexRadioAuthProbe.ABOUT) -> RadioProbeResponse(200,
                if (credential == token && fmUid != null) """{"result":{"uid":"$fmUid","message":"private-server-message"}}""" else "{}")
            credential == token || publicCollection -> RadioProbeResponse(200, """{"slugs":["private-favourite-station"]}""")
            else -> RadioProbeResponse(401, """{"message":"private-server-message"}""")
        }
    }
    private suspend fun report(uid: String? = "1234567", public: Boolean = false, expected: String = "1234567") =
        JSONObject(YandexRadioAuthProbe(fixture(uid, public)).run(token, expected).diagnosticText())

    @Test fun matchingAccountRequiresProtectedCollection() = runBlocking {
        assertTrue(report().getBoolean("personalAccountIdentityVerified"))
        assertFalse(report(public = true).getBoolean("existingTokenVerifiedForFmCollection"))
    }
    @Test fun mismatchAndMissingUidNeverClaimIdentity() = runBlocking {
        assertFalse(report(uid = "7654321").getBoolean("personalAccountIdentityVerified"))
        val partial = report(uid = null)
        assertTrue(partial.getBoolean("existingTokenVerifiedForFmCollection"))
        assertFalse(partial.getBoolean("personalAccountIdentityVerified"))
        assertTrue(YandexRadioAuthProbe(fixture("7654321")).run(token, "1234567").summary.contains("не совпали"))
        assertTrue(YandexRadioAuthProbe(fixture(null)).run(token, "1234567").summary.contains("не вернуло UID"))
    }
    @Test fun wrongSavedProfileStopsBeforeFm() = runBlocking {
        val stopped = report(expected = "7654321")
        assertTrue(stopped.getBoolean("stoppedBeforeFm"))
        assertFalse(stopped.getBoolean("personalAccountIdentityVerified"))
    }
    @Test fun existingKeystoreTokenIsReadWithoutChangingCredentialsAndExportIsRedacted() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = KeystoreAccountStore(context)
        val profile = "radio-probe-fixture"
        val journalFile = File(context.cacheDir, "radio-probe-journal.log")
        val journal = DiagnosticsJournal(context, journalFile)
        try {
            store.write(profile, AccountSession(YandexAccount("1234567", "private-name"), OAuthCredentials(token, "private-refresh", null)))
            val session = requireNotNull(store.read(profile))
            val result = YandexRadioAuthProbe(fixture()).run(session.credentials.accessToken, session.account?.id)
            journal.saveRadioProbe(result)
            val snapshot = journal.snapshot()
            assertTrue(snapshot.contains("\"personalAccountIdentityVerified\": true"))
            listOf(token, "private-refresh", "1234567", "private-name", "private-login", "private-favourite-station", "private-server-message").forEach {
                assertFalse("Sensitive value in exported snapshot", snapshot.contains(it))
            }
            assertEquals(token, store.read(profile)?.credentials?.accessToken)
            assertEquals("private-refresh", store.read(profile)?.credentials?.refreshToken)
            journal.clear()
            assertFalse(journal.snapshot().contains("Radio OAuth probe:"))
        } finally { store.write(profile, null); journal.clear() }
    }
    @Test fun transportRejectsUnapprovedOriginsBeforeOpeningConnection() = runBlocking {
        var opened = false
        val http = RadioProbeHttp { opened = true; throw AssertionError("Must not open") }
        listOf("https://example.org/account/about", "http://radio.api.music.yandex.ru/account/about",
            "https://radio.api.music.yandex.ru:444/account/about", "https://user@radio.api.music.yandex.ru/account/about").forEach { url ->
            try { http.get(url, token); fail("Origin accepted") } catch (_: IllegalArgumentException) { }
        }
        assertFalse(opened)
    }
}
