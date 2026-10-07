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
    PublicArtwork(track?.artworkUri, tr(Msg.msg_70daf92e717a, track?.title.orEmpty()), modifier.clip(MaterialTheme.shapes.large), ContentScale.Crop, "track_artwork") {
        FallbackArtwork(track?.tint ?: 0, modifier.testTag("artwork_placeholder"))
    }
}

@Composable internal fun PublicArtwork(uri: String?, label: String, modifier: Modifier, contentScale: ContentScale,
    tag: String, placeholder: @Composable () -> Unit) {
    // Key the entire state: a previous track's cover is never shown during a new read.
    key(uri) {
        val bitmap by produceState<Bitmap?>(null, uri) {
            value = if (uri == null) null else withContext(Dispatchers.IO) {
                coverMemory.get(uri) ?: runCatching {
                    val parsed = Uri.parse(uri)
                    if (parsed.scheme == "file") BitmapFactory.decodeFile(parsed.path)
                    else readOnlineCover(uri)
                }.getOrNull()?.also { coverMemory.put(uri, it) }
            }
        }
        if (bitmap == null) placeholder()
        else Image(bitmap!!.asImageBitmap(), label, modifier.testTag(tag), contentScale = contentScale)
    }
}

/** Public cover only, no credentials or persistent audio/image download. */
private fun readOnlineCover(uri: String): Bitmap? {
    val url = URL(uri)
    if (url.protocol != "https" || !url.host.lowercase().endsWith(".yandex.net") || url.userInfo != null || url.port !in listOf(-1, 443)) return null
    val connection = url.openConnection() as HttpsURLConnection
    try {
        connection.connectTimeout = 8000; connection.readTimeout = 8000; connection.instanceFollowRedirects = false
        if (connection.responseCode != 200) return null
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 2 * 1024 * 1024) return null
                output.write(buffer, 0, count)
            }
        }
        val bytes = output.toByteArray()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        options.inJustDecodeBounds = false
        while (options.outWidth / options.inSampleSize.coerceAtLeast(1) > 800 || options.outHeight / options.inSampleSize.coerceAtLeast(1) > 800)
            options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } finally { connection.disconnect() }
}
