package dev.petrov.ymplayer2.shell

import dev.petrov.ymplayer2.localization.*

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import dev.petrov.ymplayer2.core.Track
import dev.petrov.ymplayer2.designsystem.FallbackArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.io.ByteArrayOutputStream
import javax.net.ssl.HttpsURLConnection

private val coverMemory = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}

@Composable internal fun TrackArtwork(track: Track?, modifier: Modifier = Modifier) {
    PublicArtwork(track?.artworkUri, tr(Msg.msg_70daf92e717a, track?.title.orEmpty()), modifier.clip(MaterialTheme.shapes.large), ContentScale.Crop, "track_artwork", track?.artworkRevision) {
        FallbackArtwork(track?.tint ?: 0, modifier.testTag("artwork_placeholder"))
    }
}

@Composable internal fun PublicArtwork(uri: String?, label: String, modifier: Modifier, contentScale: ContentScale,
    tag: String, revision: String? = null, placeholder: @Composable () -> Unit) {
    val context = LocalContext.current
    // Key the entire state: a previous track's cover is never shown during a new read.
    key(uri, revision) {
        val bitmap by produceState<Bitmap?>(null, uri, revision) {
            value = if (uri == null) null else withContext(Dispatchers.IO) {
                val cacheKey = "$uri\u0000${revision.orEmpty()}"
                coverMemory.get(cacheKey) ?: runCatching {
                    val parsed = Uri.parse(uri)
                    when (parsed.scheme) {
                        "file", "content", "android.resource" -> context.contentResolver.openInputStream(parsed)?.use { input ->
                            decodeCover(readCoverBytes(input, 8 * 1024 * 1024))
                        }
                        else -> readOnlineCover(uri)
                    }
                }.getOrNull()?.also { coverMemory.put(cacheKey, it) }
            }
        }
        if (bitmap == null) placeholder()
        else Image(bitmap!!.asImageBitmap(), label, modifier.testTag(tag), contentScale = contentScale)
    }
}

private fun readCoverBytes(input: java.io.InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (output.size() + count > limit) throw java.io.IOException("Artwork exceeds the image limit")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
private fun decodeCover(bytes: ByteArray): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    options.inJustDecodeBounds = false
    while (options.outWidth / options.inSampleSize.coerceAtLeast(1) > 800 || options.outHeight / options.inSampleSize.coerceAtLeast(1) > 800)
        options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/** Public cover only, no credentials or persistent audio/image download. */
private fun readOnlineCover(uri: String): Bitmap? {
    val url = URL(uri)
    if (url.protocol != "https" || !url.host.lowercase().endsWith(".yandex.net") || url.userInfo != null || url.port !in listOf(-1, 443)) return null
    val connection = url.openConnection() as HttpsURLConnection
    try {
        connection.connectTimeout = 8000; connection.readTimeout = 8000; connection.instanceFollowRedirects = false
        if (connection.responseCode != 200) return null
        return connection.inputStream.use { decodeCover(readCoverBytes(it, 2 * 1024 * 1024)) }
    } finally { connection.disconnect() }
}
