package ru.ruscrafting.ecojobs.integration

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID

/** Optional ARC bridge for accepted EcoJobs XP-event observations. */
internal object ArcJobWorkTelemetry {
    fun record(playerId: UUID, jobId: String): Boolean {
        return runCatching {
            Bukkit.getPluginManager().isPluginEnabled("ARC") && AvailableArcTelemetry.record(playerId, jobId)
        }.getOrDefault(false)
    }

    fun breakPlayer(playerId: UUID) {
        runCatching {
            if (Bukkit.getPluginManager().isPluginEnabled("ARC")) {
                AvailableArcTelemetry.breakPlayer(playerId)
            }
        }
    }

    private object AvailableArcTelemetry {
        fun record(playerId: UUID, jobId: String): Boolean =
            Bukkit.getServicesManager().load(ArcTelemetryProvider::class.java)
                ?.recordJobWork(playerId, jobId) == true

        fun breakPlayer(playerId: UUID) {
            Bukkit.getServicesManager().load(ArcTelemetryProvider::class.java)?.breakJobWork(playerId)
        }
    }
}
