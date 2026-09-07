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
})
