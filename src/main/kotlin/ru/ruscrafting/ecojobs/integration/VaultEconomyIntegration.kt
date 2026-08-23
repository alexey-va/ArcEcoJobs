package ru.ruscrafting.ecojobs.integration

import com.willfp.eco.core.integrations.economy.EconomyIntegration
import net.milkbowl.vault.economy.Economy
import org.bukkit.OfflinePlayer
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
) : EconomyIntegration {
    override fun getPluginName(): String = "Vault"

    override fun hasAmount(player: OfflinePlayer, amount: BigDecimal): Boolean =
        economy.has(player, amount.toDouble())

    override fun giveMoney(player: OfflinePlayer, amount: BigDecimal): Boolean =
        economy.depositPlayer(player, amount.toDouble()).transactionSuccess()

    override fun removeMoney(player: OfflinePlayer, amount: BigDecimal): Boolean =
        economy.withdrawPlayer(player, amount.toDouble()).transactionSuccess()

    override fun getExactBalance(player: OfflinePlayer): BigDecimal =
        BigDecimal.valueOf(economy.getBalance(player))
}
