package ru.ruscrafting.ecojobs.paper

import com.willfp.ecojobs.jobs.Job
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.ecojobs.exploration.DiscoveryClaim
import ru.ruscrafting.ecojobs.exploration.DiscoveryClaimResult
import ru.ruscrafting.ecojobs.exploration.DiscoveryDeliveryStatus
import ru.ruscrafting.ecojobs.exploration.DiscoveryKey
import ru.ruscrafting.ecojobs.exploration.DiscoveryLedger
import ru.ruscrafting.ecojobs.exploration.ExplorationTrigger
import ru.ruscrafting.ecojobs.exploration.MySqlDiscoveryLedger
import ru.ruscrafting.ecojobs.integration.EcoJobsBridge
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

class ExplorationListener(
    private val plugin: JavaPlugin,
    private val ecoJobs: EcoJobsBridge,
    private val explorerJob: Job,
    private val ledger: DiscoveryLedger,
    private val trigger: ExplorationTrigger,
    private val maximumInFlight: Int,
) : Listener {
    private data class Attempt(val key: DiscoveryKey, val playerId: UUID)
    private data class Delivery(
        val completion: CompletableFuture<Unit> = CompletableFuture(),
        val finishing: AtomicBoolean = AtomicBoolean(),
    )

    private val inFlight = ConcurrentHashMap.newKeySet<Attempt>()
    private val operations = ConcurrentHashMap.newKeySet<CompletableFuture<Unit>>()
    private val deliveries = ConcurrentHashMap<DiscoveryClaim, Delivery>()

    @Volatile
    private var stopped = false

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (stopped || event is PlayerTeleportEvent) return
        val from = event.from
        val to = event.to
        if (from.world.uid != to.world.uid) return
        if ((from.blockX shr 4) == (to.blockX shr 4) && (from.blockZ shr 4) == (to.blockZ shr 4)) return
        val player = event.player
        if (!ecoJobs.active(player, explorerJob)) return
        if (!canExplore(player.gameMode, player.isFlying)) return

        val attempt = Attempt(
            DiscoveryKey(to.world.uid, to.blockX shr 4, to.blockZ shr 4),
            player.uniqueId,
        )
        if (inFlight.size >= maximumInFlight || !inFlight.add(attempt)) return
        val operation = ledger.claim(attempt.key, attempt.playerId).handle { result, failure ->
            if (failure != null) {
                plugin.logger.log(Level.WARNING, "Could not claim a chunk discovery", failure)
                null
            } else {
                (result as? DiscoveryClaimResult.Acquired)?.claim
            }
        }.thenCompose { claim ->
            if (claim == null) CompletableFuture.completedFuture(Unit) else scheduleDelivery(claim)
        }
        operations.add(operation)
        operation.whenComplete { _, failure ->
            operations.remove(operation)
            inFlight.remove(attempt)
            if (failure != null) {
                plugin.logger.log(Level.WARNING, "Could not complete a chunk discovery operation", failure)
            }
        }
    }

    fun shutdown(): CompletableFuture<Unit> {
        stopped = true
        deliveries.forEach { (claim, delivery) ->
            finish(claim, delivery, DiscoveryDeliveryStatus.ABANDONED)
        }
        return CompletableFuture.allOf(*operations.toTypedArray()).thenApply { Unit }
    }

    private fun scheduleDelivery(claim: DiscoveryClaim): CompletableFuture<Unit> {
        val delivery = Delivery()
        deliveries[claim] = delivery
        if (stopped || !plugin.isEnabled) {
            finish(claim, delivery, DiscoveryDeliveryStatus.ABANDONED)
            return delivery.completion
        }
        runCatching {
            plugin.server.scheduler.runTask(plugin, Runnable { deliver(claim, delivery) })
        }.onFailure { failure ->
            plugin.logger.log(Level.WARNING, "Could not schedule a chunk discovery reward", failure)
            finish(claim, delivery, DiscoveryDeliveryStatus.ABANDONED)
        }
        if (stopped) finish(claim, delivery, DiscoveryDeliveryStatus.ABANDONED)
        return delivery.completion
    }

    private fun deliver(claim: DiscoveryClaim, completion: Delivery) {
        check(Bukkit.isPrimaryThread()) { "chunk discovery rewards must be delivered on the server thread" }
        val player = Bukkit.getPlayer(claim.playerId)
        if (stopped || player == null || !player.isOnline || !ecoJobs.active(player, explorerJob)) {
            finish(claim, completion, DiscoveryDeliveryStatus.ABANDONED)
            return
        }
        val dispatch = runCatching {
            trigger.dispatch(
                player,
                claim.rank,
                experienceFactor(claim.rank),
                moneyFactor(claim.rank),
            )
        }
        if (dispatch.isFailure) {
            plugin.logger.log(Level.WARNING, "Could not dispatch a chunk discovery reward", dispatch.exceptionOrNull())
        }
        finish(
            claim,
            completion,
            if (dispatch.isSuccess) DiscoveryDeliveryStatus.APPLIED else DiscoveryDeliveryStatus.ABANDONED,
        )
    }

    private fun finish(claim: DiscoveryClaim, delivery: Delivery, status: DiscoveryDeliveryStatus) {
        if (!delivery.finishing.compareAndSet(false, true)) return
        val finishing = runCatching { ledger.finish(claim, status) }.getOrElse { failure ->
            plugin.logger.log(Level.WARNING, "Could not start chunk discovery completion for rank ${claim.rank}", failure)
            deliveries.remove(claim, delivery)
            delivery.completion.complete(Unit)
            return
        }
        finishing.whenComplete { finished, failure ->
            if (failure != null || finished != true) {
                plugin.logger.log(
                    Level.WARNING,
                    "Chunk discovery completion is uncertain for rank ${claim.rank}",
                    failure,
                )
            }
            deliveries.remove(claim, delivery)
            delivery.completion.complete(Unit)
        }
    }

    companion object {
        const val JOB_ID = "explorer"

        internal fun moneyFactor(rank: Int): Double = when (rank) {
            1 -> 1.0
            2 -> 0.8
            3 -> 0.6
            4 -> 0.4
            5 -> 0.1
            else -> error("invalid chunk discovery rank: $rank")
        }.also {
            require(rank <= MySqlDiscoveryLedger.MAX_DISCOVERERS)
        }

        internal fun experienceFactor(rank: Int): Double = when (rank) {
            1, 2 -> 1.0
            3 -> 0.8
            4 -> 0.5
            5 -> 0.1
            else -> error("invalid chunk discovery rank: $rank")
        }

        internal fun canExplore(gameMode: GameMode, isFlying: Boolean): Boolean =
            (gameMode == GameMode.SURVIVAL || gameMode == GameMode.ADVENTURE) && !isFlying
    }
}
