package dev.petrov.ymplayer2.shell

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
import dev.petrov.ymplayer2.designsystem.DemoArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val coverMemory = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}

@Composable internal fun TrackArtwork(track: Track?, modifier: Modifier = Modifier) {
    val uri = track?.artworkUri
    // Key the entire state: a previous track's cover is never shown during a new read.
    key(uri) {
        val bitmap by produceState<Bitmap?>(null, uri) {
            value = if (uri == null) null else withContext(Dispatchers.IO) {
                coverMemory.get(uri) ?: runCatching {
                    val parsed = Uri.parse(uri)
                    if (parsed.scheme != "file") null else BitmapFactory.decodeFile(parsed.path)
                }.getOrNull()?.also { coverMemory.put(uri, it) }
            }
        }
        if (bitmap == null) DemoArtwork(track?.tint ?: 0, modifier.testTag("artwork_placeholder"))
        else Image(bitmap!!.asImageBitmap(), "Обложка: ${track?.title.orEmpty()}", modifier.clip(MaterialTheme.shapes.large).testTag("track_artwork"), contentScale = ContentScale.Crop)
    }
}
