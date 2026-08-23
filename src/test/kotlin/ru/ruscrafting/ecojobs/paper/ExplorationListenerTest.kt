package ru.ruscrafting.ecojobs.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.GameMode
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerMoveEvent

class ExplorationListenerTest : StringSpec({
    "discovery ranks preserve the old Jobs money and experience curves" {
        (1..5).map(ExplorationListener::moneyFactor) shouldBe listOf(1.0, 0.8, 0.6, 0.4, 0.1)
        (1..5).map(ExplorationListener::experienceFactor) shouldBe listOf(1.0, 1.0, 0.8, 0.5, 0.1)
        shouldThrow<IllegalStateException> { ExplorationListener.moneyFactor(6) }
        shouldThrow<IllegalStateException> { ExplorationListener.experienceFactor(6) }
    }

    "movement discovery observes only completed non-cancelled movement" {
        val handler = ExplorationListener::class.java
            .getDeclaredMethod("onMove", PlayerMoveEvent::class.java)
            .getAnnotation(EventHandler::class.java)

        handler.priority shouldBe EventPriority.MONITOR
        handler.ignoreCancelled shouldBe true
    }

    "creative spectator and ordinary flight preserve the old Jobs exclusions" {
        ExplorationListener.canExplore(GameMode.SURVIVAL, isFlying = false) shouldBe true
        ExplorationListener.canExplore(GameMode.ADVENTURE, isFlying = false) shouldBe true
        ExplorationListener.canExplore(GameMode.SURVIVAL, isFlying = true) shouldBe false
        ExplorationListener.canExplore(GameMode.CREATIVE, isFlying = false) shouldBe false
        ExplorationListener.canExplore(GameMode.SPECTATOR, isFlying = false) shouldBe false
    }
})
