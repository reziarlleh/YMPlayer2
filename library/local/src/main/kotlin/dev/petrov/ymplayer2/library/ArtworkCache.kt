package dev.petrov.ymplayer2.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/** Small, disposable cover images only. Audio is never copied into this cache. */
internal class ArtworkCache(private val directory: File) {
    fun store(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size > 8 * 1024 * 1024) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 64_000_000) return null
        val name = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } + ".jpg"
        directory.mkdirs()
        val file = File(directory, name)
        if (!file.exists()) {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 512) inSampleSize *= 2
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val temp = File(directory, "$name.tmp")
            try {
                temp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
                check(temp.renameTo(file))
            } finally { bitmap.recycle(); temp.delete() }
        }
        file.setLastModified(System.currentTimeMillis())
        return Uri.fromFile(file).toString()
    }
    fun uri(name: String): String? = name.takeIf { it.matches(Regex("[a-f0-9]{64}\\.jpg")) }?.let { Uri.fromFile(File(directory, it)).toString() }
    fun present(uri: String?) = uri == null || File(Uri.parse(uri).path.orEmpty()).isFile
    fun trim() {
        val files = directory.listFiles()?.filter { it.extension == "jpg" }?.sortedByDescending { it.lastModified() }.orEmpty()
        var used = 0L
        files.forEach { file -> used += file.length(); if (used > 24 * 1024 * 1024) file.delete() }
    }
}
