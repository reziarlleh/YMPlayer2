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
    val diagnostic: String? = null,
    val updatingAccount: Boolean = false,
)

/** Credentials never form part of UI state, saved instance state or diagnostic strings. */
class OAuthCredentials(val accessToken: String, val refreshToken: String?, val expiresAtMillis: Long?) {
    override fun toString() = "OAuthCredentials(redacted)"
}
class AccountSession(val account: YandexAccount?, val credentials: OAuthCredentials) {
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
enum class NetworkIssue { DNS, TIMEOUT, TLS, CONNECTION, SERVICE }
class AuthException(val failure: AuthFailure, val networkIssue: NetworkIssue? = null, val retryAfterSeconds: Long? = null) : Exception(failure.name)

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
            if (saved != null && !expired && saved.account == null && api.configured) updateAccount(profile, ticket, saved.credentials)
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
            var stage = "CODE"
            try {
                val challenge = api.requestCode(profile)
                if (!valid(ticket, profile)) return@launch
                require(challenge.expiresInSeconds in 1..3600 && challenge.intervalSeconds in 1..3600)
                val deadline = now() + challenge.expiresInSeconds * 1000
                var interval = challenge.intervalSeconds * 1000
                var waitMillis = interval
                mutable.value = AccountAuthState(profile, AuthPhase.WAITING, userCode = challenge.userCode,
                    verificationUrl = challenge.verificationUrl, expiresAtMillis = deadline)
                while (valid(ticket, profile)) {
                    delay(minOf(waitMillis, (deadline - now()).coerceAtLeast(0)))
                    if (now() >= deadline) throw AuthException(AuthFailure.EXPIRED)
                    stage = "TOKEN"
                    val response = try { withTimeout((deadline - now()).coerceAtLeast(1)) { api.poll(challenge) } }
                    catch (_: TimeoutCancellationException) { throw AuthException(AuthFailure.EXPIRED) }
                    catch (e: AuthException) {
                        if (e.failure != AuthFailure.NETWORK) throw e
                        if (!valid(ticket, profile)) return@launch
                        mutable.value = state.value.copy(issue = "Не удалось проверить подтверждение. Код сохранён, повторяем запрос. После подтверждения вернитесь в приложение.", diagnostic = diagnostic(stage, e))
                        waitMillis = maxOf(interval, (waitMillis * 2).coerceAtMost(30_000), (e.retryAfterSeconds ?: 0).coerceIn(0, 3600) * 1000)
                        continue
                    }
                    if (!valid(ticket, profile)) return@launch
                    mutable.value = state.value.copy(issue = null, diagnostic = null)
                    when (response) {
                        TokenPoll.Pending -> waitMillis = interval
                        TokenPoll.SlowDown -> { interval = (interval + 5000).coerceAtMost(3_600_000); waitMillis = interval }
                        is TokenPoll.Granted -> {
                            completeLogin(profile, ticket, response.credentials)
                            return@launch
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: AuthException) { if (valid(ticket, profile)) fail(profile, e.failure, diagnostic(stage, e)) }
            catch (_: Exception) { if (valid(ticket, profile)) fail(profile, AuthFailure.RESPONSE) }
        }
    }

    /** Account details are optional: refresh them from the durable token without another OAuth exchange. */
    fun retryAccount() {
        val current = state.value
        if (current.phase != AuthPhase.SIGNED_IN || current.updatingAccount || (current.account != null && current.issue == null)) return
        val ticket = stopWork()
        mutable.value = current.copy(updatingAccount = true)
        job = scope.launch {
            try {
                val saved = writes.withLock { store.read(current.profileId) }
                if (!valid(ticket, current.profileId)) return@launch
                if (saved == null || saved.credentials.expiresAtMillis?.let { it <= now() } == true) {
                    restore(current.profileId, ticket)
                } else updateAccount(current.profileId, ticket, saved.credentials)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (valid(ticket, current.profileId)) fail(current.profileId, AuthFailure.STORAGE, "STORE_STORAGE") }
        }
    }

    private suspend fun completeLogin(profile: String, ticket: Long, credentials: OAuthCredentials) {
        mutable.value = AccountAuthState(profile, AuthPhase.VERIFYING)
        try {
            // Match 1.x: successful OAuth is saved before any optional Music account request.
            saveSession(profile, ticket, AccountSession(null, credentials))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (valid(ticket, profile)) fail(profile, AuthFailure.STORAGE, "STORE_STORAGE"); return }
        if (!valid(ticket, profile)) return
        mutable.value = AccountAuthState(profile, AuthPhase.SIGNED_IN)
        updateAccount(profile, ticket, credentials)
    }

    private suspend fun updateAccount(profile: String, ticket: Long, credentials: OAuthCredentials) {
        if (!valid(ticket, profile)) return
        mutable.value = state.value.copy(updatingAccount = true)
        var stage = "ACCOUNT"
        try {
            val account = api.account(credentials)
            if (!valid(ticket, profile)) return
            stage = "STORE"
            saveSession(profile, ticket, AccountSession(account, credentials))
            if (valid(ticket, profile)) mutable.value = AccountAuthState(profile, AuthPhase.SIGNED_IN, account)
        } catch (e: CancellationException) { throw e }
        catch (e: AuthException) {
            if (!valid(ticket, profile)) return
            accountIssue(stage, e)
        } catch (_: Exception) { if (valid(ticket, profile)) accountIssue(stage, AuthException(AuthFailure.RESPONSE)) }
    }

    private fun accountIssue(stage: String, error: AuthException) {
        mutable.value = state.value.copy(updatingAccount = false,
            issue = "Вход сохранён. Сведения об аккаунте Музыки пока недоступны. Их можно обновить позже без повторного ввода кода.", diagnostic = diagnostic(stage, error))
    }

    private suspend fun saveSession(profile: String, ticket: Long, session: AccountSession) {
        // A cancellation during an atomic disk write must not leave a late login or metadata update behind.
        withContext(NonCancellable) {
            writes.withLock {
                if (valid(ticket, profile)) {
                    val previous = store.read(profile)
                    store.write(profile, session)
                    if (!valid(ticket, profile)) store.write(profile, previous)
                }
            }
        }
    }

    private fun diagnostic(stage: String, error: AuthException) = "${stage}_${error.networkIssue?.name ?: error.failure.name}"

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

    private fun fail(profile: String, failure: AuthFailure, diagnostic: String? = null) {
        val message = when (failure) {
            AuthFailure.NETWORK -> "Нет связи с Яндексом. Проверьте сеть и повторите вход."
            AuthFailure.EXPIRED -> "Код истёк. Получите новый код."
            AuthFailure.DENIED -> "Вход не разрешён. Можно попробовать снова."
            AuthFailure.CLIENT -> "Яндекс отклонил вход для этой сборки."
            AuthFailure.ACCOUNT -> "Не удалось подтвердить доступ к аккаунту Яндекс Музыки."
            AuthFailure.RESPONSE -> "Получен некорректный ответ Яндекса. Повторите вход."
            AuthFailure.STORAGE -> "Не удалось прочитать или сохранить вход на устройстве."
        }
        mutable.value = AccountAuthState(profile, AuthPhase.ERROR, issue = message, diagnostic = diagnostic)
    }
}
