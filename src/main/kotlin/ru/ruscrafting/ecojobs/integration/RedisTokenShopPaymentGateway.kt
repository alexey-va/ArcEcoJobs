package ru.ruscrafting.ecojobs.integration

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import ru.ruscrafting.ecojobs.config.ShopCurrency
import ru.ruscrafting.ecojobs.shop.PaymentOutcome
import ru.ruscrafting.ecojobs.shop.ShopPaymentGateway
import java.math.BigDecimal
import java.util.UUID

/** Named RedisEconomy token wallet; it never consults the Vault/default wallet. */
class RedisTokenShopPaymentGateway(
    private val apiProvider: () -> RedisEconomyAPI? = RedisEconomyAPI::getAPI,
) : ShopPaymentGateway {
    override fun forCurrency(currency: ShopCurrency): ShopPaymentGateway? =
        takeIf { currency == ShopCurrency.TOKENS }

    override fun has(playerId: UUID, amount: BigDecimal): Boolean = runCatching {
        if (!amount.isPositiveFinite()) return false
        val api = apiProvider() ?: return false
        val currency = api.getCurrencyByName(TOKEN_CURRENCY) ?: return false
        val balance = currency.getBalance(playerId)
        balance.isFinite() && BigDecimal.valueOf(balance) >= amount
    }.getOrDefault(false)

    override fun charge(playerId: UUID, amount: BigDecimal): PaymentOutcome = runCatching {
        if (!amount.isPositiveFinite()) return PaymentOutcome.REJECTED
        val currency = apiProvider()?.getCurrencyByName(TOKEN_CURRENCY) ?: return PaymentOutcome.UNKNOWN
        val before = currency.getBalance(playerId)
        if (!before.isFinite() || before < amount.toDouble()) return PaymentOutcome.REJECTED
        if (currency.transactionTax != 0.0) return PaymentOutcome.REJECTED
        val response = currency.withdrawPlayer(playerId, currency.currencyName, amount.toDouble(), "arc-ecojobs-shop")
        if (!response.transactionSuccess()) return PaymentOutcome.REJECTED
        val after = currency.getBalance(playerId)
        val expected = before - amount.toDouble()
        if (!after.isFinite() || kotlin.math.abs(after - expected) > BALANCE_TOLERANCE) PaymentOutcome.UNKNOWN
        else PaymentOutcome.ACCEPTED
    }.getOrDefault(PaymentOutcome.UNKNOWN)

    private fun BigDecimal.isPositiveFinite(): Boolean = signum() > 0 && toDouble().isFinite() && toDouble() > 0.0

    companion object {
        const val TOKEN_CURRENCY = "tokens"
        private const val BALANCE_TOLERANCE = 1.0e-7
    }
}
