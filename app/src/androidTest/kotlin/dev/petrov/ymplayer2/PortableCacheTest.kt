@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.offline.LikedFileStore
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/** Real WAV files, original v1 sidecars, private identities lost between contexts, and real file-store IO. */
@RunWith(AndroidJUnit4::class)
class PortableCacheTest {
    private class Isolated(base: Context) : ContextWrapper(base) {
        private val prefix = "portable281-${UUID.randomUUID()}"
        val root = File(base.filesDir, prefix).apply { mkdirs() }
        private val prefs = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
            prefs += name
            return baseContext.getSharedPreferences("$prefix-$name", mode)
        }
        fun close() { prefs.forEach { getSharedPreferences(it, 0).edit().clear().commit() }; root.deleteRecursively() }
    }
    private fun fixture(block: suspend (Isolated, Isolated, File) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val first = Isolated(base); val second = Isolated(base)
        val shared = File(base.cacheDir, "portable-shared-${UUID.randomUUID()}").apply { mkdirs() }
        try { block(first, second, shared) } finally { first.close(); second.close(); shared.deleteRecursively() }
    }
    private val a = OfflineOwner("profile-a", "account-1")
    private val b = OfflineOwner("profile-b", "account-2")
    private fun hash(text: String) = hash(text.toByteArray())
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun ownerKey(owner: OfflineOwner) = hash(owner.profileId + "\u0000" + owner.accountId)
    private fun root(track: Track) = File(Uri.parse(track.uri).path!!).parentFile!!
    private fun passport(root: File) = File(root, "ymplayer2-cache.json")
    private fun wav(context: Isolated): File {
        val file = File(context.root, "source.wav")
        if (!file.exists()) {
            val bytes = 8000 * 2 * 2
            val out = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
            out.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes)
            file.writeBytes(out.array())
        }
        return file
    }
    private suspend fun seed(context: Isolated, store: LikedFileStore, owner: OfflineOwner, id: String = "1"): Track {
        val source = wav(context).toURI().toString()
        return store.sync(owner, Track("yandex:$id:7", "Title $id", "Artist", "Album", Source.YANDEX, 2, false),
            { source }, { true }, {}).track ?: error("WAV fixture must be accepted by the real decoder")
    }
    private fun v1(context: Isolated, vararg roots: File) {
        roots.forEach { assertTrue(passport(it).delete()) }
        assertTrue(context.getSharedPreferences("offline-locations-v2", 0).edit().clear().commit())
    }
    private suspend fun move(store: LikedFileStore, owner: OfflineOwner, destination: File) {
        store.move(owner, destination.toURI().toString(), null, null, { true }) { _, _ -> }
    }

    @Test fun v1PrivateMigrationKeepsBothProfilesAndDoesNotResolveAudioAgain() = fixture { context, _, _ ->
        val store = LikedFileStore(context)
        val one = seed(context, store, a); val two = seed(context, store, b, "2")
        val bytes = File(Uri.parse(one.uri).path!!).readBytes()
        v1(context, root(one), root(two))
        val upgraded = LikedFileStore(context)
        assertEquals(one.uri, upgraded.catalog(a).single().uri)
        assertArrayEquals(bytes, File(Uri.parse(upgraded.audio(a, one.id)).path!!).readBytes())
        assertEquals("account-1", JSONObject(passport(root(one)).readText()).getString("accountUid"))
        assertEquals(two.uri, upgraded.catalog(b).single().uri)
        val before = hash(passport(root(two)).readBytes())
        upgraded.sync(a, one.copy(offline = false, uri = null), { error("An intact cached track must not be downloaded") }, { true }, {})
        assertEquals(before, hash(passport(root(two)).readBytes()))
    }

    @Test fun oldSharedExternalLocationIsAdoptedIndividuallyAndMovingOnePreservesTheOther() = fixture { context, _, shared ->
        val old = LikedFileStore(context)
        val one = seed(context, old, a); val two = seed(context, old, b, "2")
        val base = File(shared, "old-cache").apply { mkdirs() }
        for ((owner, track) in listOf(a to one, b to two)) {
            root(track).copyRecursively(File(base, "v1/${ownerKey(owner)}"))
            root(track).deleteRecursively()
        }
        v1(context, File(base, "v1/${ownerKey(a)}"), File(base, "v1/${ownerKey(b)}"))
        context.getSharedPreferences("offline-location", 0).edit().putString("uri", base.toURI().toString())
            .putString("label", "old-usb").putBoolean("external", true).commit()
        val upgraded = LikedFileStore(context)
        val originalA = upgraded.catalog(a).single(); val originalB = upgraded.catalog(b).single()
        assertTrue(upgraded.locationState(a).external)
        val destination = File(shared, "new-usb").apply { mkdirs() }
        move(upgraded, a, destination)
        assertFalse(root(originalA).exists()); assertTrue(base.isDirectory)
        assertEquals(originalB.uri, upgraded.catalog(b).single().uri)
        assertNotNull(upgraded.audio(b, originalB.id))
    }

    @Test fun newProfilesDoNotInheritTheOldGlobalCustomLocation() = fixture { context, _, shared ->
        context.getSharedPreferences("offline-location", 0).edit().putString("uri", shared.toURI().toString()).putBoolean("external", true).commit()
        val store = LikedFileStore(context); val track = seed(context, store, a)
        assertTrue(root(track).canonicalPath.startsWith(context.filesDir.canonicalPath))
        assertFalse(store.locationState(a).external)
    }
    @Test fun newlyAddedProfileDoesNotNeedTheOldProfilesUnavailableUsb() = fixture { context, _, _ ->
        File(context.filesDir, "user-profiles.json").writeText("""{"profiles":[{"id":"old-user"}]}""")
        context.getSharedPreferences("offline-location", 0).edit().putString("uri", "content://missing.usb.documents/tree/root").putBoolean("external", true).commit()
        val store = LikedFileStore(context)
        val track = seed(context, store, a)
        assertFalse(store.locationState(a).external)
        assertNotNull(store.audio(a, track.id))
    }

    @Test fun copiedPortableCacheAttachesToAnotherInstallationAndNewProfileWithoutNetwork() = fixture { oldContext, clean, shared ->
        val original = seed(oldContext, LikedFileStore(oldContext), a)
        val copy = File(shared, "copied"); root(original).copyRecursively(copy)
        val owner = OfflineOwner("brand-new-profile-id", a.accountId)
        val restored = LikedFileStore(clean)
        val candidate = restored.inspectLocation(owner, copy.toURI().toString()).single()
        assertFalse(candidate.legacy)
        restored.move(owner, copy.toURI().toString(), candidate, null, { true }) { _, _ -> }
        val track = restored.catalog(owner).single()
        assertEquals(File(copy, File(Uri.parse(original.uri).path!!).name).toURI().path, Uri.parse(track.uri).path)
        assertEquals(hash(File(Uri.parse(original.uri).path!!).readBytes()), hash(File(Uri.parse(restored.audio(owner, track.id)).path!!).readBytes()))
        restored.sync(owner, original.copy(offline = false), { error("No network allowed") }, { true }, {})
    }

    @Test fun copiedLegacyImportUsesOnlyConfirmedLikesAndPreservesUnknownOriginalFiles() = fixture { oldContext, clean, shared ->
        val old = LikedFileStore(oldContext); val one = seed(oldContext, old, a); seed(oldContext, old, a, "2")
        val copy = File(shared, "legacy"); root(one).copyRecursively(copy); passport(copy).delete()
        val notes = File(copy, "user-notes.txt").apply { writeText("keep me") }
        val restored = LikedFileStore(clean); val owner = OfflineOwner("new-user", a.accountId)
        val candidate = restored.inspectLocation(owner, copy.toURI().toString()).single()
        assertTrue(candidate.legacy)
        assertTrue(runCatching { restored.move(owner, copy.toURI().toString(), candidate, null, { true }) { _, _ -> } }.isFailure)
        restored.move(owner, copy.toURI().toString(), candidate, setOf("1"), { true }) { _, _ -> }
        assertEquals(listOf(one.id), restored.catalog(owner).map { it.id })
        assertEquals("keep me", notes.readText())
        assertTrue(File(copy, hash("2") + ".audio").isFile)
        assertNotNull(restored.audio(owner, one.id))
    }

    @Test fun mismatchAndUnsupportedFormatNeverRewriteThePickedFolder() = fixture { oldContext, clean, shared ->
        val track = seed(oldContext, LikedFileStore(oldContext), a)
        val copy = File(shared, "copy"); root(track).copyRecursively(copy)
        val restored = LikedFileStore(clean)
        val original = passport(copy).readText()
        assertTrue(runCatching { restored.inspectLocation(b, copy.toURI().toString()) }.isFailure)
        assertEquals(original, passport(copy).readText())
        passport(copy).writeText(JSONObject(original).put("format", 99).toString())
        val future = passport(copy).readText()
        assertTrue(runCatching { restored.inspectLocation(a, copy.toURI().toString()) }.isFailure)
        assertEquals(future, passport(copy).readText())
    }

    @Test fun sameAccountProfilesHaveIndependentFilesAndCannotAttachEachOthersLiveRoot() = fixture { context, _, shared ->
        val other = OfflineOwner("other-user", a.accountId)
        val store = LikedFileStore(context); val one = seed(context, store, a); val two = seed(context, store, other)
        assertNotEquals(one.uri, two.uri)
        move(store, a, shared)
        val picked = root(store.catalog(a).single())
        assertTrue(runCatching { store.inspectLocation(other, picked.toURI().toString()) }.isFailure)
        assertNotNull(store.audio(other, two.id))
    }

    @Test fun disablingGloballyDefersLegacyMigrationAndForbidsMoves() = fixture { context, _, shared ->
        val track = seed(context, LikedFileStore(context), a)
        v1(context, root(track))
        context.getSharedPreferences("offline", 0).edit().putBoolean("enabled", false).commit()
        val store = LikedFileStore(context)
        assertEquals(track.uri, store.catalog(a).single().uri)
        assertFalse(passport(root(track)).exists())
        assertTrue(runCatching { move(store, a, shared) }.exceptionOrNull() is CancellationException)
        assertTrue(shared.listFiles().orEmpty().isEmpty())
        context.getSharedPreferences("offline", 0).edit().putBoolean("enabled", true).commit()
        LikedFileStore(context).catalog(a)
        assertTrue(passport(root(track)).isFile)
    }

    @Test fun interruptedCopyKeepsOriginalBindingAndRemovesOnlyItsGeneratedPartialRoot() = fixture { context, _, shared ->
        val store = LikedFileStore(context); val track = seed(context, store, a)
        val notes = File(shared, "notes.txt").apply { writeText("untouched") }
        val result = runCatching {
            store.move(a, shared.toURI().toString(), null, null, { true }) { done, _ -> if (done > 0) throw CancellationException("fixture") }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(track.uri, store.catalog(a).single().uri)
        assertEquals(listOf(notes.name), shared.listFiles().orEmpty().map { it.name })
        assertNotNull(store.audio(a, track.id))
    }

    @Test fun globalDisableDuringCopyCannotCommitTheNewLocation() = fixture { context, _, shared ->
        val store = LikedFileStore(context); val track = seed(context, store, a)
        val result = runCatching {
            store.move(a, shared.toURI().toString(), null, null, { true }) { done, _ ->
                if (done > 0) context.getSharedPreferences("offline", 0).edit().putBoolean("enabled", false).commit()
            }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(track.uri, store.catalog(a).single().uri)
        assertTrue(shared.listFiles().orEmpty().isEmpty())
    }

    @Test fun retainAndClearNeverDeleteThePortablePassportOrAnotherOwnersFiles() = fixture { context, _, _ ->
        val store = LikedFileStore(context); val one = seed(context, store, a); seed(context, store, a, "2")
        val other = seed(context, store, b, "3")
        val original = passport(root(one)).readText()
        store.retain(a, setOf("1")); assertEquals(listOf(one.id), store.catalog(a).map { it.id })
        assertEquals(original, passport(root(one)).readText())
        store.clear(a); assertTrue(store.catalog(a).isEmpty())
        assertEquals(original, passport(root(one)).readText())
        assertNotNull(store.audio(b, other.id))
    }

    @Test fun deletingProfilesRemovesPrivateRootsButLeavesPortableCustomCachesAttachable() = fixture { context, clean, shared ->
        val store = LikedFileStore(context); val private = seed(context, store, a); val external = seed(context, store, b)
        move(store, b, shared)
        val custom = root(store.catalog(b).single())
        store.removeProfile(a.profileId, a.accountId); assertFalse(root(private).exists())
        store.removeProfile(b.profileId, b.accountId); assertTrue(custom.isDirectory)
        val restored = LikedFileStore(clean); val owner = OfflineOwner("new-b", b.accountId)
        val candidate = restored.inspectLocation(owner, custom.toURI().toString()).single()
        restored.move(owner, custom.toURI().toString(), candidate, null, { true }) { _, _ -> }
        assertNotNull(restored.audio(owner, external.id))
    }

    @Test fun processDeathJournalRollsBackAnUncommittedTargetAndFinishesCommittedCleanup() = fixture { context, _, shared ->
        val store = LikedFileStore(context); val track = seed(context, store, a)
        val prefs = context.getSharedPreferences("offline-locations-v2", 0)
        val partial = File(shared, "YMPlayer2-offline-partial").apply { mkdirs(); File(this, "partial.part").writeText("pending") }
        val journal = "pending:${ownerKey(a)}"
        prefs.edit().putString(journal, JSONObject().put("uri", partial.toURI().toString()).put("source", root(track).toURI().toString()).put("fresh", true).toString()).commit()
        assertNotNull(LikedFileStore(context).audio(a, track.id)); assertFalse(partial.exists())
        val abandoned = File(shared, "old-managed-root").apply { mkdirs(); File(this, hash("1") + ".audio").writeText("old") }
        passport(root(track)).copyTo(passport(abandoned))
        prefs.edit().putString(journal, JSONObject().put("uri", root(track).toURI().toString()).put("source", abandoned.toURI().toString()).put("fresh", true).toString()).commit()
        assertNotNull(LikedFileStore(context).audio(a, track.id)); assertFalse(abandoned.exists()); assertFalse(prefs.contains(journal))
    }
    @Test fun aMovePreservesUnrelatedFilesInTheFormerCustomCacheDirectory() = fixture { context, _, shared ->
        val store = LikedFileStore(context); seed(context, store, a); move(store, a, shared)
        val old = root(store.catalog(a).single())
        val note = File(old, "personal-note.txt").apply { writeText("preserve") }
        store.move(a, null, null, null, { true }) { _, _ -> }
        assertEquals("preserve", note.readText())
        assertFalse(passport(old).exists())
        assertNotNull(store.audio(a, "yandex:1:7"))
    }
    @Test fun genuineArtworkAndItsChecksumSurviveTheMove() = fixture { context, _, shared ->
        val image = File(context.root, "source-cover.png")
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val store = LikedFileStore(context)
        val track = Track("yandex:1:7", "Artwork", "Artist", "Album", Source.YANDEX, 2, false, artworkUri = image.toURI().toString())
        val original = store.sync(a, track, { wav(context).toURI().toString() }, { true }, {}).track!!
        val expected = hash(File(Uri.parse(original.artworkUri).path!!).readBytes())
        move(store, a, shared)
        val moved = store.catalog(a).single()
        assertEquals(expected, hash(File(Uri.parse(moved.artworkUri).path!!).readBytes()))
        assertTrue(File(root(moved), File(Uri.parse(moved.artworkUri).path!!).name + ".sha256").isFile)
    }
    @Test fun mergingWithReusableTargetAudioStillCopiesMissingGenuineArtwork() = fixture { old, current, shared ->
        val previous = LikedFileStore(old)
        seed(old, previous, a); move(previous, a, shared)
        val target = previous.catalog(a).single()
        val targetAudio = File(Uri.parse(target.uri).path!!).readBytes()
        assertNull(target.artworkUri)
        val image = File(current.root, "cover.png")
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val store = LikedFileStore(current)
        val input = Track("yandex:1:7", "Title 1", "Artist", "Album", Source.YANDEX, 2, false, artworkUri = image.toURI().toString())
        val cached = store.sync(a, input, { wav(current).toURI().toString() }, { true }, {}).track!!
        val expectedCover = File(Uri.parse(cached.artworkUri).path!!).readBytes()
        val selected = previous.locationIdentity(a)!!
        val candidate = store.inspectLocation(a, selected).single()
        store.move(a, selected, candidate, null, { true }) { _, _ -> }
        val merged = store.catalog(a).single()
        assertArrayEquals(targetAudio, File(Uri.parse(merged.uri).path!!).readBytes())
        assertArrayEquals(expectedCover, File(Uri.parse(merged.artworkUri).path!!).readBytes())
    }
}
