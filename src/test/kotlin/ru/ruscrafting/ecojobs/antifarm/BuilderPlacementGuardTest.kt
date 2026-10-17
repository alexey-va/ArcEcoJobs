package ru.ruscrafting.ecojobs.antifarm

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class BuilderPlacementGuardTest : StringSpec({
    val player = UUID.randomUUID()
    val world = UUID.randomUUID()
    val first = BuilderPlacementGuard.Key(player, world, 1, 64, 1)
    val second = first.copy(x = 2)

    "new coordinates are payable while repeated placement is blocked for the cooldown" {
        val guard = BuilderPlacementGuard { BuilderPlacementSettings(cooldownMillis = 600_000, maximumEntries = 10) }

        guard.record(first, 0).allowed shouldBe true
        guard.record(first, 599_999).allowed shouldBe false
        guard.record(second, 599_999).allowed shouldBe true
        guard.record(first, 600_000).allowed shouldBe true
    }

    "a relog with the same UUID does not erase the placement history" {
        val guard = BuilderPlacementGuard { BuilderPlacementSettings(cooldownMillis = 600_000, maximumEntries = 10) }

        guard.record(first, 0).allowed shouldBe true
        // The guard has no session or quit state; a new Player object with this UUID
        // reaches the same key and remains blocked until the TTL expires.
        val sameUuidAfterRelog = first.copy()
        guard.record(sameUuidAfterRelog, 1).allowed shouldBe false
    }

    "full capacity fails closed without evicting non-expired coordinates" {
        val guard = BuilderPlacementGuard { BuilderPlacementSettings(cooldownMillis = 600_000, maximumEntries = 2) }

        guard.record(first, 0).allowed shouldBe true
        guard.record(second, 0).allowed shouldBe true
        val third = first.copy(x = 3)
        val denied = guard.record(third, 1)
        denied.allowed shouldBe false
        denied.capacityFull shouldBe true
        guard.record(first, 2).allowed shouldBe false
    }

    "a backwards clock does not expire a newer coordinate" {
        val guard = BuilderPlacementGuard { BuilderPlacementSettings(cooldownMillis = 600_000, maximumEntries = 10) }
        val newer = first.copy(x = 4)

        guard.record(first, 1_000).allowed shouldBe true
        guard.record(newer, 2_000).allowed shouldBe true
        guard.record(first, 1_500).allowed shouldBe false
        guard.record(first, 601_000).allowed shouldBe true
        guard.record(newer, 601_000).allowed shouldBe false
    }
    "full expiration and ring reuse preserve every active placement window" {
        val guard = BuilderPlacementGuard { BuilderPlacementSettings(cooldownMillis = 60_000, maximumEntries = 100_000) }
        repeat(100_000) { index -> guard.record(first.copy(x = index), 0).allowed shouldBe true }
        guard.record(first.copy(x = 100_000), 1).capacityFull shouldBe true
        repeat(100_000) { index -> guard.record(first.copy(x = index + 100_000), 60_000).allowed shouldBe true }
        guard.record(first.copy(x = 150_000), 60_001).allowed shouldBe false
        guard.record(first.copy(x = 1), 60_001).capacityFull shouldBe true
        guard.record(first.copy(x = 1), 120_000).allowed shouldBe true
    }

    "cooldown and capacity reloads match the original fail-closed window semantics" {
        var config = BuilderPlacementSettings(cooldownMillis = 60_000, maximumEntries = 32)
        val guard = BuilderPlacementGuard { config }
        val reference = linkedMapOf<BuilderPlacementGuard.Key, Long>()
        var previousNow = Long.MIN_VALUE
        var clock = 0L
        val random = java.util.Random(7)
        repeat(4_096) { index ->
            if (index % 31 == 0) config = config.copy(cooldownMillis = if (random.nextBoolean()) 60_000 else 120_000,
                maximumEntries = 1 + random.nextInt(64), enabled = random.nextInt(5) != 0)
            clock += random.nextInt(8_000) - 1_000
            val now = maxOf(previousNow, clock)
            previousNow = now
            val key = first.copy(x = random.nextInt(128))
            val expected = if (!config.enabled) BuilderPlacementGuard.Decision(key, now, true) else {
                val iterator = reference.entries.iterator()
                while (iterator.hasNext() && now - iterator.next().value >= config.cooldownMillis) iterator.remove()
                when {
                    key in reference -> BuilderPlacementGuard.Decision(key, now, false)
                    reference.size >= config.maximumEntries -> BuilderPlacementGuard.Decision(key, now, false, capacityFull = true)
                    else -> { reference[key] = now; BuilderPlacementGuard.Decision(key, now, true) }
                }
            }
            guard.record(key, clock) shouldBe expected
        }
    }

})
