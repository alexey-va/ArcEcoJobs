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

    // Insertion order makes expired entries a prefix while the clock moves
    // forward. Rejected repeats do not move that prefix, so hot placements do
    // not scan the full 100000-entry bound.
    private val placements = LinkedHashMap<Key, Long>(16, .75f)
    private var lastNow = Long.MIN_VALUE

    fun record(key: Key, now: Long): Decision {
        val config = settings()
        val effectiveNow = now.coerceAtLeast(lastNow)
        lastNow = effectiveNow
        if (!config.enabled) return Decision(key, effectiveNow, true)
        purgeExpired(effectiveNow, config.cooldownMillis)
        val previous = placements[key]
        if (previous != null) {
            if (!expired(previous, effectiveNow, config.cooldownMillis)) return Decision(key, effectiveNow, false)
            placements.remove(key)
        }
        if (previous == null && placements.size >= config.maximumEntries) {
            // Never evict an active key: a full bounded guard fails closed.
            return Decision(key, effectiveNow, false, capacityFull = true)
        }
        placements[key] = effectiveNow
        return Decision(key, effectiveNow, true)
    }

    private fun purgeExpired(now: Long, cooldownMillis: Long) {
        val iterator = placements.entries.iterator()
        while (iterator.hasNext() && expired(iterator.next().value, now, cooldownMillis)) iterator.remove()
    }

    private fun expired(at: Long, now: Long, cooldownMillis: Long): Boolean =
        now >= at && now - at >= cooldownMillis

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
