package ru.ruscrafting.ecojobs.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant
import java.util.UUID

class BoostStackingTest : StringSpec({
    val now = Instant.parse("2026-08-23T12:00:00Z")

    fun payload(
        type: BoostType = BoostType.XP,
        multiplier: Int = 200,
        seconds: Long = 1_800,
        jobs: Set<String> = setOf("all"),
    ) = VoucherPayload(
        presetId = "test",
        voucherId = UUID.randomUUID(),
        type = type,
        multiplierBasisPoints = multiplier,
        durationSeconds = seconds,
        jobs = jobs,
        issuedAtEpochSecond = now.epochSecond,
    )

    "matching effects add their remaining time sequentially" {
        val first = BoostInstance(UUID.randomUUID(), BoostType.XP, 200, setOf("all"), now.plusSeconds(1_800))
        val second = BoostInstance(UUID.randomUUID(), BoostType.XP, 200, setOf("all"), now.plusSeconds(600))

        val decision = BoostStacking.decide(
            listOf(first, second),
            payload(),
            now,
            Duration.ofDays(365),
        ).shouldBeInstanceOf<BoostApplicationDecision.Stack>()

        decision.totalRemaining shouldBe Duration.ofMinutes(70)
        decision.replacedInstanceIds shouldBe setOf(first.instanceId, second.instanceId)
    }

    "another boost type is rejected" {
        val active = BoostInstance(UUID.randomUUID(), BoostType.MONEY, 150, setOf("all"), now.plusSeconds(900))

        BoostStacking.decide(listOf(active), payload(), now, Duration.ofDays(365))
            .shouldBeInstanceOf<BoostApplicationDecision.TypeConflict>()
            .remaining shouldBe Duration.ofMinutes(15)
    }

    "same type with another multiplier or scope is rejected" {
        val active = BoostInstance(UUID.randomUUID(), BoostType.XP, 150, setOf("miner"), now.plusSeconds(900))

        BoostStacking.decide(listOf(active), payload(), now, Duration.ofDays(365))
            .shouldBeInstanceOf<BoostApplicationDecision.EffectConflict>()
    }

    "stacking limit keeps the new voucher unused" {
        val active = BoostInstance(UUID.randomUUID(), BoostType.XP, 200, setOf("all"), now.plus(Duration.ofDays(365)))

        BoostStacking.decide(listOf(active), payload(), now, Duration.ofDays(365)) shouldBe
            BoostApplicationDecision.LimitExceeded
    }
})
