package ru.ruscrafting.ecojobs.earnings

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.util.concurrent.TimeUnit

class MoneyAttributionTest : StringSpec({
    "a marker is consumed exactly once by the matching player" {
        var now = 100L
        val player = UUID.randomUUID()
        val attribution = MoneyAttribution({ now }, TimeUnit.SECONDS.toNanos(1))

        attribution.mark(player, "Miner")

        attribution.consume(player) shouldBe "miner"
        attribution.consume(player).shouldBeNull()
    }

    "a marker cannot leak to another player or a later payout" {
        var now = 100L
        val player = UUID.randomUUID()
        val attribution = MoneyAttribution({ now }, 10L)

        attribution.mark(player, "builder")
        attribution.consume(UUID.randomUUID()).shouldBeNull()
        attribution.consume(player).shouldBeNull()

        attribution.mark(player, "builder")
        now = 111L
        attribution.consume(player).shouldBeNull()
    }

    "the default marker window only covers the immediate synchronous deposit" {
        var now = 1_000L
        val player = UUID.randomUUID()
        val attribution = MoneyAttribution(nanoTime = { now })

        attribution.mark(player, "builder")
        now += MoneyAttribution.DEFAULT_SYNCHRONOUS_GAP_NANOS + 1L

        attribution.consume(player).shouldBeNull()
    }
})
