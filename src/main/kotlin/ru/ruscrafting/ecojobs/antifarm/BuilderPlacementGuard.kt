package ru.ruscrafting.ecojobs.antifarm

import java.util.UUID

/**
 * Keeps the latest accepted placement decision for each player and block key.
 * The bounded map intentionally has no quit hook: reconnecting must not grant a
 * fresh reward window. This is process-local state, like the hunting guard.
 */
class BuilderPlacementGuard(private val settings: () -> BuilderPlacementSettings) {
    data class Key(val player: UUID, val world: UUID, val x: Int, val y: Int, val z: Int)
    data class Decision(val key: Key, val at: Long, val allowed: Boolean, val capacityFull: Boolean = false)

    // Monotonic timestamps form an ordered ring. Expiration advances its logical
    // head by binary search; stale map entries are reclaimed one slot per new
    // placement, so a quiet period never turns the next placement into a full scan.
    private data class Entry(val key: Key, val at: Long, val sequence: Long)
    private val placements = HashMap<Key, Entry>()
    private val pages = arrayOfNulls<Array<Entry?>>(MAX_ENTRIES / PAGE_SIZE + 1)
    private var head = 0L
    private var tail = 0L
    private var lastNow = Long.MIN_VALUE

    fun record(key: Key, now: Long): Decision {
        val config = settings()
        val effectiveNow = now.coerceAtLeast(lastNow)
        lastNow = effectiveNow
        if (!config.enabled) return Decision(key, effectiveNow, true)
        purgeExpired(effectiveNow, config.cooldownMillis)
        val previous = placements[key]?.takeIf { it.sequence >= head }
        if (previous != null) return Decision(key, effectiveNow, false)
        if (tail - head >= config.maximumEntries) {
            // Never evict an active key: a full bounded guard fails closed.
            return Decision(key, effectiveNow, false, capacityFull = true)
        }
        val slot = (tail % MAX_ENTRIES).toInt()
        val page = pages[slot / PAGE_SIZE] ?: arrayOfNulls<Entry>(PAGE_SIZE).also { pages[slot / PAGE_SIZE] = it }
        page[slot % PAGE_SIZE]?.let { placements.remove(it.key, it) }
        val entry = Entry(key, effectiveNow, tail++)
        page[slot % PAGE_SIZE] = entry
        placements[key] = entry
        return Decision(key, effectiveNow, true)
    }

    private fun purgeExpired(now: Long, cooldownMillis: Long) {
        var low = head
        var high = tail
        while (low < high) {
            val middle = low + (high - low) / 2
            val slot = (middle % MAX_ENTRIES).toInt()
            val entry = checkNotNull(pages[slot / PAGE_SIZE]?.get(slot % PAGE_SIZE))
            if (expired(entry.at, now, cooldownMillis)) low = middle + 1 else high = middle
        }
        head = low
    }

    private fun expired(at: Long, now: Long, cooldownMillis: Long): Boolean =
        now >= at && now - at >= cooldownMillis

    private companion object { const val MAX_ENTRIES = 100_000; const val PAGE_SIZE = 256 }
}

data class BuilderPlacementSettings(
    val enabled: Boolean = true,
    val cooldownMillis: Long = 600_000,
    val maximumEntries: Int = 100_000,
) {
    init {
        require(cooldownMillis in 60_000..86_400_000) {
            "builder placement cooldown must be between 60 seconds and 24 hours"
        }
        require(maximumEntries in 1..100_000) {
            "builder placement maximum entries must be between 1 and 100000"
        }
    }
}
