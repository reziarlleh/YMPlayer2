package dev.petrov.ymplayer2.core

/** An existing item is moved, keeping a single occurrence and the sounding item in place. */
fun <T> List<T>.withNextGroup(group: List<T>, currentId: String?, id: (T) -> String): List<T> {
    val next = group.distinctBy(id).filter { id(it) != currentId }
    if (next.isEmpty()) return this
    val moving = next.mapTo(HashSet(), id)
    val remaining = filter { id(it) !in moving }.toMutableList()
    remaining.addAll(remaining.indexOfFirst { id(it) == currentId } + 1, next)
    return remaining
}
