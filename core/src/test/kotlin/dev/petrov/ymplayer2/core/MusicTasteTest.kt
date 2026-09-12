package dev.petrov.ymplayer2.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicTasteTest {
    private class Api : MusicTasteApi {
        val lists = mutableMapOf<Pair<String, TasteKind>, TasteList>()
        val writes = mutableListOf<Triple<String, TasteTarget, TasteAction>>()
        var delayWrite: suspend () -> Unit = {}
        var failRead = false
        override suspend fun taste(profileId: String, kind: TasteKind): TasteList {
            if (failRead) throw MusicException(MusicFailure.NETWORK)
            return lists[profileId to kind] ?: TasteList()
        }
        override suspend fun react(profileId: String, target: TasteTarget, action: TasteAction) {
            writes += Triple(profileId, target, action); delayWrite()
            val old = lists[profileId to target.kind] ?: TasteList()
            lists[profileId to target.kind] = when (action) {
                TasteAction.LIKE -> old.copy(liked = old.liked + target.key, blocked = old.blocked - target.key)
                TasteAction.UNLIKE -> old.copy(liked = old.liked - target.key)
                TasteAction.BLOCK -> old.copy(liked = old.liked - target.key, blocked = old.blocked + target.key)
                TasteAction.UNBLOCK -> old.copy(blocked = old.blocked - target.key)
            }
        }
    }
    private fun TestScope.auth(): AccountAuth = AccountAuth(DemoCatalog().profiles, object : DeviceAuthApi {
        override val configured = true
        override suspend fun requestCode(profileId: String) = error("unused")
        override suspend fun poll(code: DeviceChallenge) = error("unused")
        override suspend fun account(credentials: OAuthCredentials) = error("unused")
    }, object : AccountStore {
        override suspend fun read(profileId: String) = AccountSession(YandexAccount(profileId, profileId), OAuthCredentials("fixture", null, null))
        override suspend fun write(profileId: String, session: AccountSession?) = Unit
    }, backgroundScope).also { it.activate("owner"); runCurrent() }

    @Test fun sameNumericIdStillHasThreeIndependentFavouriteCollections() = runTest {
        val api = Api(); val taste = MusicTaste(auth(), api, backgroundScope); runCurrent()
        for (kind in listOf(TasteKind.ARTIST, TasteKind.ALBUM)) { taste.react(TasteTarget(kind, "7", "Same"), TasteAction.LIKE); runCurrent() }
        assertEquals(setOf("7"), taste.state.value.shelf(TasteKind.ARTIST).list.liked)
        assertEquals(setOf("7"), taste.state.value.shelf(TasteKind.ALBUM).list.liked)
        assertTrue(taste.state.value.shelf(TasteKind.TRACK).list.liked.isEmpty())
        assertTrue(api.writes.none { it.second.kind == TasteKind.TRACK })
        taste.react(TasteTarget(TasteKind.ALBUM, "7", "Same"), TasteAction.BLOCK); runCurrent()
        assertEquals(2, api.writes.size)
    }
    @Test fun unlikeAndUnblockDoNotTurnIntoTheOppositeReaction() = runTest {
        val api = Api(); val taste = MusicTaste(auth(), api, backgroundScope); runCurrent()
        val track = TasteTarget(TasteKind.TRACK, "1:7", "Song")
        for (action in listOf(TasteAction.LIKE, TasteAction.UNLIKE, TasteAction.BLOCK, TasteAction.UNBLOCK)) { taste.react(track, action); runCurrent() }
        assertEquals(TasteList(), taste.state.value.shelf(TasteKind.TRACK).list)
        assertEquals(listOf(TasteAction.LIKE, TasteAction.UNLIKE, TasteAction.BLOCK, TasteAction.UNBLOCK), api.writes.map { it.third })
    }
    @Test fun artistBlockUsesEveryParticipantIdAndNeverNames() {
        val track = Track("yandex:1:7", "Song", "Identical, B", "Album", Source.YANDEX, 10, false,
            artists = listOf(ArtistRef("5", "Identical"), ArtistRef("6", "B")))
        assertFalse(TasteList().allows(track, TasteList(blocked = setOf("6"))))
        assertTrue(TasteList().allows(track, TasteList(blocked = setOf("8"))))
        assertFalse(TasteList(blocked = setOf("1")).allows(track, TasteList()))
    }
    @Test fun busyTargetCannotSubmitTwoWritesOrShowOptimisticHeart() = runTest {
        val api = Api().apply { delayWrite = { delay(1000) } }
        val taste = MusicTaste(auth(), api, backgroundScope); runCurrent()
        val track = TasteTarget(TasteKind.TRACK, "1", "Song")
        taste.react(track, TasteAction.LIKE); taste.react(track, TasteAction.BLOCK); runCurrent()
        assertTrue(taste.state.value.shelf(TasteKind.TRACK).list.liked.isEmpty())
        assertTrue(taste.state.value.shelf(TasteKind.TRACK).busy)
        advanceTimeBy(1001); runCurrent()
        assertEquals(1, api.writes.size); assertEquals(setOf("1"), taste.state.value.shelf(TasteKind.TRACK).list.liked)
    }
    @Test fun delayedMutationCannotLeakIntoAnotherProfile() = runTest {
        val auth = auth(); val api = Api().apply { delayWrite = { withContext(NonCancellable) { delay(1000) } } }
        val taste = MusicTaste(auth, api, backgroundScope); runCurrent()
        taste.react(TasteTarget(TasteKind.ARTIST, "7", "Artist"), TasteAction.LIKE); runCurrent()
        auth.activate("road"); runCurrent(); advanceTimeBy(1001); runCurrent()
        assertEquals("road", taste.state.value.profileId)
        assertTrue(taste.state.value.shelf(TasteKind.ARTIST).list.liked.isEmpty())
    }
    @Test fun failedVerificationDisablesWritesUntilRefresh() = runTest {
        val api = Api(); val taste = MusicTaste(auth(), api, backgroundScope); runCurrent()
        api.failRead = true
        taste.react(TasteTarget(TasteKind.TRACK, "1", "Song"), TasteAction.LIKE); runCurrent()
        assertFalse(taste.state.value.shelf(TasteKind.TRACK).ready)
        assertNotNull(taste.state.value.shelf(TasteKind.TRACK).issue)
        api.failRead = false; taste.refresh(TasteKind.TRACK); runCurrent()
        assertEquals(setOf("1"), taste.state.value.shelf(TasteKind.TRACK).list.liked)
    }
}
