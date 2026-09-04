package ru.ruscrafting.ecojobs.earnings

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import ru.ruscrafting.ecojobs.config.EarningsSettings
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Level

class EarningsService(
    private val plugin: JavaPlugin,
    private val settings: EarningsSettings,
    private val store: HourlyEarningsStore,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private data class BucketKey(val playerId: UUID, val jobId: String, val epochHour: Long)
    private data class PendingBatch(val id: UUID, val entries: List<HourlyEarnings>)
    private data class CachedReport(val validUntil: Instant, val report: EarningsReport)

    private val lock = Any()
    private val pending = linkedMapOf<BucketKey, EarningsTotals>()
    private val cache = mutableMapOf<Pair<UUID, String>, CachedReport>()
    private val revisions = mutableMapOf<Pair<UUID, String>, Long>()
    private var retryBatch: PendingBatch? = null
    private var writeInFlight: CompletableFuture<Unit>? = null
    private var lastOverflowWarning = Instant.EPOCH
    private var lastWriteWarning = Instant.EPOCH
    private var closed = false
    private var flushTask: BukkitTask? = null
    private var cleanupTask: BukkitTask? = null

    fun start() {
        check(flushTask == null && cleanupTask == null) { "earnings service is already started" }
        flushTask = plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable { flush() },
            settings.flushInterval.toTicks(),
            settings.flushInterval.toTicks(),
        )
        cleanupTask = plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable { prune() },
            INITIAL_CLEANUP_DELAY_TICKS,
            settings.cleanupInterval.toTicks(),
        )
    }

    fun recordMoney(playerId: UUID, jobId: String, amount: BigDecimal) {
        val accepted = accept(amount) ?: return
        record(playerId, jobId, EarningsTotals(money = accepted, moneyEvents = 1))
    }

    fun recordXp(playerId: UUID, jobId: String, amount: Double) {
        if (!amount.isFinite() || amount <= 0.0) return
        val accepted = accept(BigDecimal.valueOf(amount)) ?: return
        record(playerId, jobId, EarningsTotals(xp = accepted, xpEvents = 1))
    }

    fun report(playerId: UUID, jobId: String): CompletableFuture<EarningsReport> {
        val normalizedJob = jobId.lowercase()
        val cacheKey = playerId to normalizedJob
        val revision: Long
        synchronized(lock) {
            cache[cacheKey]?.takeIf { it.validUntil.isAfter(clock.instant()) }?.let {
                return CompletableFuture.completedFuture(it.report)
            }
            revision = revisions[cacheKey] ?: 0L
        }
        return flushAll().handle { _, failure ->
            if (failure != null) {
                warnWrite(failure)
                false
            } else {
                true
            }
        }.thenCompose { flushSucceeded ->
            val from = epochHour(clock.instant()) - settings.retentionDays * 24L - 24L
            store.read(playerId, normalizedJob, from).thenApply { rows -> flushSucceeded to rows }
        }.thenApply { (flushSucceeded, rows) ->
            EarningsReport(rows, settings.zoneId).also { report ->
                synchronized(lock) {
                    if (flushSucceeded && !closed && (revisions[cacheKey] ?: 0L) == revision) {
                        cache[cacheKey] = CachedReport(clock.instant().plus(settings.cacheDuration), report)
                    }
                }
            }
        }
    }

    fun flush(): CompletableFuture<Unit> = flush(allowClosed = false)

    private fun flush(allowClosed: Boolean): CompletableFuture<Unit> {
        val write: CompletableFuture<Unit>
        val completion = CompletableFuture<Unit>()
        val batchId: UUID
        synchronized(lock) {
            if (closed && !allowClosed) return CompletableFuture.completedFuture(Unit)
            writeInFlight?.let { return it }
            val batch = retryBatch ?: drainPending() ?: return CompletableFuture.completedFuture(Unit)
            retryBatch = batch
            batchId = batch.id
            write = store.write(batch.id, batch.entries)
            writeInFlight = completion
        }
        write.whenComplete { _, failure ->
            synchronized(lock) {
                if (writeInFlight === completion) writeInFlight = null
                if (failure == null && retryBatch?.id == batchId) retryBatch = null
            }
            if (failure == null) completion.complete(Unit) else {
                warnWrite(failure)
                completion.completeExceptionally(failure)
            }
        }
        return completion
    }

    private fun flushAll(allowClosed: Boolean = false): CompletableFuture<Unit> = flush(allowClosed).thenCompose {
        val hasMore = synchronized(lock) { retryBatch != null || pending.isNotEmpty() }
        if (hasMore) flushAll(allowClosed) else CompletableFuture.completedFuture(Unit)
    }

    private fun record(playerId: UUID, jobId: String, addition: EarningsTotals) {
        val normalizedJob = jobId.lowercase()
        if (!JOB_ID.matches(normalizedJob)) return
        val key = BucketKey(playerId, normalizedJob, epochHour(clock.instant()))
        synchronized(lock) {
            if (closed) return
            val existing = pending[key]
            val occupied = pending.size + (retryBatch?.entries?.size ?: 0)
            if (existing == null && occupied >= settings.maximumPendingBuckets) {
                val now = clock.instant()
                if (lastOverflowWarning.plusSeconds(60).isBefore(now)) {
                    lastOverflowWarning = now
                    plugin.logger.warning(
                        "ArcEcoJobs earnings buffer reached ${settings.maximumPendingBuckets} buckets; dropping new analytics buckets",
                    )
                }
                return
            }
            pending[key] = (existing ?: EarningsTotals()) + addition
            val cacheKey = playerId to key.jobId
            cache.remove(cacheKey)
            revisions[cacheKey] = (revisions[cacheKey] ?: 0L) + 1L
        }
    }

    private fun drainPending(): PendingBatch? {
        if (pending.isEmpty()) return null
        val entries = pending.map { (key, totals) ->
            HourlyEarnings(
                key.playerId,
                key.jobId,
                key.epochHour,
                totals.copy(
                    money = normalize(totals.money),
                    xp = normalize(totals.xp),
                ),
            )
        }
        pending.clear()
        return PendingBatch(UUID.randomUUID(), entries)
    }

    private fun prune() {
        val before = epochHour(clock.instant()) - settings.retentionDays * 24L
        store.prune(before).whenComplete { removed, failure ->
            if (failure != null) {
                plugin.logger.log(Level.WARNING, "Could not prune old ArcEcoJobs earnings aggregates", failure)
            } else if (removed > 0) {
                plugin.logger.info("Pruned $removed expired ArcEcoJobs earnings buckets")
            }
        }
    }

    private fun warnWrite(failure: Throwable) {
        val now = clock.instant()
        synchronized(lock) {
            if (!lastWriteWarning.plusSeconds(60).isBefore(now)) return
            lastWriteWarning = now
        }
        plugin.logger.log(Level.WARNING, "Could not flush ArcEcoJobs earnings; the same batch will be retried", failure)
    }

    override fun close() {
        flushTask?.cancel()
        cleanupTask?.cancel()
        flushTask = null
        cleanupTask = null
        synchronized(lock) { closed = true }
        val finalWrite = flushAll(allowClosed = true)
        runCatching { finalWrite.get(5, TimeUnit.SECONDS) }
            .onFailure { plugin.logger.log(Level.WARNING, "Could not finish the final ArcEcoJobs earnings flush", it) }
        store.close()
    }

    internal fun pendingBucketCount(): Int = synchronized(lock) {
        pending.size + (retryBatch?.entries?.size ?: 0)
    }

    companion object {
        private const val STORED_SCALE = 6
        private const val STORED_PRECISION = 24
        private const val INITIAL_CLEANUP_DELAY_TICKS = 20L * 60L
        private val JOB_ID = Regex("[a-z0-9_-]{1,64}")

        internal fun epochHour(instant: Instant): Long = instant.epochSecond.floorDiv(SECONDS_PER_HOUR)

        private fun accept(amount: BigDecimal): BigDecimal? {
            if (amount.signum() <= 0) return null
            val integerDigits = amount.precision() - amount.scale()
            return amount.takeIf { integerDigits <= STORED_PRECISION - STORED_SCALE }
        }

        private fun normalize(amount: BigDecimal): BigDecimal =
            amount.setScale(STORED_SCALE, RoundingMode.HALF_UP).also {
                require(it.precision() <= STORED_PRECISION) { "earnings aggregate exceeds the persisted decimal contract" }
            }
    }
}

private fun java.time.Duration.toTicks(): Long = (toMillis() / 50L).coerceAtLeast(1L)
