package ru.ruscrafting.ecojobs.earnings

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import ru.ruscrafting.ecojobs.config.RedemptionStorageSettings
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

interface HourlyEarningsStore : AutoCloseable {
    fun write(batchId: UUID, entries: List<HourlyEarnings>): CompletableFuture<Unit>

    fun read(playerId: UUID, jobId: String, fromEpochHour: Long): CompletableFuture<List<HourlyEarnings>>

    fun prune(beforeEpochHour: Long): CompletableFuture<Int>

    override fun close() = Unit
}

class MySqlHourlyEarningsStore private constructor(
    private val dataSource: HikariDataSource,
    private val executor: ExecutorService,
) : HourlyEarningsStore {
    override fun write(batchId: UUID, entries: List<HourlyEarnings>): CompletableFuture<Unit> = submit {
        if (entries.isEmpty()) return@submit Unit
        dataSource.connection.use { connection ->
            transaction(connection) {
                val firstAttempt = connection.prepareStatement(
                    "INSERT IGNORE INTO `$BATCHES_TABLE` (`batch_id`, `applied_at`) VALUES (?, ?)",
                ).use { statement ->
                    statement.setBytes(1, batchId.bytes())
                    statement.setTimestamp(2, Timestamp.from(Instant.now()))
                    statement.executeUpdate() == 1
                }
                if (!firstAttempt) return@transaction
                connection.prepareStatement(
                    """
                    INSERT INTO `$HOURLY_TABLE`
                        (`player_id`, `job_id`, `bucket_hour`, `money`, `xp`, `money_events`, `xp_events`)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        `money` = `money` + VALUES(`money`),
                        `xp` = `xp` + VALUES(`xp`),
                        `money_events` = `money_events` + VALUES(`money_events`),
                        `xp_events` = `xp_events` + VALUES(`xp_events`),
                        `updated_at` = CURRENT_TIMESTAMP(3)
                    """.trimIndent(),
                ).use { statement ->
                    entries.forEach { entry ->
                        statement.setBytes(1, entry.playerId.bytes())
                        statement.setString(2, entry.jobId)
                        statement.setLong(3, entry.epochHour)
                        statement.setBigDecimal(4, entry.totals.money)
                        statement.setBigDecimal(5, entry.totals.xp)
                        statement.setLong(6, entry.totals.moneyEvents)
                        statement.setLong(7, entry.totals.xpEvents)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
        }
    }

    override fun read(
        playerId: UUID,
        jobId: String,
        fromEpochHour: Long,
    ): CompletableFuture<List<HourlyEarnings>> = submit {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT `bucket_hour`, `money`, `xp`, `money_events`, `xp_events`
                FROM `$HOURLY_TABLE`
                WHERE `player_id` = ? AND `job_id` = ? AND `bucket_hour` >= ?
                ORDER BY `bucket_hour` DESC
                """.trimIndent(),
            ).use { statement ->
                statement.setBytes(1, playerId.bytes())
                statement.setString(2, jobId.lowercase())
                statement.setLong(3, fromEpochHour)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                HourlyEarnings(
                                    playerId = playerId,
                                    jobId = jobId.lowercase(),
                                    epochHour = result.getLong("bucket_hour"),
                                    totals = EarningsTotals(
                                        money = result.getBigDecimal("money"),
                                        xp = result.getBigDecimal("xp"),
                                        moneyEvents = result.getLong("money_events"),
                                        xpEvents = result.getLong("xp_events"),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun prune(beforeEpochHour: Long): CompletableFuture<Int> = submit {
        dataSource.connection.use { connection ->
            transaction(connection) {
                val hourly = connection.prepareStatement(
                    "DELETE FROM `$HOURLY_TABLE` WHERE `bucket_hour` < ?",
                ).use { statement ->
                    statement.setLong(1, beforeEpochHour)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "DELETE FROM `$BATCHES_TABLE` WHERE `applied_at` < ?",
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(Instant.ofEpochSecond(beforeEpochHour * SECONDS_PER_HOUR)))
                    statement.executeUpdate()
                }
                hourly
            }
        }
    }

    override fun close() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        dataSource.close()
    }

    private fun <T> submit(block: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(block, executor)

    private fun <T> transaction(connection: Connection, block: (Connection) -> T): T {
        connection.autoCommit = false
        return try {
            block(connection).also { connection.commit() }
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }.onFailure(failure::addSuppressed)
            throw failure
        } finally {
            runCatching { connection.autoCommit = true }
        }
    }

    companion object {
        private const val HISTORY_TABLE = "arcecojobs_schema_history"
        private const val HOURLY_TABLE = "arcecojobs_earnings_hourly"
        private const val BATCHES_TABLE = "arcecojobs_earnings_batches"
        private const val MIGRATION_LOCK = "arc:arcecojobs:migrations"
        const val SCHEMA_VERSION = 4
        private val CREATE_HOURLY = """
            CREATE TABLE IF NOT EXISTS `$HOURLY_TABLE` (
                `player_id` BINARY(16) NOT NULL,
                `job_id` VARCHAR(64) NOT NULL,
                `bucket_hour` BIGINT NOT NULL,
                `money` DECIMAL(24,6) NOT NULL DEFAULT 0,
                `xp` DECIMAL(24,6) NOT NULL DEFAULT 0,
                `money_events` BIGINT UNSIGNED NOT NULL DEFAULT 0,
                `xp_events` BIGINT UNSIGNED NOT NULL DEFAULT 0,
                `updated_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                PRIMARY KEY (`player_id`, `job_id`, `bucket_hour`),
                KEY `arcecojobs_earnings_retention_idx` (`bucket_hour`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """.trimIndent()
        private val CREATE_BATCHES = """
            CREATE TABLE IF NOT EXISTS `$BATCHES_TABLE` (
                `batch_id` BINARY(16) NOT NULL,
                `applied_at` TIMESTAMP(3) NOT NULL,
                PRIMARY KEY (`batch_id`),
                KEY `arcecojobs_earnings_batches_retention_idx` (`applied_at`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """.trimIndent()
        private val MIGRATION_CHECKSUM = MessageDigest.getInstance("SHA-256")
            .digest("$CREATE_HOURLY\n$CREATE_BATCHES".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        fun open(settings: RedemptionStorageSettings): MySqlHourlyEarningsStore {
            require(settings.enabled) { "earnings MySQL is disabled" }
            val poolSize = 1
            val hikari = HikariConfig().apply {
                poolName = "ArcEcoJobs-earnings"
                jdbcUrl = buildString {
                    append("jdbc:mysql://")
                    append(settings.host)
                    append(':')
                    append(settings.port)
                    append('/')
                    append(settings.database)
                    append("?useUnicode=true&characterEncoding=utf8")
                    append("&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true")
                    append("&sslMode=")
                    append(settings.sslMode)
                    if (settings.sslMode == "DISABLED") append("&allowPublicKeyRetrieval=true")
                    append("&connectTimeout=")
                    append(settings.connectionTimeoutMs)
                    append("&socketTimeout=30000")
                }
                driverClassName = "com.mysql.cj.jdbc.Driver"
                username = settings.username
                password = settings.password
                minimumIdle = settings.minimumIdle.coerceAtMost(poolSize)
                maximumPoolSize = poolSize
                connectionTimeout = settings.connectionTimeoutMs
                validationTimeout = settings.validationTimeoutMs
                maxLifetime = settings.maxLifetimeMs
                initializationFailTimeout = settings.connectionTimeoutMs
                isAutoCommit = true
                addDataSourceProperty("cachePrepStmts", "true")
                addDataSourceProperty("prepStmtCacheSize", "250")
                addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
                addDataSourceProperty("useServerPrepStmts", "true")
            }
            val dataSource = HikariDataSource(hikari)
            return runCatching {
                migrate(dataSource)
                MySqlHourlyEarningsStore(
                    dataSource,
                    Executors.newFixedThreadPool(poolSize, EarningsThreadFactory()),
                )
            }.getOrElse { failure ->
                dataSource.close()
                throw failure
            }
        }

        private fun migrate(dataSource: HikariDataSource) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT GET_LOCK(?, 30)").use { statement ->
                    statement.setString(1, MIGRATION_LOCK)
                    statement.executeQuery().use { result ->
                        check(result.next() && result.getInt(1) == 1) {
                            "could not acquire ArcEcoJobs migration lock"
                        }
                    }
                }
                try {
                    connection.createStatement().use { statement ->
                        statement.executeUpdate(
                            """
                            CREATE TABLE IF NOT EXISTS `$HISTORY_TABLE` (
                                `version` INT NOT NULL,
                                `description` VARCHAR(255) NOT NULL,
                                `checksum` CHAR(64) NOT NULL,
                                `applied_at` TIMESTAMP(3) NOT NULL,
                                PRIMARY KEY (`version`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                            """.trimIndent(),
                        )
                    }
                    val existing = connection.prepareStatement(
                        "SELECT `checksum` FROM `$HISTORY_TABLE` WHERE `version` = ?",
                    ).use { statement ->
                        statement.setInt(1, SCHEMA_VERSION)
                        statement.executeQuery().use { result ->
                            result.takeIf { it.next() }?.getString("checksum")
                        }
                    }
                    if (existing != null) {
                        check(existing == MIGRATION_CHECKSUM) { "earnings migration checksum mismatch" }
                        return
                    }
                    connection.createStatement().use { statement ->
                        statement.executeUpdate(CREATE_HOURLY)
                        statement.executeUpdate(CREATE_BATCHES)
                    }
                    connection.prepareStatement(
                        """
                        INSERT INTO `$HISTORY_TABLE` (`version`, `description`, `checksum`, `applied_at`)
                        VALUES (?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setInt(1, SCHEMA_VERSION)
                        statement.setString(2, "create bounded hourly job earnings aggregates")
                        statement.setString(3, MIGRATION_CHECKSUM)
                        statement.setTimestamp(4, Timestamp.from(Instant.now()))
                        statement.executeUpdate()
                    }
                } finally {
                    connection.prepareStatement("SELECT RELEASE_LOCK(?)").use { statement ->
                        statement.setString(1, MIGRATION_LOCK)
                        statement.executeQuery().use { result ->
                            check(result.next() && result.getInt(1) == 1) {
                                "could not release ArcEcoJobs migration lock"
                            }
                        }
                    }
                }
            }
        }
    }
}

private class EarningsThreadFactory : ThreadFactory {
    private val sequence = AtomicInteger()

    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, "ArcEcoJobs-earnings-${sequence.incrementAndGet()}").apply { isDaemon = true }
}

private fun UUID.bytes(): ByteArray = ByteBuffer.allocate(16)
    .putLong(mostSignificantBits)
    .putLong(leastSignificantBits)
    .array()
