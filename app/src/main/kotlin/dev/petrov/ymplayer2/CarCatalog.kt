package dev.petrov.ymplayer2

import dev.petrov.ymplayer2.core.*
import dev.petrov.ymplayer2.localization.*
import dev.petrov.ymplayer2.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun folder(key: String, label: Msg, contents: suspend () -> List<CarRow>) =
    CarRow(key, tr(label), children = contents)
internal fun button(key: String, label: Msg, action: () -> Unit) = CarRow(key, tr(label), prepare = { action })
internal fun more(key: String, contents: suspend () -> List<CarRow>) = folder("more:$key", Msg.msg_c6826c783794, contents)

// Car hosts request a whole folder in one binder call. Group large lists before constructing
// metadata or opening artwork, and keep every item reachable without host pagination.
internal fun <T> carGroups(list: List<T>, start: Int = 0, row: (T) -> CarRow): List<CarRow> {
    if (list.size <= 32) return list.map(row)
    var group = 32
    while ((list.size - 1) / group >= 32) group *= 32
    return list.indices.step(group).map { from ->
        val end = minOf(from + group, list.size)
        CarRow("range:${start + from}", "${start + from + 1}–${start + end}", children = {
            carGroups(list.subList(from, end), start + from, row)
        })
    }
}

internal class AppCarCatalog(private val app: PlayerApplication) : CarCatalog {
    private val player get() = app.playback
    private val profile get() = player.state.value.profileId
    private val local = CarLocalLibrary(app)
    private val cloud = CarOnlineLibrary(app)
    private val radio = CarRadioLibrary(app)
    private val signed get() = app.accounts.state.value.let { it.profileId == profile && it.phase == AuthPhase.SIGNED_IN }
    override val owner get() = profile + ":" + app.accounts.state.value.let { if (signed) it.account?.id.orEmpty() else "-" }
    override val changes get() = merge(
        app.accounts.state.map { Triple(it.profileId, it.phase, it.account?.id) }.distinctUntilChanged().map { },
        player.state.map { it.profileId to it.localLists }.distinctUntilChanged().map { },
        app.library.state.map { it.ready to it.roots }.distinctUntilChanged().map { },
        app.collections.state.map { }, AppLanguages.state.map { },
        app.offline.state.map { Triple(it.owner, it.enabled, it.tracks) }.distinctUntilChanged().map { },
    )
    override suspend fun ready() {
        val target = profile
        app.accounts.activate(target)
        app.accounts.state.first { it.profileId != target || it.phase != AuthPhase.LOADING }
        require(profile == target)
    }
    private fun requireAccount() { if (!signed) throw CarAccountRequired() }

    private fun tab(key: String, label: Msg, contents: suspend () -> List<CarRow>) =
        CarRow(key, tr(label), artwork = android.net.Uri.parse("android.resource://${app.packageName}/drawable/car_$key"), children = contents)

    override fun root() = listOf(
        tab("player", Msg.msg_dff16f6f70a1, ::shortcuts),
        tab("local", Msg.library_local_files, local::root),
        tab("music", Msg.msg_c39959813ccd) { requireAccount(); cloud.root() },
        tab("radio", Msg.radio_e36f9cecd549) { requireAccount(); radio.root() },
    )

    private fun shortcuts() = buildList {
        add(button("resume", Msg.car_resume, ::resume))
        if (signed) {
            add(button("wave", Msg.msg_de1ea8c09caa, player::playMyWave))
            if (app.offline.state.value.enabled) {
                add(button("offline", Msg.msg_aa07f084155c, player::playOffline))
                add(folder("cached", Msg.msg_8491940b48e2) {
                    val rows = app.offline.readyTracks(profile)
                    val ids = rows.map(Track::id)
                    carGroups(rows) { song -> local.song(song) { player.playList(ids, song.id, PlaybackOrigin(PlaybackSource.OFFLINE)) } }
                })
            }
        }
        add(folder("queue", Msg.msg_cb297d129add) { queue(0) })
        add(folder("recent", Msg.local_list_history, local::recent))
    }

    private suspend fun queue(offset: Int): List<CarRow> {
        val page = player.queuePage(offset, 30)
        return page.items.filter(Track::available).map { song -> local.song(song) { player.select(song.id) } } +
            listOfNotNull(if (page.hasMore) more("queue${offset + page.items.size}") { queue(offset + page.items.size) } else null)
    }

    override fun resume() {
        val saved = app.launchState.read(profile)
        when (saved.output) {
            PlaybackOutput.CLIPS -> { requireAccount(); app.playCarClips() }
            PlaybackOutput.RADIO -> {
                requireAccount()
                app.radio.switchProfile(profile)
                if (app.radio.state.value.let { !it.playing && !it.buffering && !it.reconnecting }) app.radio.play()
            }
            PlaybackOutput.MUSIC -> player.resumeFromCar()
        }
    }

    override suspend fun search(query: String): List<CarRow> = supervisorScope {
        val favorites = button("local-favorites", Msg.player_source_favorites) {
            player.resumeLocalList(LocalListTarget.favorites, app.collections.state.value.profile(profile).favorites)
        }
        val shortcuts = (shortcuts().filter { it.prepare != null } + favorites +
            if (signed) listOf(button("clip-audio", Msg.car_clip_audio, app::playCarClips)) else emptyList())
            .filter { it.title.equals(query.trim(), ignoreCase = true) }
        if (shortcuts.isNotEmpty()) return@supervisorScope shortcuts
        val device = async { local.search(query) }
        if (!signed) return@supervisorScope device.await()
        val music = async { remoteResults { cloud.search(query) } }
        val fm = async { remoteResults { radio.search(query) } }
        val cached = app.offline.tracks(profile).filter { it.title.contains(query, true) || it.artist.contains(query, true) }
        val ids = cached.map(Track::id)
        val results = device.await() + cached.take(20).map { song -> local.song(song) {
            player.playList(ids, song.id, PlaybackOrigin(PlaybackSource.OFFLINE))
        } } + music.await().getOrDefault(emptyList()) + fm.await().getOrDefault(emptyList())
        if (results.isEmpty()) music.await().exceptionOrNull()?.let { throw it }
        results.distinctBy(CarRow::key)
    }

    private suspend fun remoteResults(load: suspend () -> List<CarRow>): Result<List<CarRow>> =
        try { withTimeoutOrNull(6500) { Result.success(load()) } ?: Result.failure(MusicException(MusicFailure.NETWORK)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
}
