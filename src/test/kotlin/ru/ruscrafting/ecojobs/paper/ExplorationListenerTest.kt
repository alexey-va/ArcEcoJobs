package ru.ruscrafting.ecojobs.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import com.willfp.ecojobs.jobs.Job
import org.bukkit.GameMode
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerMoveEvent
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ecojobs.exploration.DiscoveryClaim
import ru.ruscrafting.ecojobs.exploration.DiscoveryClaimResult
import ru.ruscrafting.ecojobs.exploration.DiscoveryDeliveryStatus
import ru.ruscrafting.ecojobs.exploration.DiscoveryKey
import ru.ruscrafting.ecojobs.exploration.DiscoveryLedger
import ru.ruscrafting.ecojobs.exploration.ExplorationTrigger
import ru.ruscrafting.ecojobs.integration.EcoJobsBridge
import java.util.UUID
import java.util.concurrent.CompletableFuture

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

    "shutdown waits for an in-flight claim and abandons it before storage closes" {
        MockBukkitTestRuntime.open().use { paper ->
            requireSupportedMockBukkit {
                val plugin = paper.createSimplePlugin("ArcEcoJobsExplorationShutdownTest")
                val player = paper.addPlayer("ExplorerQA")
                val job = mockk<Job>()
                val ecoJobs = mockk<EcoJobsBridge> {
                    every { active(player, job) } returns true
                }
                val ledger = ControlledDiscoveryLedger()
                val listener = ExplorationListener(
                    plugin,
                    ecoJobs,
                    job,
                    ledger,
                    mockk<ExplorationTrigger>(relaxed = true),
                    maximumInFlight = 4,
                )
                val from = player.location.clone().apply { x = 0.0 }
                val to = from.clone().apply { x = 16.0 }

                listener.onMove(PlayerMoveEvent(player, from, to))
                val shutdown = listener.shutdown()
                shutdown.isDone shouldBe false

                val claim = DiscoveryClaim(
                    DiscoveryKey(to.world.uid, 1, 0),
                    player.uniqueId,
                    1,
                )
                ledger.claim.complete(DiscoveryClaimResult.Acquired(claim))
                shutdown.join()

                ledger.finishes shouldBe listOf(claim to DiscoveryDeliveryStatus.ABANDONED)
            }
        }
    }
})

private class ControlledDiscoveryLedger : DiscoveryLedger {
    override val available: Boolean = true
    val claim = CompletableFuture<DiscoveryClaimResult>()
    val finishes = mutableListOf<Pair<DiscoveryClaim, DiscoveryDeliveryStatus>>()

    override fun claim(key: DiscoveryKey, playerId: UUID): CompletableFuture<DiscoveryClaimResult> = claim

    override fun finish(
        claim: DiscoveryClaim,
        status: DiscoveryDeliveryStatus,
    ): CompletableFuture<Boolean> {
        finishes += claim to status
        return CompletableFuture.completedFuture(true)
    }
}
