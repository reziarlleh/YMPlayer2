package dev.petrov.ymplayer2.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** Stable IDs own accounts and checkpoints. Display names never form storage keys. */
class UserProfiles(initial: List<Profile>,
    private val persist: suspend (List<Profile>) -> Unit,
    private val beforeRemove: suspend (String, String) -> Unit = { _, _ -> },
    val writable: Boolean = true,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(initial.toList().also(::validate))
    val state = mutable.asStateFlow()
    private val working = MutableStateFlow(false)
    val busy = working.asStateFlow()

    suspend fun create(name: String): Profile = mutate {
        val profile = Profile(newId(), checkedName(name), "", userNamed = true)
        require(it.none { p -> p.id == profile.id })
        (it + profile) to profile
    }
    suspend fun rename(id: String, name: String) = mutate {
        require(it.any { p -> p.id == id })
        it.map { p -> if (p.id == id) p.copy(name = checkedName(name), defaultNumber = null, userNamed = true) else p } to Unit
    }
    suspend fun remove(id: String) = mutate {
        require(it.size > 1 && it.any { p -> p.id == id })
        val remaining = it.filterNot { p -> p.id == id }
        beforeRemove(id, remaining.first().id)
        remaining to Unit
    }
    private suspend fun <T> mutate(change: suspend (List<Profile>) -> Pair<List<Profile>, T>): T = mutex.withLock {
        check(writable) { "Profile storage is unavailable" }
        // Once a durable mutation starts, closing a dialog must not leave disk and state divergent.
        working.value = true
        try { withContext(NonCancellable) {
            val (next, result) = change(state.value)
            validate(next)
            persist(next)
            mutable.value = next.toList()
            result
        } } finally { working.value = false }
    }
    private fun validate(profiles: List<Profile>) {
        require(profiles.isNotEmpty() && profiles.map(Profile::id).distinct().size == profiles.size)
        profiles.forEach { require(it.id.matches(Regex("[a-zA-Z0-9_-]{1,64}"))); checkedName(it.name) }
    }
    companion object {
        const val MAX_NAME_LENGTH = 80
        fun checkedName(value: String): String = value.trim().also {
            require(it.isNotEmpty() && it.length <= MAX_NAME_LENGTH && it.none(Char::isISOControl))
        }
    }
}
