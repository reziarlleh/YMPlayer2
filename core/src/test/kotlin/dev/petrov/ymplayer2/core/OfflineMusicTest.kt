package dev.petrov.ymplayer2.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineMusicTest {
    private fun track(id: String) = Track("yandex:$id:7", "Track $id", "Artist", "Album", Source.YANDEX, 30, false)
    private class Store : OfflineStore {
        val files = mutableMapOf<OfflineOwner, MutableMap<String, Track>>()
        var beforeSave: suspend () -> Unit = {}
        var beforeRetain: suspend () -> Unit = {}
        val downloaded = mutableListOf<Pair<OfflineOwner, String>>()
        var loads = 0
        var catalogs = 0
        var catalogGate: suspend () -> Unit = {}
        var audioReads = 0
        override suspend fun load(owner: OfflineOwner): List<Track> { loads++; return files[owner]?.values?.toList().orEmpty() }
        override suspend fun catalog(owner: OfflineOwner): List<Track> { catalogs++; catalogGate(); return files[owner]?.values?.toList().orEmpty() }
        override suspend fun sync(owner: OfflineOwner, track: Track, resolve: suspend () -> String, allowed: () -> Boolean, transfer: () -> Unit): OfflineItem {
            transfer(); beforeSave()
            if (!allowed()) return OfflineItem(null, false, false, false)
            val ready = track.copy(offline = true)
            files.getOrPut(owner) { mutableMapOf() }[track.tasteTarget().key] = ready
            downloaded += owner to track.id
            return OfflineItem(ready, false, false, true)
        }
        override suspend fun retain(owner: OfflineOwner, keys: Set<String>, allowed: () -> Boolean) { beforeRetain(); if (allowed()) files[owner]?.keys?.retainAll(keys) }
        override suspend fun audio(owner: OfflineOwner, trackId: String): String? { audioReads++; return files[owner]?.get(trackId.removePrefix("yandex:").substringBefore(':'))?.let { "file:///fixture" } }
        override suspend fun clear(owner: OfflineOwner) { files.remove(owner) }
    }
    private inner class Harness(val scope: TestScope, enabled: Boolean = true) {
        val owner = OfflineOwner("owner", "A")
        val sessions = mutableMapOf("owner" to AccountSession(YandexAccount("A", "A"), OAuthCredentials("fixture", null, null)),
            "road" to AccountSession(YandexAccount("A", "A"), OAuthCredentials("fixture", null, null)))
        val auth = AccountAuth(DemoCatalog().profiles, object : DeviceAuthApi {
            override val configured = true
            override suspend fun requestCode(profileId: String) = error("unused")
            override suspend fun poll(code: DeviceChallenge) = error("unused")
            override suspend fun account(credentials: OAuthCredentials) = error("unused")
        }, object : AccountStore {
            override suspend fun read(profileId: String) = sessions[profileId]
            override suspend fun write(profileId: String, session: AccountSession?) { if (session == null) sessions.remove(profileId) else sessions[profileId] = session }
        }, scope.backgroundScope)
        var liked = setOf("1", "2")
        var failedSnapshot = false
        var snapshots = 0
        var savedEnabled = enabled
        var gate: suspend () -> Unit = {}
        var finalKeys: Set<String>? = null
        val kinds = mutableListOf<TasteKind>()
        val taste = MusicTaste(auth, object : MusicTasteApi {
            override suspend fun taste(profileId: String, kind: TasteKind): TasteList {
                kinds += kind
                return TasteList(if (kind == TasteKind.TRACK) liked else setOf("999"))
            }
            override suspend fun react(profileId: String, target: TasteTarget, action: TasteAction) { if (target.kind == TasteKind.TRACK && action == TasteAction.UNLIKE) liked = liked - target.key }
        }, scope.backgroundScope)
        val api = object : LikedMusicApi {
            override suspend fun snapshot(profileId: String): LikedSnapshot {
                snapshots++
                val captured = liked
                gate()
                if (failedSnapshot) throw MusicException(MusicFailure.NETWORK)
                return LikedSnapshot(captured, captured.map(::track))
            }
            override suspend fun keys(profileId: String) = finalKeys ?: liked
        }
        val online = object : OnlineMusicApi {
            override suspend fun page(profileId: String, request: MusicRequest, page: Int) = error("Offline sync must not traverse artist/album/catalog pages")
            override suspend fun stream(profileId: String, trackId: String, quality: AudioQuality) = "unused"
        }
        val store = Store()
        var network = true
        val offline = OfflineMusic(auth, taste, api, online, store, scope.backgroundScope, network = { network },
            enabled = enabled, saveEnabled = { savedEnabled = it })
        init { auth.activate("owner"); scope.runCurrent() }
    }
    @Test fun onlyTrackLikesAreDownloadedAndMissingCoversDoNotBlockAudio() = runTest {
        val h = Harness(this); h.offline.sync(); runCurrent()
        assertEquals(setOf("yandex:1:7", "yandex:2:7"), h.offline.state.value.tracks.map(Track::id).toSet())
        assertEquals(2, h.offline.state.value.noCover); assertEquals(0, h.offline.state.value.audioFailures)
        assertFalse(h.offline.state.value.running)
        assertFalse(h.store.downloaded.any { "999" in it.second })
    }
    @Test fun unlikeDuringNonCancellableDownloadCannotResurrectAFile() = runTest {
        val h = Harness(this); h.store.beforeSave = { withContext(NonCancellable) { delay(1000) } }
        h.offline.sync(); runCurrent()
        h.taste.react(track("1").tasteTarget(), TasteAction.UNLIKE); runCurrent()
        advanceTimeBy(3000); runCurrent()
        assertEquals(listOf("yandex:2:7"), h.offline.state.value.tracks.map(Track::id))
        assertFalse(h.store.files[h.owner].orEmpty().containsKey("1"))
    }
    @Test fun cancellationKeepsCompletedTracksAndRejectsLatePublication() = runTest {
        val h = Harness(this)
        var saves = 0
        h.store.beforeSave = { if (++saves == 2) withContext(NonCancellable) { delay(1000) } }
        h.offline.sync(); runCurrent(); h.offline.cancel(); advanceTimeBy(2000); runCurrent()
        assertEquals(listOf("yandex:1:7"), h.offline.state.value.tracks.map(Track::id))
        assertEquals(setOf("1"), h.store.files[h.owner]?.keys)
        assertFalse(h.offline.state.value.running)
    }
    @Test fun profileSwitchSeparatesFilesEvenForSameYandexAccount() = runTest {
        val h = Harness(this); h.offline.sync(); runCurrent()
        h.auth.activate("road"); runCurrent()
        assertTrue(h.offline.state.value.tracks.isEmpty())
        assertNull(h.offline.audio("road", "yandex:1:7"))
        h.offline.clear(); runCurrent()
        assertEquals(2, h.store.files[h.owner]?.size)
    }
    @Test fun logoutAndDifferentAccountCannotSeePreviousDownloads() = runTest {
        val h = Harness(this); h.offline.sync(); runCurrent()
        h.auth.signOut(); runCurrent(); assertTrue(h.offline.state.value.tracks.isEmpty())
        h.sessions["owner"] = AccountSession(YandexAccount("B", "B"), OAuthCredentials("fixture-b", null, null))
        h.auth.activate("road"); runCurrent(); h.auth.activate("owner"); runCurrent()
        assertEquals("B", h.offline.state.value.owner?.accountId)
        assertTrue(h.offline.state.value.tracks.isEmpty()); assertNull(h.offline.audio("owner", "yandex:1:7"))
    }
    @Test fun failedSnapshotAndMissingWifiDoNotPruneExistingFiles() = runTest {
        val h = Harness(this); h.offline.sync(); runCurrent()
        h.failedSnapshot = true; h.offline.sync(); runCurrent()
        assertEquals(2, h.store.files[h.owner]?.size)
        h.failedSnapshot = false; h.network = false; h.offline.sync(); runCurrent()
        assertEquals(2, h.store.files[h.owner]?.size); assertTrue(h.offline.state.value.message!!.contains("Wi-Fi"))
    }
    @Test fun finalServerRecheckRemovesUnlikeFromAnotherClient() = runTest {
        val h = Harness(this); h.finalKeys = setOf("2"); h.liked = setOf("2")
        // Snapshot belongs to the beginning of the pass; final keys represent another client's unlike.
        h.gate = { h.liked = setOf("2") }; h.liked = setOf("1", "2")
        h.offline.sync(); runCurrent()
        assertEquals(setOf("2"), h.store.files[h.owner]?.keys)
        assertEquals(listOf("yandex:2:7"), h.offline.state.value.tracks.map(Track::id))
    }
    @Test fun delayedOldPruneCannotDeleteNewPassFiles() = runTest {
        val h = Harness(this); h.store.beforeRetain = { withContext(NonCancellable) { delay(1000) } }
        h.liked = setOf("1"); h.taste.refresh(TasteKind.TRACK); runCurrent()
        h.liked = setOf("1", "2"); h.offline.sync(); runCurrent()
        advanceTimeBy(4000); runCurrent()
        assertEquals(setOf("1", "2"), h.store.files[h.owner]?.keys)
    }
    @Test fun stoppingSyncDoesNotCancelConfirmedUnlikeCleanup() = runTest {
        val h = Harness(this); h.offline.sync(); runCurrent()
        h.store.beforeRetain = { withContext(NonCancellable) { delay(1000) } }
        h.taste.react(track("1").tasteTarget(), TasteAction.UNLIKE); runCurrent()
        h.offline.cancel(); advanceTimeBy(2000); runCurrent()
        assertEquals(setOf("2"), h.store.files[h.owner]?.keys)
        assertNull(h.offline.audio("owner", "yandex:1:7"))
    }
    @Test fun disabledCacheDoesNotReadDiskOrStartTransfersAndSurvivesRecreation() = runTest {
        val h = Harness(this, enabled = false)
        h.offline.sync(); runCurrent()
        assertEquals(0, h.snapshots); assertEquals(0, h.store.loads)
        assertNull(h.offline.audio("owner", "yandex:1:7")); assertEquals(0, h.store.audioReads)
        h.offline.setEnabled(true); runCurrent(); h.offline.sync(); runCurrent()
        assertEquals(2, h.offline.tracks("owner").size)
        h.offline.setEnabled(false); runCurrent()
        assertFalse(h.savedEnabled)
        val restored = OfflineMusic(h.auth, h.taste, h.api, h.online, h.store, backgroundScope, enabled = h.savedEnabled)
        val loads = h.store.loads
        runCurrent(); restored.sync(); runCurrent()
        assertFalse(restored.state.value.enabled); assertEquals(loads, h.store.loads)
        assertTrue(restored.tracks("owner").isEmpty())
        assertEquals(2, h.store.files[h.owner]?.size)
    }
    @Test fun disablingDuringLateDownloadRejectsPublicationAndReenableAllowsANewPass() = runTest {
        val h = Harness(this)
        h.store.beforeSave = { withContext(NonCancellable) { delay(1000) } }
        h.offline.sync(); runCurrent(); h.offline.setEnabled(false)
        advanceTimeBy(2000); runCurrent()
        assertFalse(h.offline.state.value.running); assertTrue(h.store.downloaded.isEmpty())
        assertTrue(h.offline.tracks("owner").isEmpty())
        h.store.beforeSave = {}
        h.offline.setEnabled(true); runCurrent(); h.offline.sync(); runCurrent()
        assertEquals(2, h.offline.tracks("owner").size)
    }
    @Test fun disabledCachePreservesFilesUntilExplicitClearAndCannotDecorateTracks() = runTest {
        val h = Harness(this); h.offline.sync(); runCurrent()
        h.offline.setEnabled(false); runCurrent()
        h.liked = setOf("2"); h.taste.refresh(TasteKind.TRACK); runCurrent()
        assertEquals(2, h.store.files[h.owner]?.size)
        assertFalse(h.offline.decorate("owner", track("1")).offline)
        assertNull(h.offline.audio("owner", "yandex:1:7"))
        h.offline.clear(); runCurrent()
        assertTrue(h.store.files[h.owner].isNullOrEmpty())
        assertFalse(h.offline.state.value.enabled); assertTrue(h.offline.state.value.ready)
    }
    @Test fun openingOfflineUsesLocalCatalogWithoutWaitingForWholeCacheAudit() = runTest {
        val h = Harness(this)
        assertEquals(1, h.store.catalogs)
        assertEquals(0, h.store.loads)
        val gate = CompletableDeferred<Unit>()
        h.store.catalogGate = { gate.await() }
        h.offline.setEnabled(false); h.offline.setEnabled(true); runCurrent()
        val request = async { h.offline.readyTracks("owner") }; runCurrent()
        assertFalse(request.isCompleted)
        gate.complete(Unit); runCurrent()
        assertTrue(request.await().isEmpty())
        assertEquals(0, h.store.loads)
    }
    @Test fun pendingOfflineSelectionCannotReturnPreviousProfilesFiles() = runTest {
        val h = Harness(this); h.offline.sync(); runCurrent()
        val gate = CompletableDeferred<Unit>()
        h.store.catalogGate = { gate.await() }
        h.offline.setEnabled(false); h.offline.setEnabled(true); runCurrent()
        val request = async { h.offline.readyTracks("owner") }; runCurrent()
        assertFalse(request.isCompleted)
        h.auth.activate("road"); runCurrent()
        assertTrue(request.await().isEmpty())
        gate.complete(Unit); runCurrent()
        assertTrue(h.offline.tracks("owner").isEmpty())
        h.offline.setEnabled(false); runCurrent()
        assertTrue(h.offline.readyTracks("road").isEmpty())
    }
}
