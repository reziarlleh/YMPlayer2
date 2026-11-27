package dev.petrov.ymplayer2

import android.content.*
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import dev.petrov.ymplayer2.shell.readPublicArtwork
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

internal class CarArtwork(private val app: PlayerApplication) {
    private data class Image(val owner: String, val source: String)
    private val images = LruCache<String, Image>(640)
    private val base = Uri.parse("content://${app.packageName}.car-art/image")
    private var granted = false
    fun uri(source: String?): Uri? {
        if (source.isNullOrBlank()) return null
        if (!granted) {
            for (pkg in listOf("com.google.android.projection.gearhead", "com.google.android.googlequicksearchbox")) {
                app.grantUriPermission(pkg, base, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }
            granted = true
        }
        val image = Image(app.carCatalog.owner, source)
        val key = UUID.nameUUIDFromBytes((image.owner + "\u0000" + source).toByteArray()).toString()
        images.put(key, image)
        return base.buildUpon().appendPath(key).build()
    }
    fun bitmap(uri: Uri): Bitmap? {
        val image = images.get(uri.lastPathSegment ?: return null) ?: return null
        if (image.owner != app.carCatalog.owner) return null
        return readPublicArtwork(app, image.source)?.takeIf { image.owner == app.carCatalog.owner }
    }
}

class CarArtworkProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/jpeg"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r" || uri.pathSegments.size != 2 || uri.pathSegments.first() != "image") throw FileNotFoundException()
        val app = context?.applicationContext as? PlayerApplication ?: throw FileNotFoundException()
        val bitmap = app.carArtwork.bitmap(uri) ?: throw FileNotFoundException()
        return openPipeHelper(uri, "image/jpeg", null, bitmap) { pipe, _, _, _, _ ->
            try { ParcelFileDescriptor.AutoCloseOutputStream(pipe).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
            catch (_: IOException) { /* Host stopped reading. */ }
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
