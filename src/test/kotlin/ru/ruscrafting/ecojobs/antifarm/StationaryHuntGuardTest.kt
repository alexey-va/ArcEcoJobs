package ru.ruscrafting.ecojobs.antifarm

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class StationaryHuntGuardTest : StringSpec({
    val player = UUID.randomUUID()
    val world = UUID.randomUUID()
    val site = StationaryHuntGuard.Position(world, 0.0, 70.0, 0.0)
    fun settings() = HuntGuardSettings()

    "a sustained stationary farm is blocked at both evidence thresholds and keeps cooldown on re-entry" {
        val guard = StationaryHuntGuard(::settings)
        repeat(120) { guard.recordKill(player, site, site, it * 1000L) shouldBe true }
        guard.recordKill(player, site, site, 180_000) shouldBe false
        guard.recordKill(player, site.copy(x = 50.0), site.copy(x = 50.0), 181_000) shouldBe true
        guard.recordKill(player, site, site, 182_000) shouldBe false
        // No quit/join reset exists: the same UUID keeps the site after reconnecting.
        guard.recordKill(player, site, site, 1_979_999) shouldBe false
        guard.recordKill(player, site, site, 1_980_000) shouldBe true
    }
    "short combat bursts and sparse local encounters remain payable" {
        val burst = StationaryHuntGuard(::settings)
        repeat(500) { burst.recordKill(player, site, site, it * 100L) shouldBe true }
        val sparse = StationaryHuntGuard(::settings)
        repeat(119) { sparse.recordKill(player, site, site, it * 2000L) shouldBe true }
    }
    "ordinary moving hunt is payable while tiny movement and looking around cannot reset evidence" {
        val active = StationaryHuntGuard(::settings)
        repeat(300) {
            val moving = site.copy(x = it.toDouble())
            active.recordKill(player, moving, moving, it * 2000L) shouldBe true
        }
        val jitter = StationaryHuntGuard(::settings)
        repeat(120) { i ->
            val allowed = jitter.recordKill(player, site.copy(x = (i % 2).toDouble()), site, i * 2000L)
            allowed shouldBe (i < 119)
        }
    }
    "other players and worlds do not inherit a farm decision" {
        val guard = StationaryHuntGuard(::settings)
        repeat(120) { guard.recordKill(player, site, site, it * 2000L) }
        guard.recordKill(UUID.randomUUID(), site, site, 240_000) shouldBe true
        val anotherWorld = site.copy(world = UUID.randomUUID())
        guard.recordKill(player, anotherWorld, anotherWorld, 240_000) shouldBe true
        guard.recordKill(player, site, site, 240_000) shouldBe false
    }
    "idle observation expires without granting an early escape from a flagged cooldown" {
        val guard = StationaryHuntGuard(::settings)
        repeat(119) { guard.recordKill(player, site, site, it * 2000L) }
        guard.recordKill(player, site, site, 1_000_000) shouldBe true
    }
    "reload toggles policy without erasing an already observed site" {
        var config = settings()
        val guard = StationaryHuntGuard { config }
        repeat(120) { guard.recordKill(player, site, site, it * 2000L) }
        config = config.copy(enabled = false)
        guard.recordKill(player, site, site, 240_000) shouldBe true
        config = config.copy(enabled = true)
        guard.recordKill(player, site, site, 240_000) shouldBe false
    }
})
