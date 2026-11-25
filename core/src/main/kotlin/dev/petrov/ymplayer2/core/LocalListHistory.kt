package dev.petrov.ymplayer2.core

/** Most recent first. Only the current shortcut can sit outside a full menu of pins. */
data class LocalListHistory(val recent: List<LocalListTarget> = emptyList(), val pinned: List<String> = emptyList()) {
    fun menu(): List<LocalListTarget> {
        val fixed = pinned.mapNotNull { key -> recent.find { it.key == key } }
        return fixed + recent.filterNot { it.key in pinned }.take(CAPACITY - fixed.size)
    }

    fun played(target: LocalListTarget): LocalListHistory =
        retained(listOf(target) + recent.filterNot { it.key == target.key }, pinned)

    fun pin(target: LocalListTarget, enabled: Boolean): LocalListHistory {
        if (enabled == (target.key in pinned) || enabled && pinned.size >= CAPACITY) return this
        val next = if (enabled) pinned + target.key else pinned - target.key
        val order = if (enabled && recent.none { it.key == target.key }) recent + target else recent
        return retained(order, next)
    }

    private fun retained(order: List<LocalListTarget>, pins: List<String>): LocalListHistory {
        val visible = order.filterNot { it.key in pins }.take(CAPACITY - pins.size)
        return LocalListHistory(order.filter { it.key in pins || it in visible || it == order.firstOrNull() }, pins)
    }

    companion object { const val CAPACITY = 5 }
}
