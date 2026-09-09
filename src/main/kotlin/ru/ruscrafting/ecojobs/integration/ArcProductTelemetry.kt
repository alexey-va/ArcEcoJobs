package ru.ruscrafting.ecojobs.integration

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID

/** Optional ARC observation after the owned purchase/redemption transition. */
internal object ArcProductTelemetry {
    private val telemetry by lazy {
        runCatching { Bukkit.getServicesManager().load(ArcTelemetryProvider::class.java) }.getOrNull()
    }

    fun purchased(playerId: UUID, purchaseId: UUID) = record(playerId, "job_boost_purchased", purchaseId)
    fun activated(playerId: UUID, voucherId: UUID) = record(playerId, "job_boost_activated", voucherId)

    fun workBlocked(playerId: UUID, reason: String) {
        if (reason in setOf("afk", "farm")) record(playerId, "work_blocked_$reason", UUID.randomUUID())
    }

    private fun record(playerId: UUID, event: String, operationId: UUID) {
        runCatching { telemetry?.recordEvent(playerId, "arcecojobs", event, operationId.toString()) }
    }
}
