package dev.petrov.ymplayer2

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.petrov.ymplayer2.designsystem.skin.*
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class SkinPackageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun manifest(id: String = "custom") = JSONObject().put("schemaVersion", 1).put("id", id).put("name", "Мой скин").put("author", "Тест")
    private fun archive(manifest: JSONObject, extra: Map<String, ByteArray> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            (mapOf("manifest.json" to manifest.toString().toByteArray()) + extra).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
    private fun rejected(bytes: ByteArray) {
        try { SkinPackageReader.readBytes(bytes); fail("Package must be rejected") }
        catch (expected: SkinPackageException) { assertFalse(expected.message.isNullOrBlank()) }
    }
    @Test fun partialPaletteAndVectorInheritOtherRoles() {
        val m = manifest().put("dark", JSONObject().put("primary", "#7FABC5"))
            .put("icons", JSONObject().put("PLAY", "icons/play.json"))
        val data = """{"width":24,"height":24,"paths":[{"data":"M 4 3 L 20 12 L 4 21 Z"}]}""".toByteArray()
        val parsed = SkinPackageReader.readBytes(archive(m, mapOf("icons/play.json" to data)))
        assertEquals(Color(0xFF7FABC5), parsed.skin.dark.primary)
        assertEquals(PrismSkin.light, parsed.skin.light)
        assertEquals(PrismSkin.dark.background, parsed.skin.dark.background)
        assertNotSame(PrismIcons[UiIcon.PLAY], parsed.skin.icon(UiIcon.PLAY))
        assertSame(PrismIcons[UiIcon.PAUSE], parsed.skin.icon(UiIcon.PAUSE))
    }
    @Test fun bundledThemesAreActualValidExternalPackages() {
        for (name in listOf("harbor", "olive", "silver")) {
            val skin = context.assets.open("skins/$name.ymskin").use(SkinPackageReader::read).skin
            assertEquals(name, skin.id)
            assertNotEquals(skin.dark.primary, skin.light.primary)
            assertEquals(setOf(UiIcon.PLAY, UiIcon.PAUSE), skin.icons.keys)
        }
    }
    @Test fun incompatibleUnknownAndMalformedFieldsAreRejected() {
        rejected(archive(manifest().put("schemaVersion", 2)))
        rejected(archive(manifest("prism")))
        rejected(archive(manifest().put("dark", JSONObject().put("primarry", "#ffffff"))))
        rejected(archive(manifest().put("light", JSONObject().put("primary", "https://example.org"))))
        rejected(archive(manifest().put("icons", JSONObject().put("PLAY", "missing.json"))))
        rejected(archive(manifest().put("author", "")))
        rejected(byteArrayOf(1, 2, 3, 4))
    }
    @Test fun unsafeArchivePathsUnusedFilesAndDuplicatesAreRejected() {
        for (name in listOf("../evil.json", "/evil.json", "a\\evil.json", "a/./evil.json", "extra.json")) rejected(archive(manifest(), mapOf(name to byteArrayOf(1))))
        val bytes = archive(manifest(), mapOf("firstx.json" to byteArrayOf(1), "second.json" to byteArrayOf(2)))
        // ZIP entry names are equal length; changing both local/central names leaves data CRCs intact.
        val duplicate = String(bytes, Charsets.ISO_8859_1).replace("second.json", "firstx.json").toByteArray(Charsets.ISO_8859_1)
        rejected(duplicate)
    }
    @Test fun compressedOversizeAndBadVectorAreRejected() {
        rejected(ByteArray(SkinPackageReader.MAX_ARCHIVE + 1))
        rejected(archive(manifest(), mapOf("large.json" to ByteArray(256 * 1024 + 1))))
        val m = manifest().put("icons", JSONObject().put("PLAY", "play.json"))
        for (data in listOf("""{"width":0,"height":24,"paths":[]}""", """{"width":24,"height":24,"paths":[{"data":"M 1e99 0 L 2 2"}]}""", """{"width":24,"height":24,"paths":[{"data":"M 0 0 X 1 2"}]}""")) {
            rejected(archive(m, mapOf("play.json" to data.toByteArray())))
        }
    }
    @Test fun importPreviewApplyRestartCorruptionAndRemovalAreIsolated() = runBlocking {
        val store = "skin-test-${System.nanoTime()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val directory = File(context.filesDir, store)
        val source = File(context.cacheDir, "$store.ymskin")
        source.writeBytes(archive(manifest().put("dark", JSONObject().put("primary", "#ABCDEF"))))
        try {
            val repo = SkinRepository(context, scope, store)
            withTimeout(10000) { repo.state.first { it.ready } }
            assertEquals(4, repo.state.value.choices.size)
            repo.inspect(Uri.fromFile(source)).join()
            assertEquals("prism", repo.state.value.active.id)
            assertEquals("custom", repo.state.value.preview?.skin?.id)
            repo.cancelPreview().join()
            assertNull(repo.state.value.preview)
            repo.inspect(Uri.fromFile(source)).join(); repo.applyPreview().join()
            assertEquals("custom", repo.state.value.active.id)
            repo.remove("../outside").join()
            assertEquals("custom", repo.state.value.active.id)
            val restored = SkinRepository(context, scope, store)
            withTimeout(10000) { restored.state.first { it.ready } }
            assertEquals(Color(0xFFABCDEF), restored.state.value.active.dark.primary)
            restored.inspect(Uri.fromFile(source)).join()
            assertNotNull(restored.state.value.preview)
            source.writeText("broken")
            restored.inspect(Uri.fromFile(source)).join()
            assertEquals("custom", restored.state.value.active.id)
            assertNotNull(restored.state.value.issue)
            assertNull(restored.state.value.preview)
            File(directory, "custom.ymskin").writeText("broken saved package")
            val fallback = SkinRepository(context, scope, store)
            withTimeout(10000) { fallback.state.first { it.ready } }
            assertEquals("prism", fallback.state.value.active.id)
            assertNotNull(fallback.state.value.issue)
            repo.restore().join(); repo.remove("custom").join()
            assertFalse(File(directory, "custom.ymskin").exists())
        } finally {
            scope.cancel(); directory.deleteRecursively(); source.delete(); context.getSharedPreferences(store, 0).edit().clear().commit()
        }
    }
}
