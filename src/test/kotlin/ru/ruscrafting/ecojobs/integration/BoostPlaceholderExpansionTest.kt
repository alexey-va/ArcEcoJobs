package ru.ruscrafting.ecojobs.integration

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import ru.ruscrafting.ecojobs.boost.BoostService
import ru.ruscrafting.ecojobs.domain.BoostType
import ru.ruscrafting.ecojobs.earnings.MoneyAttribution
import java.util.UUID

class BoostPlaceholderExpansionTest : StringSpec({
    "earnings marker preserves the formula value and carries the exact job" {
        val playerId = UUID.randomUUID()
        val player = mockk<OfflinePlayer>()
        every { player.uniqueId } returns playerId
        val attribution = MoneyAttribution()
        val expansion = BoostPlaceholderExpansion("test", mockk<BoostService>(), attribution)

        expansion.onRequest(player, "earnings_builder_money_marker") shouldBe "1"
        attribution.consume(playerId) shouldBe "builder"
        expansion.onRequest(player, "not_an_arcecojobs_placeholder").shouldBeNull()
    }

    "pre-action XP and money guards match and a denied boost cannot restore the payout" {
        val player = mockk<Player>()
        val boosts = mockk<BoostService>()
        var allowed = false
        val expansion = BoostPlaceholderExpansion("test", boosts, rewardAllowed = { _, _ -> allowed })
        expansion.onRequest(player, "work_slayer_allowed") shouldBe "0"
        expansion.onRequest(player, "boost_slayer_money_multiplier") shouldBe "0"
        expansion.onRequest(null, "work_slayer_allowed") shouldBe "0"
        allowed = true
        every { boosts.multiplier(player, "slayer", BoostType.MONEY) } returns 10.0
        expansion.onRequest(player, "work_slayer_allowed") shouldBe "1"
        expansion.onRequest(player, "boost_slayer_money_multiplier") shouldBe "10"
    }

    "the next job formula clears an abandoned marker before calculating" {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>()
        val boosts = mockk<BoostService>()
        every { player.uniqueId } returns playerId
        every { boosts.multiplier(player, "miner", BoostType.MONEY) } returns 1.0
        val attribution = MoneyAttribution()
        val expansion = BoostPlaceholderExpansion("test", boosts, attribution)
        attribution.mark(playerId, "builder")

        expansion.onRequest(player, "boost_miner_money_multiplier") shouldBe "1"
        attribution.consume(playerId).shouldBeNull()
    }
})
