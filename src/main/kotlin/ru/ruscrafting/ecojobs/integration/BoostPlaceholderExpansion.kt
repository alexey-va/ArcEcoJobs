package ru.ruscrafting.ecojobs.integration

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import ru.ruscrafting.ecojobs.boost.BoostService
import ru.ruscrafting.ecojobs.domain.BoostType
import java.math.BigDecimal

class BoostPlaceholderExpansion(
    private val version: String,
    private val boosts: BoostService,
) : PlaceholderExpansion() {
    override fun getIdentifier(): String = "arcecojobs"
    override fun getAuthor(): String = "RusCrafting"
    override fun getVersion(): String = version
    override fun persist(): Boolean = true
    override fun canRegister(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val online = player as? Player ?: return "1"
        val match = PARAMETER.matchEntire(params.lowercase()) ?: return null
        val jobId = match.groupValues[1]
        val multiplier = boosts.multiplier(online, jobId, BoostType.MONEY)
        return BigDecimal.valueOf(multiplier).stripTrailingZeros().toPlainString()
    }

    companion object {
        private val PARAMETER = Regex("boost_([a-z0-9_-]+)_money_multiplier")
    }
}
