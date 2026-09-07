package ru.ruscrafting.ecojobs.antifarm

import com.willfp.eco.core.integrations.afk.AFKManager
import com.willfp.ecojobs.api.event.PlayerJobExpGainEvent
import com.willfp.libreforge.triggers.event.TriggerDispatchEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import ru.ruscrafting.ecojobs.config.AddonSettings
import ru.ruscrafting.ecojobs.config.JobsLocale
import ru.ruscrafting.ecojobs.integration.ArcProductTelemetry
import ru.ruscrafting.ecojobs.integration.ArcJobWorkTelemetry
import java.util.UUID

/** One eligibility decision shared by the job money placeholder, pre-deposit gate and XP listener.
 * TriggerDispatchEvent is observed, never cancelled: other libreforge plugins keep their effects.
 * Capture precedes libreforge effects and XP counters in pinned libreforge 2026.33.
 */
class JobRewardGuard(
    private val settings: () -> AddonSettings,
    private val locale: JobsLocale,
    private val isSlayerActive: (Player) -> Boolean,
    private val isBuilderActive: (Player) -> Boolean = { true },
    private val eligibleHunt: (com.willfp.libreforge.triggers.TriggerData) -> Boolean = { true },
    private val afk: (Player) -> Boolean = AFKManager::isAfk,
    private val clock: () -> Long = System::currentTimeMillis,
) : Listener {
    private data class KillDecision(val victim: UUID, val at: Long, val allowed: Boolean)
    private val hunt = StationaryHuntGuard { settings().huntGuard }
    private val builder = BuilderPlacementGuard { settings().builderPlacement }
    private val kills = LinkedHashMap<UUID, KillDecision>(16, .75f, true)
    private data class PlacementDecision(
        val key: BuilderPlacementGuard.Key,
        val at: Long,
        val allowed: Boolean,
        val capacityFull: Boolean,
    )
    private val placements = LinkedHashMap<UUID, PlacementDecision>(16, .75f)
    private var builderContextSaturatedAt = Long.MIN_VALUE
    private var deniedBuilderCapacity = 0L
    private var deniedBuilderContext = 0L
    private val notices = LinkedHashMap<UUID, Long>(16, .75f, true)
    private var deniedAfk = 0L
    private var deniedFarm = 0L

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTrigger(event: TriggerDispatchEvent) {
        val triggerId = event.trigger.trigger.id
        val data = event.trigger.data
        val player = data.player ?: return
        if (triggerId == "place_block") {
            val block = data.block ?: return
            if (!Bukkit.isPrimaryThread() || !isBuilderActive(player) || player.hasMetadata("NPC")) return
            if (settings().blockAfkRewards && afk(player)) {
                placements.remove(player.uniqueId)
                return
            }
            val now = monotonicNow()
            purgePlacementContexts(now)
            if (!placements.containsKey(player.uniqueId) && placements.size >= MAX_CONTEXT_PLAYERS) {
                // Do not evict a live decision: fail closed for this dispatch.
                builderContextSaturatedAt = now
                deniedBuilderContext++
                return
            }
            val key = BuilderPlacementGuard.Key(
                player.uniqueId,
                block.world.uid,
                block.x,
                block.y,
                block.z,
            )
            val decision = builder.record(key, now)
            placements.remove(player.uniqueId)
            placements[player.uniqueId] = PlacementDecision(
                decision.key,
                decision.at,
                decision.allowed,
                decision.capacityFull,
            )
            if (decision.capacityFull) deniedBuilderCapacity++
            return
        }
        if (triggerId != "kill") return
        val victim = data.victim ?: return
        if (!Bukkit.isPrimaryThread() || !isSlayerActive(player) || player.hasMetadata("NPC")) return
        val now = monotonicNow()
        val previous = kills[player.uniqueId]
        if (previous?.victim == victim.uniqueId && now - previous.at in 0..CONTEXT_MILLIS) return
        if ((settings().blockAfkRewards && afk(player)) || !eligibleHunt(data)) {
            kills.remove(player.uniqueId)
            return
        }
        val allowed = hunt.recordKill(player.uniqueId, victim.location.position(), now)
        kills[player.uniqueId] = KillDecision(victim.uniqueId, now, allowed)
        if (kills.size > StationaryHuntGuard.MAX_PLAYERS) kills.remove(kills.keys.first())
    }

    fun allows(player: Player, jobId: String): Boolean {
        // Bukkit state is never read on an async economy callback. Job effects are synchronous.
        if (!Bukkit.isPrimaryThread()) return false
        val reason = when {
            settings().blockAfkRewards && afk(player) -> "afk"
            jobId == "builder" && settings().builderPlacement.enabled &&
                placements[player.uniqueId]?.let {
                    !it.allowed && monotonicNow() - it.at in 0..CONTEXT_MILLIS
                } == true -> if (placements[player.uniqueId]?.capacityFull == true) "placement-capacity" else "repeat-placement"
            jobId == "builder" && settings().builderPlacement.enabled &&
                placements[player.uniqueId] == null &&
                monotonicNow() - builderContextSaturatedAt in 0..CONTEXT_MILLIS -> "placement-capacity"
            jobId == "slayer" && settings().huntGuard.enabled &&
                kills[player.uniqueId]?.let { !it.allowed && monotonicNow() - it.at in 0..CONTEXT_MILLIS } == true -> "farm"
            else -> return true
        }
        val now = monotonicNow()
        ArcJobWorkTelemetry.breakPlayer(player.uniqueId)
        if (now - (notices[player.uniqueId] ?: Long.MIN_VALUE / 2) >= NOTICE_MILLIS) {
            notices[player.uniqueId] = now
            if (notices.size > StationaryHuntGuard.MAX_PLAYERS) notices.remove(notices.keys.first())
            if (reason == "afk") deniedAfk++ else deniedFarm++
            val messageReason = if (reason == "repeat-placement" || reason == "placement-capacity") "farm" else reason
            player.sendActionBar(locale.render("message.work-blocked-$messageReason", player))
            ArcProductTelemetry.workBlocked(player.uniqueId, messageReason)
        }
        return false
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onExperience(event: PlayerJobExpGainEvent) {
        // Farm eligibility is checked in the action filter before libreforge's XP accumulator.
        // A later blocked kill must not erase XP already accepted into an earlier batch.
        if (settings().blockAfkRewards && afk(event.player)) event.isCancelled = true
    }

    fun summary(): String = "afk=${settings().blockAfkRewards} stationary=${settings().huntGuard.enabled} " +
        "builder=${settings().builderPlacement.enabled} afk_notices=$deniedAfk farm_notices=$deniedFarm " +
        "builder_capacity_denials=$deniedBuilderCapacity builder_context_denials=$deniedBuilderContext scope=local_process"

    private fun Location.position() = StationaryHuntGuard.Position(world.uid, x, y, z)

    private fun monotonicNow(): Long = clock().coerceAtLeast(lastNow).also { lastNow = it }

    private fun purgePlacementContexts(now: Long) {
        val iterator = placements.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.at > CONTEXT_MILLIS) iterator.remove() else break
        }
    }

    companion object {
        private const val MAX_CONTEXT_PLAYERS = 8_192
        private const val CONTEXT_MILLIS = 5_000L
        private const val NOTICE_MILLIS = 60_000L
    }

    private var lastNow = Long.MIN_VALUE
}
