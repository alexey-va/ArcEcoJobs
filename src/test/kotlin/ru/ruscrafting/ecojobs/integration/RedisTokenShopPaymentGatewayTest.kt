package ru.ruscrafting.ecojobs.integration

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import dev.unnm3d.rediseconomy.currency.Currency
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.milkbowl.vault.economy.EconomyResponse
import ru.ruscrafting.ecojobs.config.ShopCurrency
import ru.ruscrafting.ecojobs.shop.PaymentOutcome
import java.math.BigDecimal
import java.util.UUID

class RedisTokenShopPaymentGatewayTest : StringSpec({
    val player = UUID.randomUUID()
    val api = mockk<RedisEconomyAPI>()
    val tokens = mockk<Currency>()
    val vault = mockk<Currency>()

    beforeTest {
        every { api.getCurrencyByName("tokens") } returns tokens
        every { api.getCurrencyByName("vault") } returns vault
        every { tokens.currencyName } returns "tokens"
        every { tokens.transactionTax } returns 0.0
    }

    "routes only TOKENS to the named Redis currency" {
        val gateway = RedisTokenShopPaymentGateway { api }

        gateway.forCurrency(ShopCurrency.TOKENS) shouldBe gateway
        gateway.forCurrency(ShopCurrency.MONEY) shouldBe null
        every { tokens.getBalance(player) } returns 100.0
        gateway.has(player, BigDecimal("90")) shouldBe true
    }

    "reports insufficient named-token balance without charging" {
        every { tokens.getBalance(player) } returns 80.0
        val gateway = RedisTokenShopPaymentGateway { api }

        gateway.has(player, BigDecimal("90")) shouldBe false
        gateway.charge(player, BigDecimal("90")) shouldBe PaymentOutcome.REJECTED
        verify(exactly = 0) { tokens.withdrawPlayer(any(), any(), any(), any()) }
    }

    "rejects provider refusal and never falls back to vault" {
        every { tokens.getBalance(player) } returns 100.0
        every { tokens.withdrawPlayer(player, "tokens", 90.0, "arc-ecojobs-shop") } returns failure(90.0, 100.0)
        val gateway = RedisTokenShopPaymentGateway { api }

        gateway.charge(player, BigDecimal("90")) shouldBe PaymentOutcome.REJECTED
        verify(exactly = 0) { vault.withdrawPlayer(any(), any(), any(), any()) }
    }

    "keeps provider or named-currency absence unknown" {
        val noApi = RedisTokenShopPaymentGateway { null }
        noApi.charge(player, BigDecimal("90")) shouldBe PaymentOutcome.UNKNOWN
        noApi.has(player, BigDecimal("90")) shouldBe false

        every { api.getCurrencyByName("tokens") } returns null
        val noTokens = RedisTokenShopPaymentGateway { api }
        noTokens.charge(player, BigDecimal("90")) shouldBe PaymentOutcome.UNKNOWN
    }

    "requires accepted charge to show the expected post-charge balance" {
        every { tokens.getBalance(player) } returnsMany listOf(100.0, 100.0)
        every { tokens.withdrawPlayer(player, "tokens", 90.0, "arc-ecojobs-shop") } returns success(90.0, 10.0)
        val gateway = RedisTokenShopPaymentGateway { api }

        gateway.charge(player, BigDecimal("90")) shouldBe PaymentOutcome.UNKNOWN
    }
})

private fun success(amount: Double, balance: Double) = EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, null)
private fun failure(amount: Double, balance: Double) = EconomyResponse(amount, balance, EconomyResponse.ResponseType.FAILURE, "rejected")
