@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package dev.petrov.ymplayer2

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.library.loadUserProfiles
import dev.petrov.ymplayer2.offline.LikedFileStore
import dev.petrov.ymplayer2.yandex.KeystoreAccountStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

private class UpgradeCacheFixture : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext) {
    val root = File(baseContext.filesDir, "portable-upgrade281").apply { mkdirs() }
    override fun getApplicationContext(): Context = this
    override fun getFilesDir() = File(root, "files").apply { mkdirs() }
    override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
    override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences("portable-upgrade281-$name", mode)
    val marker get() = File(root, "upgrade-marker.json")
    val owners = listOf(OfflineOwner("owner", "upgrade-1"), OfflineOwner("road", "upgrade-2"))
    fun hash(uri: String) = MessageDigest.getInstance("SHA-256").digest(File(Uri.parse(uri).path!!).readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun wav(): String {
        val data = 32000; val bytes = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(data + 36).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(data)
        return File(root, "source.wav").also { it.writeBytes(bytes.array()) }.toURI().toString()
    }
}

/** Run only with the archived 2.8.0 internal APK: its original LikedFileStore writes and moves the files. */
@RunWith(AndroidJUnit4::class)
class LegacyCacheProducerTest {
    @Test fun writeRealV1WithOldApplicationBeforeUpgrade() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("cacheUpgradePhase") == "producer")
        val context = UpgradeCacheFixture()
        assertTrue(context.packageManager.getPackageInfo(context.packageName, 0).versionName!!.startsWith("2.8.0"))
        context.root.deleteRecursively(); context.root.mkdirs()
        for (name in listOf("offline", "offline-location", "offline-locations-v2", "offline-downloads")) context.getSharedPreferences(name, 0).edit().clear().commit()
        File(context.filesDir, "user-profiles.json").writeText("""{"schema":3,"profiles":[{"id":"local","name":"Локальный","userNamed":false},{"id":"owner","name":"First","userNamed":false,"accountConnected":true},{"id":"road","name":"Second","userNamed":false,"accountConnected":true}]}""")
        val store = LikedFileStore(context); val source = context.wav(); val accounts = KeystoreAccountStore(context)
        for ((index, owner) in context.owners.withIndex()) {
            accounts.write(owner.profileId, AccountSession(YandexAccount(owner.accountId, "User $index"), OAuthCredentials("fixture-upgrade-only", null, null)))
            assertNotNull(store.sync(owner, Track("yandex:${index + 1}:7", "Old ${index + 1}", "Artist", "Album", Source.YANDEX, 2, false), { source }, { true }, {}).track)
        }
        val destination = File(context.root, "custom-parent").apply { mkdirs() }
        // This is deliberately the OLD public signature, shared by both app versions.
        store.move(destination.toURI().toString()) { _, _ -> }
        val marker = JSONObject().put("uid", android.os.Process.myUid()).put("firstInstallTime", context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime)
        for (owner in context.owners) {
            val track = store.catalog(owner).single()
            assertFalse(File(File(Uri.parse(track.uri).path!!).parentFile, "ymplayer2-cache.json").exists())
            marker.put(owner.profileId, JSONObject().put("uri", track.uri).put("sha256", context.hash(track.uri!!)))
        }
        context.marker.writeText(marker.toString())
    }
}

@RunWith(AndroidJUnit4::class)
class LegacyCacheConsumerTest {
    @Test fun updateRetainsAccountsProfilesAndAdoptsBothOldCustomCachesInPlace() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("cacheUpgradePhase") == "consumer")
        val context = UpgradeCacheFixture()
        assertTrue(context.packageManager.getPackageInfo(context.packageName, 0).versionName!!.startsWith("2.8.1"))
        val marker = JSONObject(context.marker.readText())
        assertEquals(marker.getInt("uid"), android.os.Process.myUid())
        assertEquals(marker.getLong("firstInstallTime"), context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime)
        val users = loadUserProfiles(context)
        assertEquals(listOf("local", "owner", "road"), users.state.value.map(Profile::id))
        val accounts = KeystoreAccountStore(context); val store = LikedFileStore(context)
        for (owner in context.owners) {
            assertEquals(owner.accountId, accounts.read(owner.profileId)!!.account!!.id)
            val track = store.catalog(owner).single(); val before = marker.getJSONObject(owner.profileId)
            assertEquals(before.getString("uri"), track.uri)
            assertEquals(before.getString("sha256"), context.hash(store.audio(owner, track.id)!!))
            val passport = File(File(Uri.parse(track.uri).path!!).parentFile, "ymplayer2-cache.json")
            assertEquals(owner.accountId, JSONObject(passport.readText()).getString("accountUid"))
            assertTrue(store.locationState(owner).external)
            store.sync(owner, track.copy(offline = false), { error("No download is permitted during upgrade") }, { true }, {})
        }
        store.move(context.owners.first(), null, null, null, { true }) { _, _ -> }
        assertFalse(store.locationState(context.owners.first()).external)
        assertTrue(store.locationState(context.owners.last()).external)
        val second = store.catalog(context.owners.last()).single()
        assertEquals(marker.getJSONObject("road").getString("uri"), second.uri)
        assertNotNull(store.audio(context.owners.last(), second.id))
    }
}
