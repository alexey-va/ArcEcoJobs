package ru.ruscrafting.ecojobs.earnings

import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlMigration
import ru.arc.sql.SqlMigrationCompatibility
import ru.arc.sql.SqlRuntime
import ru.ruscrafting.ecojobs.config.RedemptionStorageSettings
import ru.ruscrafting.ecojobs.config.toSqlConnectionConfig
import java.nio.ByteBuffer
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

interface HourlyEarningsStore : AutoCloseable {
    fun write(batchId: UUID, entries: List<HourlyEarnings>): CompletableFuture<Unit>

    fun read(playerId: UUID, jobId: String, fromEpochHour: Long): CompletableFuture<List<HourlyEarnings>>

    fun prune(beforeEpochHour: Long): CompletableFuture<Int>

    override fun close() = Unit
}

class MySqlHourlyEarningsStore private constructor(
    private val runtime: SqlRuntime,
) : HourlyEarningsStore {
    override fun write(batchId: UUID, entries: List<HourlyEarnings>): CompletableFuture<Unit> {
        if (entries.isEmpty()) return CompletableFuture.completedFuture(Unit)
        return runtime.executor.transaction { connection ->
            val firstAttempt = connection.prepareStatement(
                "INSERT IGNORE INTO `$BATCHES_TABLE` (`batch_id`, `applied_at`) VALUES (?, ?)",
            ).use { statement ->
                statement.setBytes(1, batchId.bytes())
                statement.setTimestamp(2, Timestamp.from(Instant.now()))
                statement.executeUpdate() == 1
            }
            if (!firstAttempt) return@transaction Unit
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
            Unit
        }
    }

    override fun read(
        playerId: UUID,
        jobId: String,
        fromEpochHour: Long,
    ): CompletableFuture<List<HourlyEarnings>> = runtime.executor.read { connection ->
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

    override fun prune(beforeEpochHour: Long): CompletableFuture<Int> = runtime.executor.transaction { connection ->
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

    override fun close() = runtime.close()

    companion object {
        private const val HOURLY_TABLE = "arcecojobs_earnings_hourly"
        private const val BATCHES_TABLE = "arcecojobs_earnings_batches"
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
        private val MIGRATION = SqlMigration(
            version = SCHEMA_VERSION,
            description = "create bounded hourly job earnings aggregates",
            statements = listOf(CREATE_HOURLY, CREATE_BATCHES),
        )
        private val MIGRATION_COMPATIBILITY = SqlMigrationCompatibility.legacyConcatenated(MIGRATION)

        fun open(settings: RedemptionStorageSettings): MySqlHourlyEarningsStore {
            require(settings.enabled) { "earnings MySQL is disabled" }
            val runtime = SqlRuntime.create(settings.toSqlConnectionConfig(poolSize = 1), "ArcEcoJobs-earnings")
            return runCatching {
                MySqlMigrator(runtime.dataSource, "arcecojobs").migrate(
                    listOf(MIGRATION),
                    MIGRATION_COMPATIBILITY,
                )
                MySqlHourlyEarningsStore(runtime)
            }.getOrElse { failure ->
                runtime.close()
                throw failure
            }
        }
    }
}

private fun UUID.bytes(): ByteArray = ByteBuffer.allocate(16)
    .putLong(mostSignificantBits)
    .putLong(leastSignificantBits)
    .array()
