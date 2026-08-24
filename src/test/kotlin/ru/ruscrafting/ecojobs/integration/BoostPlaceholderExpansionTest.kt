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
