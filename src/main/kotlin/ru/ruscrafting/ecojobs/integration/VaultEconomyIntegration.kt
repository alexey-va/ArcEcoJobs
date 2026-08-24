package ru.ruscrafting.ecojobs.integration

import com.willfp.eco.core.integrations.economy.EconomyIntegration
import net.milkbowl.vault.economy.Economy
import org.bukkit.OfflinePlayer
import ru.ruscrafting.ecojobs.earnings.MoneyAttribution
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
) : EconomyIntegration {
    override fun getPluginName(): String = "Vault"

    override fun hasAmount(player: OfflinePlayer, amount: BigDecimal): Boolean =
        economy.has(player, amount.toDouble())

    override fun giveMoney(player: OfflinePlayer, amount: BigDecimal): Boolean {
        val jobId = moneyAttribution?.consume(player.uniqueId)
        val response = economy.depositPlayer(player, amount.toDouble())
        val success = response.transactionSuccess()
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
