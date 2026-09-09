package ru.ruscrafting.ecojobs.integration

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.api.ArcTelemetryProvider
import java.math.BigDecimal
import java.util.UUID

class ArcAuditBridgeTest : StringSpec({
    "forwards audit calls through the typed ARC service" {
        FastBridgeFixture.reset()
        val bridge = ArcAuditBridge(FastBridgeFixture)
        val playerId = UUID.randomUUID()

        val token = bridge.mark(playerId, "builder", BigDecimal("4.25"))
        token shouldBe "builder:4.25"
        FastBridgeFixture.markedPlayer shouldBe playerId

        bridge.cancel(playerId, token)
        FastBridgeFixture.cancelledToken shouldBe token
    }
})

object FastBridgeFixture : ArcTelemetryProvider {
    var markedPlayer: UUID? = null
    var cancelledToken: String? = null

    fun reset() {
        markedPlayer = null
        cancelledToken = null
    }

    override fun markJobReward(playerId: UUID, job: String, amount: Double): String {
        markedPlayer = playerId
        return "$job:$amount"
    }

    override fun markExternalReward(
        playerId: UUID,
        source: String,
        action: String,
        amount: Double,
        currency: String?,
        rewardId: String?,
    ): String? = null

    override fun cancelAudit(playerId: UUID, token: String?) {
        check(markedPlayer == playerId)
        cancelledToken = token
    }
}
