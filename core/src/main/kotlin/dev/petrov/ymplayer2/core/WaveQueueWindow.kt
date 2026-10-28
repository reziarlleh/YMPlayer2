package dev.petrov.ymplayer2.core

/** A wave owns at most ten logical entries, including its one-step Back history. */
object WaveQueueWindow {
    const val MAX_ITEMS = 10

    fun retain(queue: List<Track>, currentId: String?): List<Track> {
        val index = queue.indexOfFirst { it.id == currentId }
        return queue.drop((index - 1).coerceAtLeast(0)).take(MAX_ITEMS)
    }

    fun needsMore(queue: List<Track>, currentId: String?) = retain(queue, currentId).size < MAX_ITEMS
}
