package ru.ruscrafting.ecojobs.integration

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID

/** Optional ARC observation after the owned purchase/redemption transition. */
internal object ArcProductTelemetry {
    fun purchased(playerId: UUID, purchaseId: UUID) = record(playerId, "job_boost_purchased", purchaseId)
    fun activated(playerId: UUID, voucherId: UUID) = record(playerId, "job_boost_activated", voucherId)

    fun workBlocked(playerId: UUID, reason: String) {
        if (reason in setOf("afk", "farm")) record(playerId, "work_blocked_$reason", UUID.randomUUID())
    }

    private fun record(playerId: UUID, event: String, operationId: UUID) {
        runCatching {
            if (Bukkit.getPluginManager().isPluginEnabled("ARC")) {
                AvailableArcTelemetry.recordEvent(playerId, event, operationId)
            }
        }
    }

    private object AvailableArcTelemetry {
        fun recordEvent(playerId: UUID, event: String, operationId: UUID) {
            Bukkit.getServicesManager().load(ArcTelemetryProvider::class.java)
                ?.recordEvent(playerId, "arcecojobs", event, operationId.toString())
        }
    }
}
