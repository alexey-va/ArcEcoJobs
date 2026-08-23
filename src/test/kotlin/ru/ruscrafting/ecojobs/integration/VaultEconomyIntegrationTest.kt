package ru.ruscrafting.ecojobs.integration

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.OfflinePlayer
import java.math.BigDecimal

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
