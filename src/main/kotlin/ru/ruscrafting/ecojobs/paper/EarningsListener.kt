package ru.ruscrafting.ecojobs.paper

import com.willfp.ecojobs.api.event.PlayerJobExpGainEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import ru.ruscrafting.ecojobs.earnings.EarningsService

class EarningsListener(
    private val earnings: EarningsService,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onExperience(event: PlayerJobExpGainEvent) {
        earnings.recordXp(event.player.uniqueId, event.job.id, event.amount)
    }
}
