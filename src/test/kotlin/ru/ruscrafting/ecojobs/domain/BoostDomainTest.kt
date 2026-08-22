package ru.ruscrafting.ecojobs.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Duration
import java.time.Instant
import java.util.UUID

class BoostDomainTest : StringSpec({
    "duration parser accepts compound bounded units" {
        DurationParser.parse("1d12h30m5s") shouldBe Duration.ofSeconds(131_405)
        DurationParser.parse("2h") shouldBe Duration.ofHours(2)
        DurationParser.parse("1h1h") shouldBe null
        DurationParser.parse("30") shouldBe null
        DurationParser.parse("0s") shouldBe null
    }

    "duration formatter uses Russian units on Russian surfaces" {
        val duration = Duration.ofDays(1).plusHours(2).plusMinutes(30)
        DurationParser.format(duration) shouldBe "1d 2h 30m"
        DurationParser.format(duration, russian = true) shouldBe "1д 2ч 30м"
    }

    "node codec round trips all signed dimensions" {
        val id = UUID.randomUUID()
        val encoded = BoostNodeCodec.encode(BoostType.MONEY, 175, "miner", id)
        BoostNodeCodec.decode(encoded) shouldBe DecodedBoostNode(BoostType.MONEY, 175, "miner", id)
        BoostNodeCodec.decode("other.permission") shouldBe null
        BoostNodeCodec.isValidScope("MINER_2") shouldBe true
        BoostNodeCodec.isValidScope("miner.two") shouldBe false
    }

    "effective multiplier uses the strongest applicable instance" {
        val now = Instant.now().plusSeconds(60)
        val boosts = listOf(
            BoostInstance(UUID.randomUUID(), BoostType.ALL, 150, setOf("all"), now),
            BoostInstance(UUID.randomUUID(), BoostType.XP, 200, setOf("miner"), now),
            BoostInstance(UUID.randomUUID(), BoostType.MONEY, 300, setOf("fisherman"), now),
        )
        Multipliers.effective(boosts, "miner", BoostType.XP) shouldBeExactly 2.0
        Multipliers.effective(boosts, "miner", BoostType.MONEY) shouldBeExactly 1.5
        Multipliers.effective(boosts, "builder", BoostType.XP) shouldBeExactly 1.5
    }

    "voucher signature detects every material payload mutation" {
        val signer = VoucherSigner(ByteArray(32) { it.toByte() })
        val payload = VoucherPayload(
            presetId = "workday",
            voucherId = UUID.randomUUID(),
            recipientId = UUID.randomUUID(),
            type = BoostType.ALL,
            multiplierBasisPoints = 150,
            durationSeconds = 3600,
            jobs = setOf("miner", "fisherman"),
            issuedAtEpochSecond = 1_787_260_000,
        )
        val signature = signer.sign(payload)
        signer.verify(payload, signature) shouldBe true
        signer.verify(payload.copy(durationSeconds = 7200), signature) shouldBe false
        signer.verify(payload.copy(recipientId = UUID.randomUUID()), signature) shouldBe false
        payload.jobs.sorted().shouldContainExactly("fisherman", "miner")
        signature shouldNotBe signer.sign(payload.copy(type = BoostType.XP))
    }
})
