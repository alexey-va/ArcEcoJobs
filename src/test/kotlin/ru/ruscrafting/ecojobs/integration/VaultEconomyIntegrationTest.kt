package ru.ruscrafting.ecojobs.integration

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.OfflinePlayer
import ru.ruscrafting.ecojobs.earnings.MoneyAttribution
import java.math.BigDecimal
import java.util.UUID

class VaultEconomyIntegrationTest : StringSpec({
    val player = mockk<OfflinePlayer>()
    val economy = mockk<Economy>()
    val integration = VaultEconomyIntegration(economy)

    "uses the canonical Vault integration id" {
        integration.pluginName shouldBe "Vault"
        integration.id shouldBe "vault"
    }

    "delegates balance reads to the active Vault provider" {
        every { economy.getBalance(player) } returns 12.75

        integration.getExactBalance(player) shouldBe BigDecimal("12.75")
    }

    "deposits the requested amount through Vault" {
        every { economy.depositPlayer(player, 4.25) } returns success(4.25, 17.0)

        integration.giveMoney(player, BigDecimal("4.25")) shouldBe true
        verify(exactly = 1) { economy.depositPlayer(player, 4.25) }
    }

    "propagates a rejected Vault deposit" {
        every { economy.depositPlayer(player, 4.25) } returns failure(4.25, 12.75)

        integration.giveMoney(player, BigDecimal("4.25")) shouldBe false
    }

    "records only successful deposits carrying an exact job marker" {
        val playerId = UUID.randomUUID()
        val attribution = MoneyAttribution()
        val recorded = mutableListOf<Pair<String, BigDecimal>>()
        val tracked =
            VaultEconomyIntegration(
                economy = economy,
                moneyAttribution = attribution,
                recordEarnings = { _, jobId, amount -> recorded += jobId to amount },
            )
        every { player.uniqueId } returns playerId
        every { economy.depositPlayer(player, 4.25) } returns success(4.0, 17.0)
        every { economy.depositPlayer(player, 2.0) } returns failure(2.0, 17.0)

        tracked.giveMoney(player, BigDecimal("4.25")) shouldBe true
        recorded shouldBe emptyList()

        attribution.mark(playerId, "builder")
        tracked.giveMoney(player, BigDecimal("4.25")) shouldBe true
        recorded shouldBe listOf("builder" to BigDecimal("4.0"))

        attribution.mark(playerId, "builder")
        tracked.giveMoney(player, BigDecimal("2")) shouldBe false
        recorded shouldBe listOf("builder" to BigDecimal("4.0"))
    }

    "marks ARC context before payout and cancels it after a failure" {
        val playerId = UUID.randomUUID()
        val attribution = MoneyAttribution()
        val bridge = RecordingJobAuditBridge()
        val tracked = VaultEconomyIntegration(economy, attribution, auditBridge = bridge)
        every { player.uniqueId } returns playerId
        every { economy.depositPlayer(player, 4.25) } answers {
            bridge.activeToken shouldBe "audit-token"
            success(4.25, 17.0)
        }
        attribution.mark(playerId, "builder")
        tracked.giveMoney(player, BigDecimal("4.25")) shouldBe true
        bridge.cancelled shouldBe emptyList()

        every { economy.depositPlayer(player, 2.0) } returns failure(2.0, 17.0)
        attribution.mark(playerId, "miner")
        tracked.giveMoney(player, BigDecimal("2.0")) shouldBe false
        bridge.cancelled shouldBe listOf("audit-token")
    }

    "a denied job reward never reaches provider audit or earnings but unrelated eco payments still work" {
        val id = UUID.randomUUID()
        val actor = mockk<OfflinePlayer> { every { uniqueId } returns id }
        val provider = mockk<Economy>()
        val audit = mockk<JobAuditBridge>()
        val attribution = MoneyAttribution()
        val guarded = VaultEconomyIntegration(provider, attribution,
            recordEarnings = { _, _, _ -> error("must not record denied earnings") },
            auditBridge = audit, rewardAllowed = { _, _ -> false })
        attribution.mark(id, "slayer")
        guarded.giveMoney(actor, BigDecimal("10")) shouldBe false
        verify(exactly = 0) { provider.depositPlayer(any<OfflinePlayer>(), any()) }
        verify(exactly = 0) { audit.mark(any(), any(), any()) }
        every { provider.depositPlayer(actor, 10.0) } returns success(10.0, 10.0)
        guarded.giveMoney(actor, BigDecimal("10")) shouldBe true
    }

    "withdraws the requested amount through Vault" {
        every { economy.withdrawPlayer(player, 2.5) } returns success(2.5, 10.25)

        integration.removeMoney(player, BigDecimal("2.5")) shouldBe true
        verify(exactly = 1) { economy.withdrawPlayer(player, 2.5) }
    }

    "delegates affordability checks to Vault" {
        every { economy.has(player, 9.0) } returns true

        integration.hasAmount(player, BigDecimal("9")) shouldBe true
    }
})

private class RecordingJobAuditBridge : JobAuditBridge {
    var activeToken: String? = null
    val cancelled = mutableListOf<String>()

    override fun mark(playerId: UUID, jobId: String, amount: BigDecimal): String {
        activeToken = "audit-token"
        return "audit-token"
    }

    override fun cancel(playerId: UUID, token: String?) {
        token?.let(cancelled::add)
        activeToken = null
    }
}

private fun success(amount: Double, balance: Double) = EconomyResponse(
    amount,
    balance,
    EconomyResponse.ResponseType.SUCCESS,
    null,
)

private fun failure(amount: Double, balance: Double) = EconomyResponse(
    amount,
    balance,
    EconomyResponse.ResponseType.FAILURE,
    "rejected",
)
