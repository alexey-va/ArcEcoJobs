package ru.ruscrafting.ecojobs.paper

import com.willfp.eco.core.integrations.afk.AFKManager
import com.willfp.ecojobs.api.event.PlayerJobExpGainEvent
import com.willfp.ecojobs.api.event.PlayerJobLeaveEvent
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import ru.ruscrafting.ecojobs.integration.ArcJobWorkTelemetry
import java.util.Locale
import java.util.UUID

/** Observes accepted native EcoJobs XP events; ARC labels these as work intervals. */
internal class ArcJobWorkObserver(
    private val record: (UUID, String) -> Unit = { playerId, jobId -> ArcJobWorkTelemetry.record(playerId, jobId) },
    private val breakPlayer: (UUID) -> Unit = ArcJobWorkTelemetry::breakPlayer,
    private val afk: (Player) -> Boolean = AFKManager::isAfk,
) : Listener, AutoCloseable {
    private var afkTask: ScheduledTask? = null

    fun start(tasks: LifecycleTaskScope) {
        check(afkTask == null) { "job work observer is already started" }
        afkTask = checkNotNull(tasks.runTimer(20L, 20L) {
            Bukkit.getOnlinePlayers().filter { runCatching { afk(it) }.getOrDefault(true) }
                .forEach { breakPlayer(it.uniqueId) }
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onExperience(event: PlayerJobExpGainEvent) {
        if (event.isCancelled || event.player.hasMetadata("NPC") || !event.amount.isFinite() || event.amount <= 0.0) return
        if (runCatching { afk(event.player) }.getOrDefault(true)) {
            breakPlayer(event.player.uniqueId)
            return
        }
        record(event.player.uniqueId, event.job.id.lowercase(Locale.ROOT))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = breakPlayer(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) = breakPlayer(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onJobLeave(event: PlayerJobLeaveEvent) = breakPlayer(event.player.uniqueId)

    override fun close() {
        afkTask?.cancel()
        afkTask = null
        Bukkit.getOnlinePlayers().forEach { breakPlayer(it.uniqueId) }
    }
}
