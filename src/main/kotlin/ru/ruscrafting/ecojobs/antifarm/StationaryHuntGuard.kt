package ru.ruscrafting.ecojobs.antifarm

import java.util.UUID

/** Local hunting eligibility, not a client/bot verdict. Call only on the server thread.
 * Observations survive reconnects and configuration reloads in this process; restart clears them.
 * Bounded to 8192 players × 32 recent sites; eligibility follows victim sites, not the hunter walking around one farm.
 */
class StationaryHuntGuard(private val settings: () -> HuntGuardSettings) {
    data class Position(val world: UUID, val x: Double, val y: Double, val z: Double) {
        fun near(other: Position, radius: Double): Boolean = world == other.world &&
            (x - other.x) * (x - other.x) + (y - other.y) * (y - other.y) +
            (z - other.z) * (z - other.z) <= radius * radius
    }
    private data class Site(
        val target: Position, var startedAt: Long, var lastAt: Long,
        var kills: Int = 0, var blockedUntil: Long = 0,
    )
    private data class Hunter(val sites: MutableList<Site> = mutableListOf(), var lastAt: Long = 0)
    private val hunters = LinkedHashMap<UUID, Hunter>(16, .75f, true)

    fun recordKill(playerId: UUID, target: Position, now: Long): Boolean {
        val config = settings()
        if (!config.enabled) return true
        val player = hunters.getOrPut(playerId) { Hunter() }
        player.lastAt = now
        if (hunters.size > MAX_PLAYERS) hunters.remove(hunters.keys.first())
        player.sites.removeAll { it.blockedUntil <= now && now - it.lastAt >= config.idleResetMillis }
        val site = player.sites.firstOrNull { it.target.near(target, config.siteRadius) }
            ?: Site(target, now, now).also {
                if (player.sites.size >= MAX_SITES) {
                    // Active cooldowns are retained: capacity pressure cannot grant a fresh farm window.
                    val expired = player.sites.filter { site -> site.blockedUntil <= now }.minByOrNull(Site::lastAt)
                        ?: return false
                    player.sites.remove(expired)
                }
                player.sites += it
            }
        site.lastAt = now
        if (site.blockedUntil > now) return false
        // Walking around the same victim site must not grant a fresh paid window.
        if (site.blockedUntil != 0L) {
            site.startedAt = now
            site.kills = 0
            site.blockedUntil = 0
        }
        site.kills = (site.kills + 1).coerceAtMost(config.minimumKills)
        if (site.kills >= config.minimumKills && now - site.startedAt >= config.minimumDurationMillis) {
            site.blockedUntil = now + config.cooldownMillis
            return false
        }
        return true
    }

    companion object {
        const val MAX_PLAYERS = 8192
        const val MAX_SITES = 32
    }
}

data class HuntGuardSettings(
    val enabled: Boolean = true,
    val minimumKills: Int = 120,
    val minimumDurationMillis: Long = 180_000,
    val siteRadius: Double = 12.0,
    val idleResetMillis: Long = 600_000,
    val cooldownMillis: Long = 1_800_000,
) {
    init {
        require(minimumKills in 20..10_000)
        require(minimumDurationMillis in 30_000..3_600_000)
        require(siteRadius.isFinite() && siteRadius in 2.0..64.0)
        require(idleResetMillis in minimumDurationMillis..86_400_000)
        require(cooldownMillis in 60_000..86_400_000)
    }
}
