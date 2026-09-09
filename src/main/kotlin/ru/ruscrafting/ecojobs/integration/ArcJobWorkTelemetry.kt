package ru.ruscrafting.ecojobs.integration

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID

/** Optional ARC bridge for accepted EcoJobs XP-event observations. */
internal object ArcJobWorkTelemetry {
    private val telemetry by lazy {
        runCatching { Bukkit.getServicesManager().load(ArcTelemetryProvider::class.java) }.getOrNull()
    }

    fun record(playerId: UUID, jobId: String): Boolean =
        runCatching { telemetry?.recordJobWork(playerId, jobId) == true }.getOrDefault(false)

    fun breakPlayer(playerId: UUID) {
        runCatching { telemetry?.breakJobWork(playerId) }
    }
}
