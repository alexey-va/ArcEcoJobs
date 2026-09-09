package ru.ruscrafting.ecojobs.integration

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcTelemetryProvider
import java.math.BigDecimal
import java.util.UUID

interface JobAuditBridge {
    fun mark(playerId: UUID, jobId: String, amount: BigDecimal): String?
    fun cancel(playerId: UUID, token: String?)
}

object NoopJobAuditBridge : JobAuditBridge {
    override fun mark(playerId: UUID, jobId: String, amount: BigDecimal): String? = null
    override fun cancel(playerId: UUID, token: String?) = Unit
}

/** Optional typed link to ARC's central audit context tracker. */
class ArcAuditBridge(private val audit: ArcTelemetryProvider) : JobAuditBridge {
    override fun mark(playerId: UUID, jobId: String, amount: BigDecimal): String? =
        runCatching { audit.markJobReward(playerId, jobId, amount.toDouble()) }.getOrNull()

    override fun cancel(playerId: UUID, token: String?) {
        runCatching { audit.cancelAudit(playerId, token) }
    }

    companion object {
        fun discover(): JobAuditBridge =
            runCatching { Bukkit.getServicesManager().load(ArcTelemetryProvider::class.java) }
                .getOrNull()
                ?.let(::ArcAuditBridge)
                ?: NoopJobAuditBridge
    }
}
