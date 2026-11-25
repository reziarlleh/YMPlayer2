package dev.petrov.ymplayer2.offline

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException
import java.io.IOException

/** A read-only image stream for existing artwork consumers. The image lives in the MP3. */
class OfflineArtworkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = "image/*"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r" || uri.path != "/cover") throw FileNotFoundException()
        val app = context ?: throw FileNotFoundException()
        val audio = uri.getQueryParameter("audio")?.let(Uri::parse)
            ?.takeIf { (it.scheme == "file" || it.scheme == "content") && it.authority != uri.authority }
            ?: throw FileNotFoundException()
        val image = embeddedPicture(app, audio) ?: throw FileNotFoundException("No cached cover")
        return openPipeHelper(uri, "image/*", null, image) { pipe, _, _, _, bytes ->
            try { ParcelFileDescriptor.AutoCloseOutputStream(pipe).use { it.write(bytes) } }
            catch (_: IOException) { /* The image consumer closed its pipe. */ }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    internal companion object {
        fun coverUri(context: Context, audio: Uri): String = Uri.Builder().scheme("content")
            .authority(context.packageName + ".offline-artwork").appendPath("cover")
            .appendQueryParameter("audio", audio.toString()).build().toString()

        fun isCoverUri(context: Context, uri: String?): Boolean =
            uri != null && Uri.parse(uri).authority == context.packageName + ".offline-artwork"
    }
}
