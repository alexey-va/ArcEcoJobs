package ru.ruscrafting.ecojobs.integration

import com.willfp.eco.core.integrations.economy.EconomyIntegration
import net.milkbowl.vault.economy.Economy
import org.bukkit.OfflinePlayer
import ru.ruscrafting.ecojobs.earnings.MoneyAttribution
import ru.ruscrafting.ecojobs.config.ShopCurrency
import ru.ruscrafting.ecojobs.shop.ShopPaymentGateway
import java.math.BigDecimal

/**
 * Late-bound eco economy integration backed by the active Vault provider.
 *
 * eco loads at STARTUP and can initialize before a Vault economy provider such
 * as RedisEconomy has registered. Registering this bridge with the canonical
 * `Vault` ID replaces eco's missing or stale startup integration.
 */
class VaultEconomyIntegration(
    private val economy: Economy,
    private val moneyAttribution: MoneyAttribution? = null,
    private val recordEarnings: (OfflinePlayer, String, BigDecimal) -> Unit = { _, _, _ -> },
    private val auditBridge: JobAuditBridge = NoopJobAuditBridge,
    private val rewardAllowed: (OfflinePlayer, String) -> Boolean = { _, _ -> true },
) : EconomyIntegration {
    override fun getPluginName(): String = "Vault"

    override fun hasAmount(player: OfflinePlayer, amount: BigDecimal): Boolean =
        economy.has(player, amount.toDouble())

    override fun giveMoney(player: OfflinePlayer, amount: BigDecimal): Boolean {
        val playerId = moneyAttribution?.let { player.uniqueId }
        val jobId = playerId?.let { moneyAttribution.consume(it) }
        if (jobId != null && (amount.signum() <= 0 || !rewardAllowed(player, jobId))) return false
        val auditToken = jobId?.let { auditBridge.mark(requireNotNull(playerId), it, amount) }
        val response =
            try {
                economy.depositPlayer(player, amount.toDouble())
            } catch (failure: Throwable) {
                if (auditToken != null) auditBridge.cancel(requireNotNull(playerId), auditToken)
                throw failure
            }
        val success = response.transactionSuccess()
        if (!success && auditToken != null) auditBridge.cancel(requireNotNull(playerId), auditToken)
        val deposited = response.amount.takeIf(Double::isFinite)?.let(BigDecimal::valueOf)
        if (success && jobId != null && deposited != null && deposited.signum() > 0) {
            recordEarnings(player, jobId, deposited)
        }
        return success
    }

    override fun removeMoney(player: OfflinePlayer, amount: BigDecimal): Boolean =
        economy.withdrawPlayer(player, amount.toDouble()).transactionSuccess()

    override fun getExactBalance(player: OfflinePlayer): BigDecimal =
        BigDecimal.valueOf(economy.getBalance(player))
}

class VaultShopPaymentGateway(private val economy: VaultEconomyIntegration) : ru.ruscrafting.ecojobs.shop.ShopPaymentGateway {
    override fun has(playerId: java.util.UUID, amount: BigDecimal): Boolean = economy.hasAmount(org.bukkit.Bukkit.getOfflinePlayer(playerId), amount)
    override fun charge(playerId: java.util.UUID, amount: BigDecimal): ru.ruscrafting.ecojobs.shop.PaymentOutcome = try {
        if (economy.removeMoney(org.bukkit.Bukkit.getOfflinePlayer(playerId), amount)) ru.ruscrafting.ecojobs.shop.PaymentOutcome.ACCEPTED
        else ru.ruscrafting.ecojobs.shop.PaymentOutcome.REJECTED
    } catch (_: Throwable) {
        ru.ruscrafting.ecojobs.shop.PaymentOutcome.UNKNOWN
    }
}

class RoutedShopPaymentGateway(
    private val money: ShopPaymentGateway,
    private val tokens: ShopPaymentGateway?,
) : ShopPaymentGateway {
    override fun forCurrency(currency: ShopCurrency): ShopPaymentGateway? = when (currency) {
        ShopCurrency.MONEY -> money
        ShopCurrency.TOKENS -> tokens
    }

    override fun has(playerId: java.util.UUID, amount: BigDecimal): Boolean = error("Currency must be selected before payment")
    override fun charge(playerId: java.util.UUID, amount: BigDecimal): ru.ruscrafting.ecojobs.shop.PaymentOutcome =
        error("Currency must be selected before payment")
}
