package dev.petrov.ymplayer2.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountAuthTest {
    private val profiles = DemoCatalog().profiles
    private fun credentials() = OAuthCredentials("private-access", "private-refresh", 900_000)
    private val challenge = DeviceChallenge("private-device", "ABCD1234", "https://oauth.yandex.ru/device", 30, 5)
    private class Store : AccountStore {
        val data = mutableMapOf<String, AccountSession>()
        var writeDelay = 0L
        var fail = false
        override suspend fun read(profileId: String) = data[profileId]
        override suspend fun write(profileId: String, session: AccountSession?) {
            delay(writeDelay)
            if (fail) throw AuthException(AuthFailure.STORAGE)
            if (session == null) data.remove(profileId) else data[profileId] = session
        }
    }
    private inner class Api : DeviceAuthApi {
        override var configured = true
        var requests = 0
        var polls = 0
        var requestDelay = 0L
        var pollDelay = 0L
        var accountDelay = 0L
        var accountFailure: AuthFailure? = null
        var accounts = 0
        var result: TokenPoll = TokenPoll.Granted(credentials())
        var failure: AuthFailure? = null
        override suspend fun requestCode(profileId: String): DeviceChallenge {
            requests++
            withContext(NonCancellable) { delay(requestDelay) }
            return challenge
        }
        override suspend fun poll(code: DeviceChallenge): TokenPoll {
            polls++
            withContext(NonCancellable) { delay(pollDelay) }
            failure?.let { throw AuthException(it) }
            return result
        }
        override suspend fun account(credentials: OAuthCredentials): YandexAccount {
            accounts++
            withContext(NonCancellable) { delay(accountDelay) }
            accountFailure?.let { throw AuthException(it) }
            return YandexAccount("123", "Тестовый аккаунт")
        }
    }
    @Test fun temporaryNetworkFailureKeepsCodeAndResumesTheSameLogin() = runTest {
        val api = Api().apply { failure = AuthFailure.NETWORK }; val store = Store()
        val auth = AccountAuth(profiles, api, store, this) { testScheduler.currentTime }
        auth.activate("owner"); runCurrent(); auth.start(); runCurrent()
        advanceTimeBy(5000); runCurrent()
        assertEquals(AuthPhase.WAITING, auth.state.value.phase)
        assertEquals("ABCD1234", auth.state.value.userCode)
        assertNotNull(auth.state.value.issue)
        api.failure = null
        advanceUntilIdle()
        assertEquals(AuthPhase.SIGNED_IN, auth.state.value.phase)
        assertEquals(1, api.requests); assertEquals(2, api.polls)
        assertTrue(store.data.containsKey("owner"))
    }
    @Test fun successRespectsIntervalAndRestoresOnlyMatchingProfile() = runTest {
        val api = Api(); val store = Store()
        val auth = AccountAuth(profiles, api, store, this) { testScheduler.currentTime }
        auth.activate("owner"); runCurrent(); auth.start(); auth.start(); runCurrent()
        assertEquals(AuthPhase.WAITING, auth.state.value.phase)
        advanceTimeBy(4999); runCurrent(); assertEquals(0, api.polls)
        advanceTimeBy(1); runCurrent()
        assertEquals(AuthPhase.SIGNED_IN, auth.state.value.phase); assertEquals(1, api.requests)
        assertEquals("private-access", store.data["owner"]!!.credentials.accessToken)
        auth.activate("road"); runCurrent(); assertEquals(AuthPhase.SIGNED_OUT, auth.state.value.phase)
        auth.start(); advanceUntilIdle(); assertEquals(2, store.data.size)
        auth.signOut(); runCurrent(); assertFalse(store.data.containsKey("road")); assertTrue(store.data.containsKey("owner"))
        val restored = AccountAuth(profiles, api, store, this) { testScheduler.currentTime }
        restored.activate("owner"); runCurrent(); assertEquals(AuthPhase.SIGNED_IN, restored.state.value.phase)
    }
    @Test fun pendingSlowDownAndExpirationFollowServerTiming() = runTest {
        val api = Api().apply { result = TokenPoll.Pending }; val store = Store()
        val auth = AccountAuth(profiles, api, store, this) { testScheduler.currentTime }
        auth.activate("owner"); runCurrent(); auth.start(); runCurrent()
        advanceTimeBy(5000); runCurrent(); assertEquals(1, api.polls)
        api.result = TokenPoll.SlowDown
        advanceTimeBy(5000); runCurrent(); assertEquals(2, api.polls)
        advanceTimeBy(9999); runCurrent(); assertEquals(2, api.polls)
        advanceTimeBy(1); runCurrent(); assertEquals(3, api.polls)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(AuthPhase.ERROR, auth.state.value.phase)
        assertTrue(auth.state.value.issue!!.contains("истёк")); assertNull(auth.state.value.userCode)
        assertTrue(store.data.isEmpty())
    }
    @Test fun cancelIgnoresLateDeviceCode() = runTest {
        val api = Api().apply { requestDelay = 1000 }; val store = Store()
        val auth = AccountAuth(profiles, api, store, this) { testScheduler.currentTime }
        auth.activate("owner"); runCurrent(); auth.start(); runCurrent(); auth.cancel(); advanceUntilIdle()
        assertEquals(AuthPhase.SIGNED_OUT, auth.state.value.phase); assertEquals(0, api.polls)
    }
    @Test fun profileSwitchIgnoresLateToken() = runTest {
        val api = Api().apply { pollDelay = 1000 }; val store = Store()
        val auth = AccountAuth(profiles, api, store, this) { testScheduler.currentTime }
        auth.activate("owner"); runCurrent(); auth.start(); runCurrent(); advanceTimeBy(5000); runCurrent()
        auth.activate("road"); advanceUntilIdle()
        assertEquals("road", auth.state.value.profileId); assertEquals(AuthPhase.SIGNED_OUT, auth.state.value.phase)
        assertTrue(store.data.isEmpty())
    }
    @Test fun logoutIgnoresLateAccountVerification() = runTest {
        val api = Api().apply { accountDelay = 1000 }; val store = Store()
        val auth = AccountAuth(profiles, api, store, this) { testScheduler.currentTime }
        auth.activate("owner"); runCurrent(); auth.start(); runCurrent(); advanceTimeBy(5000); runCurrent()
        assertEquals(AuthPhase.SIGNED_IN, auth.state.value.phase); assertTrue(auth.state.value.updatingAccount)
        auth.signOut(); advanceUntilIdle()
        assertEquals(AuthPhase.SIGNED_OUT, auth.state.value.phase); assertTrue(store.data.isEmpty())
    }
    @Test fun cancelDuringPersistenceRollsBackLateLogin() = runTest {
        val api = Api(); val store = Store().apply { writeDelay = 1000 }
        val auth = AccountAuth(profiles, api, store, this) { testScheduler.currentTime }
        auth.activate("owner"); runCurrent(); auth.start(); runCurrent(); advanceTimeBy(5000); runCurrent()
        auth.cancel(); advanceUntilIdle()
        assertTrue(store.data.isEmpty()); assertEquals(AuthPhase.SIGNED_OUT, auth.state.value.phase)
    }
    @Test fun logoutCompletesForItsProfileDuringSwitch() = runTest {
        val api = Api(); val store = Store()
        store.data["owner"] = AccountSession(YandexAccount("1", "Owner"), credentials())
        store.data["road"] = AccountSession(YandexAccount("2", "Road"), credentials())
        val auth = AccountAuth(profiles, api, store, this) { testScheduler.currentTime }
        auth.activate("owner"); runCurrent(); store.writeDelay = 1000
        auth.signOut(); runCurrent(); auth.activate("road"); advanceUntilIdle()
        assertFalse(store.data.containsKey("owner")); assertTrue(store.data.containsKey("road"))
        assertEquals("Road", auth.state.value.account?.name)
    }
    @Test fun guestAndUnconfiguredBuildNeverRequestTokens() = runTest {
        val api = Api(); val store = Store()
        val auth = AccountAuth(profiles, api, store, this) { testScheduler.currentTime }
        auth.activate("guest"); runCurrent(); auth.start(); runCurrent()
        assertEquals(AuthPhase.GUEST, auth.state.value.phase)
        api.configured = false; auth.activate("owner"); runCurrent(); auth.start(); runCurrent()
        assertEquals(AuthPhase.UNCONFIGURED, auth.state.value.phase); assertEquals(0, api.requests)
    }
    @Test fun deniedNetworkAndStorageFailuresDoNotExposeCredentialsOrKeepCode() = runTest {
        val api = Api(); val store = Store()
        val auth = AccountAuth(profiles, api, store, this) { testScheduler.currentTime }
        auth.activate("owner"); runCurrent()
        for (failure in listOf(AuthFailure.DENIED, AuthFailure.NETWORK, AuthFailure.STORAGE)) {
            api.failure = failure; auth.start(); advanceUntilIdle()
            assertEquals(AuthPhase.ERROR, auth.state.value.phase); assertNull(auth.state.value.userCode)
            assertFalse(auth.state.value.toString().contains("private")); assertTrue(store.data.isEmpty())
        }
        assertFalse(credentials().toString().contains("private")); assertFalse(challenge.toString().contains("private"))
    }
    @Test fun expiredSavedSessionRequiresNewLogin() = runTest {
        val store = Store().apply { data["owner"] = AccountSession(YandexAccount("1", "Old"), OAuthCredentials("secret", null, 1)) }
        val auth = AccountAuth(profiles, Api(), store, this) { 2 }
        auth.activate("owner"); runCurrent()
        assertEquals(AuthPhase.SIGNED_OUT, auth.state.value.phase); assertNull(auth.state.value.account)
    }
    @Test fun accountNetworkFailureRetainsTokenForRetryWithoutAnotherCodeOrPoll() = runTest {
        val api = Api().apply { accountFailure = AuthFailure.NETWORK }; val store = Store()
        val auth = AccountAuth(profiles, api, store, this) { testScheduler.currentTime }
        auth.activate("owner"); runCurrent(); auth.start(); advanceUntilIdle()
        assertEquals(AuthPhase.SIGNED_IN, auth.state.value.phase)
        assertNotNull(store.data["owner"]); assertNull(store.data["owner"]!!.account); assertNull(auth.state.value.userCode)
        assertEquals("ACCOUNT_NETWORK", auth.state.value.diagnostic)
        assertFalse(auth.state.value.toString().contains("private"))
        api.accountFailure = null
        auth.retryAccount(); auth.retryAccount(); advanceUntilIdle()
        assertEquals(AuthPhase.SIGNED_IN, auth.state.value.phase)
        assertEquals(1, api.requests); assertEquals(1, api.polls); assertEquals(2, api.accounts)
        assertEquals("private-access", store.data["owner"]!!.credentials.accessToken)
    }
    @Test fun logoutOrProfileSwitchCannotReuseAnotherProfilesAccountToken() = runTest {
        for (switch in listOf(false, true)) {
            val api = Api().apply { accountFailure = AuthFailure.NETWORK }; val store = Store()
            val auth = AccountAuth(profiles, api, store, this) { testScheduler.currentTime }
            auth.activate("owner"); runCurrent(); auth.start(); advanceUntilIdle()
            if (switch) auth.activate("road") else auth.signOut()
            runCurrent(); api.accountFailure = null; auth.retryAccount(); advanceUntilIdle()
            assertEquals(switch, store.data.containsKey("owner")); assertFalse(store.data.containsKey("road")); assertEquals(1, api.accounts)
            assertEquals(AuthPhase.SIGNED_OUT, auth.state.value.phase)
        }
    }
    @Test fun cancelDuringNetworkBackoffStopsAllFurtherPolls() = runTest {
        val api = Api().apply { failure = AuthFailure.NETWORK }; val store = Store()
        val auth = AccountAuth(profiles, api, store, this) { testScheduler.currentTime }
        auth.activate("owner"); runCurrent(); auth.start(); advanceTimeBy(5000); runCurrent()
        assertEquals(AuthPhase.WAITING, auth.state.value.phase)
        auth.cancel(); advanceUntilIdle()
        assertEquals(1, api.polls); assertEquals(AuthPhase.SIGNED_OUT, auth.state.value.phase)
    }
    @Test fun serverRetryAfterAndCodeExpiryBoundNetworkRetries() = runTest {
        var polls = 0
        val api = object : DeviceAuthApi {
            override val configured = true
            override suspend fun requestCode(profileId: String) = challenge
            override suspend fun poll(code: DeviceChallenge): TokenPoll {
                polls++; throw AuthException(AuthFailure.NETWORK, NetworkIssue.SERVICE, 60)
            }
            override suspend fun account(credentials: OAuthCredentials) = error("Unexpected account request")
        }
        val auth = AccountAuth(profiles, api, Store(), this) { testScheduler.currentTime }
        auth.activate("owner"); runCurrent(); auth.start(); advanceUntilIdle()
        assertEquals(1, polls); assertEquals(30_000, testScheduler.currentTime)
        assertEquals(AuthPhase.ERROR, auth.state.value.phase); assertNull(auth.state.value.userCode)
    }
    @Test fun expiredSavedTokenRequiresNewCodeAndDoesNotReachAccountApi() = runTest {
        val api = Api().apply { accountFailure = AuthFailure.NETWORK; result = TokenPoll.Granted(OAuthCredentials("private", null, 6000)) }
        val auth = AccountAuth(profiles, api, Store(), this) { testScheduler.currentTime }
        auth.activate("owner"); runCurrent(); auth.start(); advanceUntilIdle()
        advanceTimeBy(1000); auth.retryAccount(); runCurrent()
        assertEquals(1, api.accounts); assertEquals(AuthPhase.SIGNED_OUT, auth.state.value.phase)
    }
    @Test fun savedTokenSurvivesRestartEvenWhenAccountServiceStaysUnavailable() = runTest {
        val api = Api().apply { accountFailure = AuthFailure.NETWORK }; val store = Store()
        val auth = AccountAuth(profiles, api, store, this) { testScheduler.currentTime }
        auth.activate("owner"); runCurrent(); auth.start(); advanceUntilIdle()
        val restored = AccountAuth(profiles, api, store, this) { testScheduler.currentTime }
        restored.activate("owner"); advanceUntilIdle()
        assertEquals(AuthPhase.SIGNED_IN, restored.state.value.phase)
        assertEquals("private-access", store.data["owner"]!!.credentials.accessToken)
        assertEquals(1, api.requests); assertEquals(1, api.polls); assertEquals(2, api.accounts)
        api.accountFailure = null; restored.retryAccount(); advanceUntilIdle()
        assertEquals("123", restored.state.value.account?.id)
    }
}
