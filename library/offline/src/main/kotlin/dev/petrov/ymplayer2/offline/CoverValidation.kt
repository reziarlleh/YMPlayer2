package dev.petrov.ymplayer2.offline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal const val MAX_COVER_BYTES = 8 * 1024 * 1024

internal suspend fun InputStream.readCoverBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(32 * 1024)
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = read(buffer)
        if (read < 0) break
        if (output.size() + read > MAX_COVER_BYTES) throw IOException("Artwork too large")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

/** JPEG and PNG are understood by older players that display ID3 front covers. */
internal fun mp3Picture(bytes: ByteArray): Mp3Picture? {
    if (bytes.size !in 1..MAX_COVER_BYTES || !completeContainer(bytes)) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val mime = bounds.outMimeType
    val dimension = maxOf(bounds.outWidth, bounds.outHeight)
    if ((mime == "image/jpeg" || mime == "image/png") && bytes.size <= 1024 * 1024 && dimension <= 1024) {
        return Mp3Picture(mime, bytes)
    }
    var sample = 1
    while (dimension / sample > 1024) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return try {
        val output = ByteArrayOutputStream()
        if (bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output) && output.size() <= 1024 * 1024)
            Mp3Picture("image/jpeg", output.toByteArray()) else null
    } finally { bitmap.recycle() }
}

internal fun embeddedPicture(context: Context, audio: Uri): ByteArray? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, audio)
        retriever.embeddedPicture?.takeIf { it.size in 1..MAX_COVER_BYTES }
    } catch (_: Exception) { null }
    finally { runCatching { retriever.release() } }
}

/** Validate genuine cached artwork without allocating its full decoded dimensions. */
internal fun validCover(context: Context, file: StorageNode): Boolean = try {
    if (Build.VERSION.SDK_INT >= 28) Api28Cover.decode(context, file)
    else {
        val bytes = file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(chunk); if (read < 0) break
                if (output.size() + read > MAX_COVER_BYTES) throw IOException("Artwork too large")
                output.write(chunk, 0, read)
            }
            output.toByteArray()
        }
        // BitmapFactory may return a partial JPEG/GIF. Require the complete
        // encoded container as well as an actually decodable, bounded bitmap.
        if (!completeContainer(bytes)) throw IOException("Incomplete artwork")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Invalid artwork")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw IOException("Invalid artwork")
        bitmap.recycle()
    }
    true
} catch (_: Exception) { false }

private fun completeContainer(bytes: ByteArray): Boolean {
    fun prefix(vararg values: Int) = bytes.size >= values.size && values.indices.all { bytes[it].toInt() and 255 == values[it] }
    fun suffix(vararg values: Int) = bytes.size >= values.size && values.indices.all { bytes[bytes.size - values.size + it].toInt() and 255 == values[it] }
    if (prefix(0xff, 0xd8)) return suffix(0xff, 0xd9)
    if (prefix(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        return suffix(0, 0, 0, 0, 0x49, 0x45, 0x4e, 0x44, 0xae, 0x42, 0x60, 0x82)
    if (prefix(0x47, 0x49, 0x46, 0x38)) return suffix(0x3b)
    if (prefix(0x52, 0x49, 0x46, 0x46)) {
        if (bytes.size < 12 || String(bytes, 8, 4, Charsets.US_ASCII) != "WEBP") return false
        val declared = (0..3).fold(0L) { value, index -> value or ((bytes[4 + index].toLong() and 255) shl (index * 8)) }
        return declared + 8 == bytes.size.toLong()
    }
    return bytes.isNotEmpty() // Other BitmapFactory-supported formats still need successful decoding.
}

@RequiresApi(28)
private object Api28Cover {
    fun decode(context: Context, file: StorageNode) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, file.uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setOnPartialImageListener { false }
            decoder.setTargetSampleSize(((maxOf(info.size.width, info.size.height) + 511) / 512).coerceAtLeast(1))
        }.recycle()
    }
}
