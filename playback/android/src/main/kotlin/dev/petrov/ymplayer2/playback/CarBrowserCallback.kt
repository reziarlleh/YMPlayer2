package dev.petrov.ymplayer2.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.localization.trMessage

internal const val CAR_CATALOG_HINT = "dev.petrov.ymplayer2.extra.CAR_CATALOG"

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal open class CarBrowserCallback(
    private val host: PlaybackHost, private val scope: CoroutineScope, private val beforeCarPlay: () -> Unit,
) : MediaLibrarySession.Callback {
    private val tree by lazy { CarMediaTree(requireNotNull(host.carCatalog)) }
    private var watching = false
    private var selectionVersion = 0L

    // Media3 checks the caller UID/package. A same-app client can explicitly request the
    // car tree; ordinary launchers, CWG, and old bookmarks keep their two original IDs.
    protected fun car(session: MediaSession, controller: MediaSession.ControllerInfo) =
        session.isAutoCompanionController(controller) ||
            controller.uid == android.os.Process.myUid() && controller.connectionHints.getBoolean(CAR_CATALOG_HINT) ||
            controller.isTrusted && controller.packageName == "com.google.android.googlequicksearchbox"

    private fun watch(session: MediaLibrarySession) {
        if (watching) return
        watching = true
        scope.launch {
            host.carCatalog?.changes?.collect {
                val parents = tree.clear()
                session.notifyChildrenChanged(CarMediaTree.ROOT, Int.MAX_VALUE, null)
                parents.filter { it.count { c -> c == '/' } >= 2 }.forEach {
                    session.notifyChildrenChanged(it, Int.MAX_VALUE, null)
                }
            }
        }
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val job = scope.launch {
            try { result.set(block()) }
            catch (e: CancellationException) { result.cancel(false); throw e }
            catch (e: Exception) { result.setException(e) }
        }
        result.addListener({ if (result.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        job.invokeOnCompletion { if (it != null) result.cancel(false) }
        return result
    }

    private fun resumeRequest() = MediaItem.Builder().setRequestMetadata(
        MediaItem.RequestMetadata.Builder().setSearchQuery("").build()).build()

    fun cancelPendingSelection() { selectionVersion++ }

    fun resumeActiveOutput(session: MediaSession): Boolean {
        val controller = session.controllerForCurrentRequest ?: return false
        if (!car(session, controller)) return false
        val ticket = ++selectionVersion
        val revision = host.playback.launchRevision
        scope.launch {
            try {
                val item = tree.selection(resumeRequest())
                if (ticket == selectionVersion && revision == host.playback.launchRevision)
                    (item.localConfiguration?.tag as? CarSelection)?.let { beforeCarPlay(); it.play() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { session.sendError(controller, error(e)) }
        }
        return true
    }

    private fun reported(item: MediaItem, session: MediaSession, controller: MediaSession.ControllerInfo): MediaItem {
        val selection = item.localConfiguration?.tag as? CarSelection ?: return item
        return item.buildUpon().setTag(CarSelection {
            try { beforeCarPlay(); selection.play() }
            catch (e: Exception) { session.sendError(controller, error(e)) }
        }).build()
    }
    protected fun carResumption(metadata: MediaItem?, session: MediaSession, controller: MediaSession.ControllerInfo) = future {
        val item = reported(tree.selection(resumeRequest()), session, controller).buildUpon()
            .apply { metadata?.mediaMetadata?.let(::setMediaMetadata) }.build()
        MediaSession.MediaItemsWithStartPosition(listOf(item), 0, androidx.media3.common.C.TIME_UNSET)
    }

    private fun error(e: Exception): SessionError = when (e) {
        is CarAccountRequired -> SessionError(SessionError.ERROR_SESSION_SETUP_REQUIRED, trMessage("Войдите в Яндекс в этом профиле."))
        is IllegalArgumentException, is NoSuchElementException -> SessionError(SessionError.ERROR_BAD_VALUE, trMessage("Этот трек или список сейчас недоступен."))
        is MusicException -> SessionError(SessionError.ERROR_IO, trMessage(e.failure.message()))
        else -> SessionError(SessionError.ERROR_IO, trMessage("Не удалось загрузить медиатеку"))
    }
    private fun <T : Any> library(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> = future {
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { LibraryResult.ofError<T>(error(e)) }
    }

    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
        params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
        // The legacy transport can reuse ControllerInfo for two browsers from the same process.
        // The root request's own parameters, unlike cached connection hints, belong to this browser.
        val auto = session.isAutoCompanionController(browser) ||
            browser.uid == android.os.Process.myUid() && params?.extras?.getBoolean(CAR_CATALOG_HINT) == true
        if (auto) watch(session)
        val item = if (auto) CarMediaTree.rootItem() else requireNotNull(BrowserSources.item(BrowserSources.ROOT))
        return Futures.immediateFuture(LibraryResult.ofItem(item, params))
    }

    override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
        parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (parentId == BrowserSources.ROOT || !car(session, browser) && browser.uid != android.os.Process.myUid())
            return Futures.immediateFuture(LibraryResult.ofItemList(BrowserSources.children(parentId, page, pageSize), params))
        return library { LibraryResult.ofItemList(tree.children(parentId).page(page, pageSize), params) }
    }

    override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
        mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = library {
        val item = BrowserSources.item(mediaId) ?: if (car(session, browser)) tree.item(mediaId) else null
        item?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }

    override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
        query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> = library {
        require(car(session, browser))
        // Legacy Android Auto completes a search after this notification, including no matches.
        var count = 0
        try { count = tree.search(query, fresh = true).size }
        finally { session.notifySearchResultChanged(browser, query, count, params) }
        LibraryResult.ofVoid()
    }

    override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
        page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = library {
        require(car(session, browser))
        LibraryResult.ofItemList(tree.search(query, fresh = false).page(page, pageSize), params)
    }

    override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
        val request = mediaItems.singleOrNull() ?: return Futures.immediateFailedFuture(IllegalArgumentException())
        BrowserSources.launchItem(request.mediaId)?.let { return Futures.immediateFuture(listOf(it)) }
        if (!car(session, controller) && controller.uid != android.os.Process.myUid())
            return Futures.immediateFailedFuture(IllegalArgumentException())
        val revision = host.playback.launchRevision
        val ticket = ++selectionVersion
        return future {
            try {
                val item = tree.selection(request)
                if (ticket != selectionVersion || revision != host.playback.launchRevision) throw CancellationException()
                listOf(reported(item, session, controller))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { session.sendError(controller, error(e)); throw e }
        }
    }

    private fun <T> List<T>.page(page: Int, size: Int): List<T> {
        require(page >= 0 && size > 0)
        val start = (page.toLong() * size).coerceAtMost(this.size.toLong())
        return subList(start.toInt(), (start + size).coerceAtMost(this.size.toLong()).toInt())
    }
}
