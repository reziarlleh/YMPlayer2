package dev.petrov.ymplayer2.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class YandexAccount(val id: String, val name: String)
enum class AuthPhase { LOADING, SIGNED_OUT, REQUESTING, WAITING, VERIFYING, SIGNED_IN, ERROR, GUEST, UNCONFIGURED }
data class AccountAuthState(
    val profileId: String = "", val phase: AuthPhase = AuthPhase.LOADING,
    val account: YandexAccount? = null, val userCode: String? = null,
    val verificationUrl: String? = null, val expiresAtMillis: Long? = null, val issue: String? = null,
)

/** Credentials never form part of UI state, saved instance state or diagnostic strings. */
class OAuthCredentials(val accessToken: String, val refreshToken: String?, val expiresAtMillis: Long?) {
    override fun toString() = "OAuthCredentials(redacted)"
}
class AccountSession(val account: YandexAccount, val credentials: OAuthCredentials) {
    override fun toString() = "AccountSession(redacted)"
}
class DeviceChallenge(val deviceCode: String, val userCode: String, val verificationUrl: String, val expiresInSeconds: Long, val intervalSeconds: Long) {
    override fun toString() = "DeviceChallenge(redacted)"
}
sealed interface TokenPoll {
    data object Pending : TokenPoll
    data object SlowDown : TokenPoll
    class Granted(val credentials: OAuthCredentials) : TokenPoll
}
enum class AuthFailure { NETWORK, EXPIRED, DENIED, CLIENT, ACCOUNT, RESPONSE, STORAGE }
class AuthException(val failure: AuthFailure) : Exception(failure.name)

interface DeviceAuthApi {
    val configured: Boolean
    suspend fun requestCode(profileId: String): DeviceChallenge
    suspend fun poll(code: DeviceChallenge): TokenPoll
    suspend fun account(credentials: OAuthCredentials): YandexAccount
}
interface AccountStore {
    suspend fun read(profileId: String): AccountSession?
    suspend fun write(profileId: String, session: AccountSession?)
}

/** Called from the owner's serialized dispatcher (Android main). Network and storage do their own IO. */
class AccountAuth(
    private val profiles: List<Profile>, private val api: DeviceAuthApi, private val store: AccountStore,
    private val scope: CoroutineScope, private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow(AccountAuthState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private val writes = Mutex()
    private fun valid(ticket: Long, profile: String) = generation == ticket && state.value.profileId == profile
    private fun stopWork(): Long { generation++; job?.cancel(); return generation }

    fun activate(profileId: String) {
        if (state.value.profileId == profileId) return
        require(profiles.any { it.id == profileId })
        val ticket = stopWork()
        mutable.value = AccountAuthState(profileId)
        job = scope.launch { restore(profileId, ticket) }
    }

    private suspend fun restore(profile: String, ticket: Long) {
        try {
            if (profiles.first { it.id == profile }.guest) {
                if (valid(ticket, profile)) mutable.value = AccountAuthState(profile, AuthPhase.GUEST)
                return
            }
            val saved = writes.withLock { store.read(profile) }
            if (!valid(ticket, profile)) return
            val expired = saved?.credentials?.expiresAtMillis?.let { it <= now() } == true
            mutable.value = when {
                expired -> AccountAuthState(profile, AuthPhase.SIGNED_OUT, issue = "Срок входа истёк. Войдите заново.")
                saved != null -> AccountAuthState(profile, AuthPhase.SIGNED_IN, saved.account)
                !api.configured -> AccountAuthState(profile, AuthPhase.UNCONFIGURED)
                else -> AccountAuthState(profile, AuthPhase.SIGNED_OUT)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (valid(ticket, profile)) fail(profile, AuthFailure.STORAGE) }
    }

    fun cancel() {
        if (state.value.phase !in setOf(AuthPhase.REQUESTING, AuthPhase.WAITING, AuthPhase.VERIFYING)) return
        val profile = state.value.profileId
        val ticket = stopWork()
        mutable.value = AccountAuthState(profile)
        job = scope.launch { restore(profile, ticket) }
    }

    fun start() {
        val profile = state.value.profileId
        if (profiles.none { it.id == profile && !it.guest } || !api.configured || state.value.phase !in setOf(AuthPhase.SIGNED_OUT, AuthPhase.ERROR)) return
        val ticket = stopWork()
        mutable.value = AccountAuthState(profile, AuthPhase.REQUESTING)
        job = scope.launch {
            try {
                val challenge = api.requestCode(profile)
                if (!valid(ticket, profile)) return@launch
                require(challenge.expiresInSeconds in 1..3600 && challenge.intervalSeconds in 1..3600)
                val deadline = now() + challenge.expiresInSeconds * 1000
                var interval = challenge.intervalSeconds * 1000
                mutable.value = AccountAuthState(profile, AuthPhase.WAITING, userCode = challenge.userCode,
                    verificationUrl = challenge.verificationUrl, expiresAtMillis = deadline)
                while (valid(ticket, profile)) {
                    delay(minOf(interval, (deadline - now()).coerceAtLeast(0)))
                    if (now() >= deadline) throw AuthException(AuthFailure.EXPIRED)
                    val response = try { withTimeout((deadline - now()).coerceAtLeast(1)) { api.poll(challenge) } }
                    catch (_: TimeoutCancellationException) { throw AuthException(AuthFailure.EXPIRED) }
                    if (!valid(ticket, profile)) return@launch
                    when (response) {
                        TokenPoll.Pending -> Unit
                        TokenPoll.SlowDown -> interval = (interval + 5000).coerceAtMost(3_600_000)
                        is TokenPoll.Granted -> {
                            mutable.value = AccountAuthState(profile, AuthPhase.VERIFYING)
                            val account = api.account(response.credentials)
                            if (!valid(ticket, profile)) return@launch
                            // A cancellation during an atomic disk write must not leave a late login behind.
                            withContext(NonCancellable) {
                                writes.withLock {
                                    if (valid(ticket, profile)) {
                                        val previous = store.read(profile)
                                        store.write(profile, AccountSession(account, response.credentials))
                                        if (!valid(ticket, profile)) store.write(profile, previous)
                                    }
                                }
                            }
                            if (valid(ticket, profile)) mutable.value = AccountAuthState(profile, AuthPhase.SIGNED_IN, account)
                            return@launch
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: AuthException) { if (valid(ticket, profile)) fail(profile, e.failure) }
            catch (_: Exception) { if (valid(ticket, profile)) fail(profile, AuthFailure.RESPONSE) }
        }
    }

    fun signOut() {
        val profile = state.value.profileId
        if (profiles.none { it.id == profile && !it.guest }) return
        val ticket = stopWork()
        mutable.value = AccountAuthState(profile)
        // Logout belongs to the requested profile even if the user switches during the write.
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) { writes.withLock { store.write(profile, null) } }
                if (valid(ticket, profile)) restore(profile, ticket)
            } catch (_: Exception) { if (valid(ticket, profile)) fail(profile, AuthFailure.STORAGE) }
        }
    }

    private fun fail(profile: String, failure: AuthFailure) {
        val message = when (failure) {
            AuthFailure.NETWORK -> "Нет связи с Яндексом. Проверьте сеть и повторите вход."
            AuthFailure.EXPIRED -> "Код истёк. Получите новый код."
            AuthFailure.DENIED -> "Вход не разрешён. Можно попробовать снова."
            AuthFailure.CLIENT -> "Яндекс отклонил вход для этой сборки."
            AuthFailure.ACCOUNT -> "Не удалось подтвердить доступ к аккаунту Яндекс Музыки."
            AuthFailure.RESPONSE -> "Получен некорректный ответ Яндекса. Повторите вход."
            AuthFailure.STORAGE -> "Не удалось прочитать или сохранить вход на устройстве."
        }
        mutable.value = AccountAuthState(profile, AuthPhase.ERROR, issue = message)
    }
}
