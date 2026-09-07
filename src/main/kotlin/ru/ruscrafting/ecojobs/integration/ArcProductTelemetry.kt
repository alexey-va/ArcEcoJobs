package ru.ruscrafting.ecojobs.integration

import java.util.UUID

/** Optional ARC observation after the owned purchase/redemption transition. */
internal object ArcProductTelemetry {
    private val method by lazy {
        runCatching {
            Class.forName("ru.arc.metrics.ExternalProductTelemetryBridge").getMethod(
                "recordEvent", UUID::class.java, String::class.java, String::class.java, String::class.java,
            )
        }.getOrNull()
    }

    fun purchased(playerId: UUID, purchaseId: UUID) = record(playerId, "job_boost_purchased", purchaseId)
    fun activated(playerId: UUID, voucherId: UUID) = record(playerId, "job_boost_activated", voucherId)

    fun workBlocked(playerId: UUID, reason: String) {
        if (reason in setOf("afk", "farm")) record(playerId, "work_blocked_$reason", UUID.randomUUID())
    }

    private fun record(playerId: UUID, event: String, operationId: UUID) {
        runCatching { method?.invoke(null, playerId, "arcecojobs", event, operationId.toString()) }
    }
}
