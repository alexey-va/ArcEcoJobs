package ru.ruscrafting.ecojobs.earnings

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.ecojobs.config.EarningsSettings
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EarningsServiceTest : StringSpec({
    val instant = Instant.parse("2026-08-24T01:15:00Z")
    val clock = Clock.fixed(instant, ZoneId.of("UTC"))
    val plugin = mockk<JavaPlugin>(relaxed = true)

    "repeated events in one player job hour are flushed as one aggregate" {
        val store = RecordingEarningsStore()
        val service = EarningsService(plugin, settings(), store, clock)
        val player = UUID.randomUUID()

        service.recordMoney(player, "builder", BigDecimal("4.25"))
        service.recordMoney(player, "builder", BigDecimal("1.75"))
        service.recordXp(player, "builder", 2.5)
        service.flush().join()

        store.writes.size shouldBe 1
        store.writes.single().single() shouldBe HourlyEarnings(
            player,
            "builder",
            EarningsService.epochHour(instant),
            EarningsTotals(BigDecimal("6.000000"), BigDecimal("2.500000"), 2, 1),
        )
        service.close()
    }

    "a report flushes pending values before reading them" {
        val store = RecordingEarningsStore()
        val service = EarningsService(plugin, settings(), store, clock)
        val player = UUID.randomUUID()
        service.recordMoney(player, "miner", BigDecimal("3"))
        service.recordXp(player, "miner", 7.0)

        val report = service.report(player, "miner").join()

        report.total.money shouldBe BigDecimal("3.000000")
        report.total.xp shouldBe BigDecimal("7.000000")
        store.reads shouldBe 1
        service.report(player, "miner").join()
        store.reads shouldBe 1
        service.recordMoney(player, "miner", BigDecimal("2"))
        service.report(player, "miner").join().total.money shouldBe BigDecimal("5.000000")
        store.reads shouldBe 2
        service.close()
    }

    "an empty report is cached, then invalidated by the first event" {
        val store = RecordingEarningsStore()
        val service = EarningsService(plugin, settings(), store, clock)
        val player = UUID.randomUUID()

        service.report(player, "miner").join().total.money shouldBe BigDecimal.ZERO
        service.report(player, "miner").join().total.money shouldBe BigDecimal.ZERO
        store.reads shouldBe 1

        service.recordMoney(player, "miner", BigDecimal.ONE)
        service.report(player, "miner").join().total.money shouldBe BigDecimal("1.000000")
        store.reads shouldBe 2
        service.close()
    }

    "a failed write retries the exact same idempotency batch" {
        val store = RecordingEarningsStore().apply { failNextWrite = true }
        val service = EarningsService(plugin, settings(), store, clock)
        service.recordMoney(UUID.randomUUID(), "builder", BigDecimal.ONE)

        runCatching { service.flush().join() }.isFailure shouldBe true
        service.flush().join()

        store.attemptedBatchIds.size shouldBe 2
        store.attemptedBatchIds.toSet().size shouldBe 1
        store.writes.size shouldBe 1
        service.close()
    }

    "a report does not cache a database view after its pending flush failed" {
        val store = RecordingEarningsStore().apply { failNextWrite = true }
        val service = EarningsService(plugin, settings(), store, clock)
        val player = UUID.randomUUID()
        service.recordMoney(player, "builder", BigDecimal.ONE)

        service.report(player, "builder").join().total.money shouldBe BigDecimal.ZERO
        service.report(player, "builder").join().total.money shouldBe BigDecimal("1.000000")

        store.reads shouldBe 2
        store.attemptedBatchIds.toSet().size shouldBe 1
        service.close()
    }

    "the in-memory buffer never exceeds its configured unique bucket bound" {
        val store = RecordingEarningsStore()
        val service = EarningsService(plugin, settings(maximumPendingBuckets = 256), store, clock)
        val player = UUID.randomUUID()

        repeat(300) { index -> service.recordMoney(player, "job-$index", BigDecimal.ONE) }

        service.pendingBucketCount() shouldBe 256
        service.close()
    }

    "invalid job IDs and values outside the persisted decimal contract are ignored" {
        val store = RecordingEarningsStore()
        val service = EarningsService(plugin, settings(), store, clock)
        val player = UUID.randomUUID()

        service.recordMoney(player, "not a job", BigDecimal.ONE)
        service.recordMoney(player, "builder", BigDecimal("1000000000000000000"))
        service.recordXp(player, "builder", Double.POSITIVE_INFINITY)

        service.pendingBucketCount() shouldBe 0
        service.close()
    }

    "sub-micro earnings are aggregated before persisted rounding" {
        val store = RecordingEarningsStore()
        val service = EarningsService(plugin, settings(), store, clock)
        val player = UUID.randomUUID()

        service.recordMoney(player, "builder", BigDecimal("0.0000004"))
        service.recordMoney(player, "builder", BigDecimal("0.0000004"))
        service.flush().join()

        store.writes.single().single().totals shouldBe EarningsTotals(
            money = BigDecimal("0.000001"),
            xp = BigDecimal("0.000000"),
            moneyEvents = 2,
        )
        service.close()
    }

    "close rejects new observations before draining the final asynchronous write" {
        val store = BlockingCloseStore()
        val service = EarningsService(plugin, settings(), store, clock)
        val player = UUID.randomUUID()
        service.recordMoney(player, "builder", BigDecimal.ONE)

        val closing = CompletableFuture.runAsync(service::close)
        store.started.await(5, TimeUnit.SECONDS) shouldBe true
        service.recordMoney(player, "builder", BigDecimal.TEN)
        store.release.complete(Unit)
        closing.get(5, TimeUnit.SECONDS)

        store.writes.flatten().single().totals shouldBe EarningsTotals(
            money = BigDecimal("1.000000"),
            xp = BigDecimal("0.000000"),
            moneyEvents = 1,
        )
    }
})

private class BlockingCloseStore : HourlyEarningsStore {
    val started = CountDownLatch(1)
    val release = CompletableFuture<Unit>()
    val writes = mutableListOf<List<HourlyEarnings>>()

    override fun write(batchId: UUID, entries: List<HourlyEarnings>): CompletableFuture<Unit> {
        started.countDown()
        return release.thenApply {
            writes += entries
            Unit
        }
    }

    override fun read(playerId: UUID, jobId: String, fromEpochHour: Long) =
        CompletableFuture.completedFuture(emptyList<HourlyEarnings>())

    override fun prune(beforeEpochHour: Long) = CompletableFuture.completedFuture(0)
}

private class RecordingEarningsStore : HourlyEarningsStore {
    val writes = mutableListOf<List<HourlyEarnings>>()
    val attemptedBatchIds = mutableListOf<UUID>()
    var failNextWrite = false
    var reads = 0
    private val rows = linkedMapOf<Triple<UUID, String, Long>, HourlyEarnings>()
    private val batches = mutableSetOf<UUID>()

    override fun write(batchId: UUID, entries: List<HourlyEarnings>): CompletableFuture<Unit> {
        attemptedBatchIds += batchId
        if (failNextWrite) {
            failNextWrite = false
            return CompletableFuture.failedFuture(IllegalStateException("simulated outage"))
        }
        if (batches.add(batchId)) {
            writes += entries
            entries.forEach { entry ->
                val key = Triple(entry.playerId, entry.jobId, entry.epochHour)
                val existing = rows[key]
                rows[key] = entry.copy(totals = (existing?.totals ?: EarningsTotals()) + entry.totals)
            }
        }
        return CompletableFuture.completedFuture(Unit)
    }

    override fun read(
        playerId: UUID,
        jobId: String,
        fromEpochHour: Long,
    ): CompletableFuture<List<HourlyEarnings>> {
        reads++
        return CompletableFuture.completedFuture(
            rows.values.filter { it.playerId == playerId && it.jobId == jobId && it.epochHour >= fromEpochHour },
        )
    }

    override fun prune(beforeEpochHour: Long): CompletableFuture<Int> {
        val expired = rows.filterKeys { it.third < beforeEpochHour }.keys
        expired.forEach(rows::remove)
        return CompletableFuture.completedFuture(expired.size)
    }
}

private fun settings(maximumPendingBuckets: Int = 4_096) = EarningsSettings(
    enabled = true,
    retentionDays = 30,
    flushInterval = Duration.ofSeconds(10),
    cacheDuration = Duration.ofSeconds(15),
    cleanupInterval = Duration.ofHours(6),
    maximumPendingBuckets = maximumPendingBuckets,
    zoneId = ZoneId.of("Europe/Moscow"),
)
