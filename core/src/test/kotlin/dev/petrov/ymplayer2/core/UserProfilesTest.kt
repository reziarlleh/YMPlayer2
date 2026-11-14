package dev.petrov.ymplayer2.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class UserProfilesTest {
    @Test fun losingTheSignInKeepsTheLastYandexNameButNeverPublishesAnUnfinishedConnection() = runTest {
        val users = UserProfiles(listOf(UserProfiles.localProfile()), {}, newId = { "driver" })
        val draft = users.createYandex()
        users.updateAccount(draft.id, null, connected = true)
        assertTrue(users.state.value.last().provisional)
        users.updateAccountName(draft.id, "Мария")
        users.updateAccount(draft.id, null, connected = false)
        val saved = users.state.value.last()
        assertEquals("Мария", saved.name)
        assertFalse(saved.provisional)
        assertFalse(saved.accountConnected)
    }
    @Test fun builtInLocalProfileCannotBeRenamedDeletedOrGivenAnAccountName() = runTest {
        val local = UserProfiles.localProfile()
        var cleaned = false
        val users = UserProfiles(listOf(local, Profile("driver", "Яндекс", "")), {}, { _, _ -> cleaned = true })
        assertTrue(runCatching { users.remove(local.id) }.isFailure)
        users.updateAccountName(local.id, "Имя аккаунта")
        assertEquals(local, users.state.value.first())
        assertFalse(local.canSignIn)
        assertFalse(cleaned)
    }
    @Test fun aConnectionStaysProvisionalUntilYandexReturnsItsName() = runTest {
        val users = UserProfiles(listOf(UserProfiles.localProfile()), {}, newId = { "driver" })
        val profile = users.createYandex()
        assertTrue(profile.canSignIn)
        assertFalse(profile.userNamed)
        assertTrue(profile.provisional)
        users.updateAccount(profile.id, null, connected = true)
        assertTrue(users.state.value.last().provisional)
        assertFalse(users.state.value.last().accountConnected)
        users.updateAccountName(profile.id, "  Мария\nПетрова  ")
        assertEquals("Мария Петрова", users.state.value.last().name)
        assertFalse(users.state.value.last().provisional)
        assertTrue(users.state.value.last().accountConnected)
        users.updateAccountName(profile.id, "Мария")
        assertEquals("Мария", users.state.value.last().name)
        users.remove(profile.id)
        users.updateAccountName(profile.id, "Поздний ответ")
        assertEquals(listOf(UserProfiles.localProfile()), users.state.value)
    }
    @Test fun repeatedAccountMetadataDoesNotWriteOrRecreateRemovedProfiles() = runTest {
        var writes = 0
        val users = UserProfiles(listOf(UserProfiles.localProfile()), { writes++ }, newId = { "driver" })
        val profile = users.createYandex()
        users.updateAccountName(profile.id, "Имя")
        val savedWrites = writes
        users.updateAccountName(profile.id, "Имя")
        users.updateAccountName("missing", "Другое")
        users.updateAccountName(profile.id, " \n ")
        assertEquals(savedWrites, writes)
    }
    private val owner = Profile("owner", "Пользователь 1", "", defaultNumber = 1)

    @Test fun accountNamesReplaceOldAliasesWithoutChangingLegacyIdentity() = runTest {
        var disk = listOf(owner)
        val users = UserProfiles(disk, { disk = it })
        users.updateAccountName("owner", "  Семья / Müller  ")
        assertEquals("owner", disk.single().id)
        assertEquals("Семья / Müller", disk.single().name)
        assertNull(disk.single().defaultNumber)
        assertFalse(disk.single().userNamed)
        val aliased = UserProfiles(listOf(Profile("wife", "Мама", "", userNamed = true)), {})
        aliased.updateAccountName("wife", "Мария")
        assertEquals("Мария", aliased.state.value.single().name)
    }
    @Test fun failedWriteCannotPublishAnUnsavedProfileOrRename() = runTest {
        val users = UserProfiles(listOf(owner), { throw IOException() }, newId = { "driver" })
        assertTrue(runCatching { users.create("Водитель") }.isFailure)
        assertTrue(runCatching { users.updateAccountName("owner", "Жена") }.isFailure)
        assertEquals(listOf(owner), users.state.value)
    }
    @Test fun concurrentAddsAreSerializedInsteadOfOverwritingEachOther() = runTest {
        var sequence = 0
        var disk = listOf(owner)
        val users = UserProfiles(disk, { delay(50); disk = it }, newId = { "user-${++sequence}" })
        val first = async { users.create("Жена") }
        val second = async { users.create("Дети") }
        first.await(); second.await()
        assertEquals(listOf("owner", "user-1", "user-2"), disk.map(Profile::id))
        assertEquals(disk, users.state.value)
    }
    @Test fun cancellingTheCallingScreenCannotSplitDurableAndVisibleState() = runTest {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var disk = listOf(owner)
        val users = UserProfiles(disk, { entered.complete(Unit); gate.await(); disk = it }, newId = { "driver" })
        val mutation = launch { users.create("Водитель") }
        entered.await(); assertTrue(users.busy.value)
        mutation.cancel(); gate.complete(Unit); mutation.join()
        assertFalse(users.busy.value)
        assertEquals(disk, users.state.value)
        assertEquals(2, disk.size)
    }
    @Test fun lastUserCannotBeRemovedAndMustNotTriggerCleanup() = runTest {
        var cleaned = false
        val users = UserProfiles(listOf(owner), {}, { _, _ -> cleaned = true })
        assertTrue(runCatching { users.remove("owner") }.isFailure)
        assertFalse(cleaned)
        assertEquals(listOf(owner), users.state.value)
    }
    @Test fun failedCleanupKeepsTheUserListedForRetry() = runTest {
        val initial = listOf(owner, Profile("wife", "Жена", ""))
        val users = UserProfiles(initial, {}, { _, _ -> throw IOException() })
        assertTrue(runCatching { users.remove("owner") }.isFailure)
        assertEquals(initial, users.state.value)
    }
    @Test fun removingOwnerSelectsARealRemainingIdentityWithoutInventingOwner() = runTest {
        var replacement: String? = null
        val users = UserProfiles(listOf(owner, Profile("driver", "Водитель", "")), {}, { _, next -> replacement = next })
        users.remove("owner")
        assertEquals("driver", replacement)
        assertEquals(listOf("driver"), users.state.value.map(Profile::id))
    }
    @Test fun namesCannotBeEmptyOrContainControlCharacters() = runTest {
        val users = UserProfiles(listOf(owner), {})
        listOf("  ", "Тест\nДругой", "a".repeat(81)).forEach { name ->
            assertTrue(runCatching { users.create(name) }.isFailure)
        }
        assertEquals(1, users.state.value.size)
    }
}
