package ru.ruscrafting.ecojobs.integration

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.util.UUID

class ArcAuditBridgeTest : StringSpec({
    "uses cached exact method handles for the optional ARC bridge" {
        FastBridgeFixture.reset()
        val bridge = ReflectiveArcAuditBridge.discover(FastBridgeFixture::class.java.name)
        val playerId = UUID.randomUUID()

        val token = bridge.mark(playerId, "builder", BigDecimal("4.25"))
        token shouldBe "builder:4.25"
        FastBridgeFixture.markedPlayer shouldBe playerId

        bridge.cancel(playerId, token)
        FastBridgeFixture.cancelledToken shouldBe token
    }
})

object FastBridgeFixture {
    var markedPlayer: UUID? = null
    var cancelledToken: String? = null

    fun reset() {
        markedPlayer = null
        cancelledToken = null
    }

    @JvmStatic
    fun markJobReward(playerId: UUID, jobId: String, amount: Double): String {
        markedPlayer = playerId
        return "$jobId:$amount"
    }

    @JvmStatic
    fun cancel(playerId: UUID, token: String?) {
        check(markedPlayer == playerId)
        cancelledToken = token
    }
}
