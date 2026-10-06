package dev.petrov.ymplayer2.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class WaveRequest(val station: String = "user:onyourwave", val title: String = "Моя волна",
    val settings: List<String> = emptyList()) {
    val seeds get() = listOf(station) + settings
}
data class WaveOption(val seed: String, val title: String, val unspecified: Boolean = false)
data class WaveOptionGroup(val key: String, val title: String, val values: List<WaveOption>)
data class WaveOptions(val groups: List<WaveOptionGroup>)
data class WaveSettingsState(val profileId: String = "", val signedIn: Boolean = false,
    val options: WaveOptions? = null, val selected: Map<String, String> = emptyMap(),
    val loading: Boolean = false, val issue: String? = null)

/** Account-scoped choices. Only server-advertised seeds can be selected; late replies cannot
 * populate another account. Settings customize sessions, without changing a user's web player. */
class WaveSettings(private val accounts: AccountAuth, private val api: MyWaveApi,
    private val scope: CoroutineScope, private val read: (String) -> Map<String, String> = { emptyMap() },
    private val write: (String, Map<String, String>) -> Unit = { _, _ -> }) {
    private val mutable = MutableStateFlow(WaveSettingsState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var accountKey = ""
    init {
        scope.launch {
            accounts.state.map { Triple(it.profileId, it.phase, it.account?.id) }.distinctUntilChanged().collect { (profile, phase, uid) ->
                generation++; job?.cancel()
                accountKey = "$profile:${uid.orEmpty()}"
                mutable.value = WaveSettingsState(profile, phase == AuthPhase.SIGNED_IN,
                    selected = if (phase == AuthPhase.SIGNED_IN) read(accountKey) else emptyMap())
            }
        }
    }
    fun load(language: String) {
        if (!state.value.signedIn || state.value.loading) return
        val profile = state.value.profileId; val key = accountKey; val ticket = ++generation
        mutable.value = state.value.copy(loading = true, issue = null)
        job = scope.launch {
            try {
                val options = api.options(profile, language)
                if (ticket != generation) return@launch
                val selected = state.value.selected.filter { (key, seed) -> options.groups.any { it.key == key && it.values.any { v -> v.seed == seed } } }
                mutable.value = state.value.copy(options = options, selected = selected, loading = false)
                write(key, selected)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == generation) mutable.value = state.value.copy(loading = false,
                issue = (e as? MusicException)?.failure?.message() ?: MusicFailure.NETWORK.message()) }
        }
    }
    fun select(key: String, seed: String) {
        if (!state.value.signedIn || state.value.options?.groups?.none { it.key == key && it.values.any { v -> v.seed == seed } } != false) return
        mutable.value = state.value.copy(selected = state.value.selected + (key to seed))
        write(accountKey, state.value.selected)
    }
    fun reset() { mutable.value = state.value.copy(selected = emptyMap()); if (state.value.signedIn) write(accountKey, emptyMap()) }
    fun request(profileId: String = state.value.profileId): WaveRequest {
        val s = state.value
        if (s.profileId != profileId || !s.signedIn) return WaveRequest()
        val station = s.selected["contexts"] ?: "user:onyourwave"
        val name = if (station == "user:onyourwave") "Моя волна" else
            s.options?.groups?.firstOrNull { it.key == "contexts" }?.values?.firstOrNull { it.seed == station }?.title ?: "Моя волна"
        return WaveRequest(station, name, s.selected.filterKeys { it != "contexts" }.values.toList())
    }
}
