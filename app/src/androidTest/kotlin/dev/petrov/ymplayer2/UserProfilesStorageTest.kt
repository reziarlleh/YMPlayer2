package dev.petrov.ymplayer2

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.designsystem.InterfaceScalePreferences
import dev.petrov.ymplayer2.designsystem.ThemePreferences
import dev.petrov.ymplayer2.designsystem.skin.SkinRepository
import dev.petrov.ymplayer2.library.loadUserProfiles
import dev.petrov.ymplayer2.localization.AppLanguages
import dev.petrov.ymplayer2.yandex.KeystoreAccountStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UserProfilesStorageTest {
    private class Isolated(base: Context) : ContextWrapper(base) {
        private val prefix = "profiles280-${UUID.randomUUID()}"
        val root = File(base.filesDir, prefix).apply { mkdirs() }
        private val preferences = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
            val key = "$prefix-$name"; preferences += key
            return baseContext.getSharedPreferences(key, mode)
        }
        fun close() { preferences.forEach { baseContext.getSharedPreferences(it, MODE_PRIVATE).edit().clear().commit() }; root.deleteRecursively() }
    }
    private fun fixture(action: suspend (Isolated) -> Unit) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val context = Isolated(app)
        try { action(context) } finally { AppLanguages.initialize(app); context.close() }
    }

    @Test fun freshInstallHasOneUserAndNewUsersSurviveReload() = fixture { context ->
        val users = loadUserProfiles(context)
        assertEquals(listOf("owner"), users.state.value.map(Profile::id))
        val wife = users.create("Жена")
        val children = users.create("Дети")
        val reloaded = loadUserProfiles(context)
        assertEquals(listOf("owner", wife.id, children.id), reloaded.state.value.map(Profile::id))
        assertTrue(reloaded.state.value.none(Profile::guest))
    }

    @Test fun oldSlotsAndEncryptedAccountsKeepTheirIdentityAfterMigrationAndRename() = fixture { context ->
        val accounts = KeystoreAccountStore(context)
        accounts.write("road", AccountSession(YandexAccount("fixture-account", "Жена"), OAuthCredentials("fixture", null, null)))
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().putString("profile", "road")
            .putString("queue:road", "saved queue marker").commit()
        val users = loadUserProfiles(context)
        assertEquals(listOf("owner", "road", "guest"), users.state.value.map(Profile::id))
        assertTrue(users.state.value.none(Profile::guest))
        users.rename("road", "Жена")
        assertEquals("Жена", loadUserProfiles(context).state.value.first { it.id == "road" }.name)
        assertEquals("fixture-account", accounts.read("road")?.account?.id)
        assertEquals("saved queue marker", context.getSharedPreferences("playback", Context.MODE_PRIVATE).getString("queue:road", null))
        accounts.write("road", null)
    }

    @Test fun damagedUserListIsPreservedAndCannotBeOverwrittenByEditing() = fixture { context ->
        val file = File(context.filesDir, "user-profiles.json")
        file.writeText("{broken")
        val users = loadUserProfiles(context)
        assertFalse(users.writable)
        assertTrue(runCatching { users.create("Жена") }.isFailure)
        assertEquals("{broken", file.readText())
    }

    @Test fun createdUsersRestoreTheirOwnEncryptedSessionsAndRenamingDoesNotRequireSignIn() = fixture { context ->
        val users = loadUserProfiles(context)
        val other = users.create("Жена")
        val store = KeystoreAccountStore(context)
        store.write("owner", AccountSession(YandexAccount("a", "Я"), OAuthCredentials("fixture-a", null, null)))
        store.write(other.id, AccountSession(YandexAccount("b", "Жена"), OAuthCredentials("fixture-b", null, null)))
        val api = object : DeviceAuthApi {
            override val configured = true
            override suspend fun requestCode(profileId: String): DeviceChallenge = error("No new sign-in expected")
            override suspend fun poll(code: DeviceChallenge): TokenPoll = error("No new sign-in expected")
            override suspend fun account(credentials: OAuthCredentials): YandexAccount = error("Saved account is complete")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val auth = AccountAuth({ users.state.value }, api, store, scope)
            withContext(Dispatchers.Main.immediate) { auth.activate("owner") }
            withTimeout(10_000) { auth.state.first { it.phase == AuthPhase.SIGNED_IN } }
            assertEquals("a", auth.state.value.account?.id)
            withContext(Dispatchers.Main.immediate) { auth.activate(other.id) }
            withTimeout(10_000) { auth.state.first { it.profileId == other.id && it.phase == AuthPhase.SIGNED_IN } }
            assertEquals("b", auth.state.value.account?.id)
            assertTrue(runCatching { auth.withSession("owner") { "wrong user" } }.isFailure)
            users.rename(other.id, "Супруга")
            val restoredUsers = loadUserProfiles(context)
            val restoredAuth = AccountAuth({ restoredUsers.state.value }, api, store, scope)
            withContext(Dispatchers.Main.immediate) { restoredAuth.activate(other.id) }
            withTimeout(10_000) { restoredAuth.state.first { it.phase == AuthPhase.SIGNED_IN } }
            assertEquals("b", restoredAuth.state.value.account?.id)
            assertEquals("Супруга", restoredUsers.state.value.first { it.id == other.id }.name)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            store.write("owner", null); store.write(other.id, null)
        }
    }

    @Test fun languageThemeAndScaleBelongToTheSelectedUserIncludingLegacyDefaults() = fixture { context ->
        val active = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        val appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        appearance.edit().putString("mode", "system").putInt("interface_scale_percent", 120).commit()
        active.edit().putString("profile", "owner").commit()
        val scale = InterfaceScalePreferences(context)
        assertEquals("system", ThemePreferences(context).read())
        assertEquals(120, scale.read())
        AppLanguages.initialize(context)
        AppLanguages.select("en"); ThemePreferences(context).save("light"); scale.save(150)
        active.edit().putString("profile", "wife").commit()
        AppLanguages.refresh()
        assertEquals("system", ThemePreferences(context).read())
        assertEquals(120, scale.read())
        AppLanguages.select("ru"); ThemePreferences(context).save("dark"); scale.save(80)
        active.edit().putString("profile", "owner").commit()
        AppLanguages.refresh()
        assertEquals("en", AppLanguages.state.value.selected)
        assertEquals("light", ThemePreferences(context).read())
        assertEquals(150, scale.read())
        active.edit().putString("profile", "wife").commit()
        AppLanguages.refresh()
        assertEquals("ru", AppLanguages.state.value.selected)
        assertEquals("dark", ThemePreferences(context).read())
        assertEquals(80, scale.read())
    }

    @Test fun selectingAnotherUserRestoresTheirSkinWithoutChangingTheFirstUser() = fixture { context ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val active = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        active.edit().putString("profile", "owner").commit()
        val skins = SkinRepository(context, scope)
        try {
            withTimeout(20_000) { skins.state.first { it.ready && !it.busy } }
            val alternate = skins.state.value.choices.first { it.skin.id != "prism" }.skin.id
            skins.preview(alternate).join(); skins.applyPreview().join()
            assertEquals(alternate, skins.state.value.active.id)
            active.edit().putString("profile", "wife").commit()
            withTimeout(10_000) { skins.state.first { it.active.id == "prism" } }
            active.edit().putString("profile", "owner").commit()
            withTimeout(10_000) { skins.state.first { it.active.id == alternate } }
            assertEquals(alternate, context.getSharedPreferences("skins", Context.MODE_PRIVATE).getString("active:owner", null))
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
