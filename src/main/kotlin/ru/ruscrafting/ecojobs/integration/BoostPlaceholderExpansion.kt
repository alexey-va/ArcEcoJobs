package ru.ruscrafting.ecojobs.integration

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import ru.ruscrafting.ecojobs.boost.BoostService
import ru.ruscrafting.ecojobs.domain.BoostType
import ru.ruscrafting.ecojobs.earnings.MoneyAttribution
import java.math.BigDecimal

class BoostPlaceholderExpansion(
    private val version: String,
    private val boosts: BoostService,
    private val moneyAttribution: MoneyAttribution? = null,
    private val rewardAllowed: (Player, String) -> Boolean = { _, _ -> true },
) : PlaceholderExpansion() {
    override fun getIdentifier(): String = "arcecojobs"
    override fun getAuthor(): String = "RusCrafting"
    override fun getVersion(): String = version
    override fun persist(): Boolean = true
    override fun canRegister(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val normalized = params.lowercase()
        WORK_ALLOWED.matchEntire(normalized)?.let { match ->
            val online = player as? Player ?: return "0"
            return if (rewardAllowed(online, match.groupValues[1])) "1" else "0"
        }
        MONEY_MULTIPLIER.matchEntire(normalized)?.let { match ->
            moneyAttribution?.clear()
            val online = player as? Player ?: return "1"
            if (!rewardAllowed(online, match.groupValues[1])) return "0"
            val multiplier = boosts.multiplier(online, match.groupValues[1], BoostType.MONEY)
            return BigDecimal.valueOf(multiplier).stripTrailingZeros().toPlainString()
        }
        MONEY_MARKER.matchEntire(normalized)?.let { match ->
            moneyAttribution?.clear()
            player?.let { moneyAttribution?.mark(it.uniqueId, match.groupValues[1]) }
            return "1"
        }
        return null
    }

    companion object {
        private val WORK_ALLOWED = Regex("work_([a-z0-9_-]+)_allowed")
        private val MONEY_MULTIPLIER = Regex("boost_([a-z0-9_-]+)_money_multiplier")
        private val MONEY_MARKER = Regex("earnings_([a-z0-9_-]+)_money_marker")
    }
}
