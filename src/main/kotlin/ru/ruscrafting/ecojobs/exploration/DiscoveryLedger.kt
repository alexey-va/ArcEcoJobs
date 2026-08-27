package ru.ruscrafting.ecojobs.exploration

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
import kotlin.math.min

data class DiscoveryKey(
    val worldId: UUID,
    val chunkX: Int,
    val chunkZ: Int,
)

data class DiscoveryClaim(
    val key: DiscoveryKey,
    val playerId: UUID,
    val rank: Int,
)

sealed interface DiscoveryClaimResult {
    data class Acquired(val claim: DiscoveryClaim) : DiscoveryClaimResult
    data object AlreadyDiscovered : DiscoveryClaimResult
    data object Saturated : DiscoveryClaimResult
}

enum class DiscoveryDeliveryStatus {
    APPLIED,
    ABANDONED,
}

interface DiscoveryLedger : AutoCloseable {
    val available: Boolean

    fun claim(key: DiscoveryKey, playerId: UUID): CompletableFuture<DiscoveryClaimResult>

    fun finish(claim: DiscoveryClaim, status: DiscoveryDeliveryStatus): CompletableFuture<Boolean>

    override fun close() = Unit
}

object UnavailableDiscoveryLedger : DiscoveryLedger {
    override val available: Boolean = false

    override fun claim(key: DiscoveryKey, playerId: UUID): CompletableFuture<DiscoveryClaimResult> =
        CompletableFuture.failedFuture(IllegalStateException("chunk discovery ledger is unavailable"))

    override fun finish(
        claim: DiscoveryClaim,
        status: DiscoveryDeliveryStatus,
    ): CompletableFuture<Boolean> =
        CompletableFuture.failedFuture(IllegalStateException("chunk discovery ledger is unavailable"))
}

class MySqlDiscoveryLedger private constructor(
    private val runtime: SqlRuntime,
) : DiscoveryLedger {
    override val available: Boolean = true

    override fun claim(key: DiscoveryKey, playerId: UUID): CompletableFuture<DiscoveryClaimResult> =
        runtime.executor.transaction { connection ->
            connection.prepareStatement(
                """
                INSERT INTO `$CHUNKS_TABLE` (`world_id`, `chunk_x`, `chunk_z`, `discoverer_count`)
                VALUES (?, ?, ?, 0)
                ON DUPLICATE KEY UPDATE `world_id` = `world_id`
                """.trimIndent(),
            ).use { statement ->
                statement.bind(key)
                statement.executeUpdate()
            }

            val count = connection.prepareStatement(
                """
                SELECT `discoverer_count`
                FROM `$CHUNKS_TABLE`
                WHERE `world_id` = ? AND `chunk_x` = ? AND `chunk_z` = ?
                FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.bind(key)
                statement.executeQuery().use { result ->
                    check(result.next()) { "chunk discovery counter disappeared during transaction" }
                    result.getInt("discoverer_count")
                }
            }

            val existing = connection.prepareStatement(
                """
                SELECT 1
                FROM `$DISCOVERIES_TABLE`
                WHERE `world_id` = ? AND `chunk_x` = ? AND `chunk_z` = ? AND `player_id` = ?
                """.trimIndent(),
            ).use { statement ->
                statement.bind(key)
                statement.setBytes(4, playerId.bytes())
                statement.executeQuery().use { it.next() }
            }
            if (existing) return@transaction DiscoveryClaimResult.AlreadyDiscovered
            if (count >= MAX_DISCOVERERS) return@transaction DiscoveryClaimResult.Saturated

            val rank = count + 1
            connection.prepareStatement(
                """
                INSERT INTO `$DISCOVERIES_TABLE`
                    (`world_id`, `chunk_x`, `chunk_z`, `player_id`, `discovery_rank`, `status`, `claimed_at`)
                VALUES (?, ?, ?, ?, ?, 'CLAIMED', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.bind(key)
                statement.setBytes(4, playerId.bytes())
                statement.setInt(5, rank)
                statement.setTimestamp(6, Timestamp.from(Instant.now()))
                check(statement.executeUpdate() == 1) { "chunk discovery claim was not inserted" }
            }
            val updated = connection.prepareStatement(
                """
                UPDATE `$CHUNKS_TABLE`
                SET `discoverer_count` = ?
                WHERE `world_id` = ? AND `chunk_x` = ? AND `chunk_z` = ? AND `discoverer_count` = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, rank)
                statement.setBytes(2, key.worldId.bytes())
                statement.setInt(3, key.chunkX)
                statement.setInt(4, key.chunkZ)
                statement.setInt(5, count)
                statement.executeUpdate()
            }
            check(updated == 1) { "chunk discovery counter changed unexpectedly" }
            DiscoveryClaimResult.Acquired(DiscoveryClaim(key, playerId, rank))
        }

    override fun finish(
        claim: DiscoveryClaim,
        status: DiscoveryDeliveryStatus,
    ): CompletableFuture<Boolean> = runtime.executor.transaction { connection ->
        val updated = connection.prepareStatement(
            """
            UPDATE `$DISCOVERIES_TABLE`
            SET `status` = ?, `finished_at` = ?
            WHERE `world_id` = ? AND `chunk_x` = ? AND `chunk_z` = ?
                AND `player_id` = ? AND `discovery_rank` = ? AND `status` = 'CLAIMED'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, status.name)
            statement.setTimestamp(2, Timestamp.from(Instant.now()))
            statement.setBytes(3, claim.key.worldId.bytes())
            statement.setInt(4, claim.key.chunkX)
            statement.setInt(5, claim.key.chunkZ)
            statement.setBytes(6, claim.playerId.bytes())
            statement.setInt(7, claim.rank)
            statement.executeUpdate()
        }
        if (updated == 1) return@transaction true
        connection.prepareStatement(
            """
            SELECT `status`
            FROM `$DISCOVERIES_TABLE`
            WHERE `world_id` = ? AND `chunk_x` = ? AND `chunk_z` = ? AND `player_id` = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.bind(claim.key)
            statement.setBytes(4, claim.playerId.bytes())
            statement.executeQuery().use { result ->
                result.next() && result.getString("status") == status.name
                }
            }
        }

    override fun close() = runtime.close()

    companion object {
        const val MAX_DISCOVERERS = 5
        private const val CHUNKS_TABLE = "arcecojobs_explored_chunks"
        private const val DISCOVERIES_TABLE = "arcecojobs_chunk_discoveries"
        const val SCHEMA_VERSION = 3
        private val CREATE_CHUNKS = """
            CREATE TABLE IF NOT EXISTS `$CHUNKS_TABLE` (
                `world_id` BINARY(16) NOT NULL,
                `chunk_x` INT NOT NULL,
                `chunk_z` INT NOT NULL,
                `discoverer_count` TINYINT UNSIGNED NOT NULL,
                PRIMARY KEY (`world_id`, `chunk_x`, `chunk_z`),
                CONSTRAINT `arcecojobs_discoverer_count_chk`
                    CHECK (`discoverer_count` BETWEEN 0 AND $MAX_DISCOVERERS)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """.trimIndent()
        private val CREATE_DISCOVERIES = """
            CREATE TABLE IF NOT EXISTS `$DISCOVERIES_TABLE` (
                `world_id` BINARY(16) NOT NULL,
                `chunk_x` INT NOT NULL,
                `chunk_z` INT NOT NULL,
                `player_id` BINARY(16) NOT NULL,
                `discovery_rank` TINYINT UNSIGNED NOT NULL,
                `status` VARCHAR(16) NOT NULL,
                `claimed_at` TIMESTAMP(3) NOT NULL,
                `finished_at` TIMESTAMP(3) NULL,
                PRIMARY KEY (`world_id`, `chunk_x`, `chunk_z`, `player_id`),
                UNIQUE KEY `arcecojobs_chunk_rank_uq`
                    (`world_id`, `chunk_x`, `chunk_z`, `discovery_rank`),
                CONSTRAINT `arcecojobs_discovery_rank_chk`
                    CHECK (`discovery_rank` BETWEEN 1 AND $MAX_DISCOVERERS),
                CONSTRAINT `arcecojobs_discovery_status_chk`
                    CHECK (`status` IN ('CLAIMED', 'APPLIED', 'ABANDONED'))
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """.trimIndent()
        private val MIGRATION = SqlMigration(
            version = SCHEMA_VERSION,
            description = "create least-privilege chunk discovery ledger",
            statements = listOf(CREATE_CHUNKS, CREATE_DISCOVERIES),
        )
        private val MIGRATION_COMPATIBILITY = SqlMigrationCompatibility.legacyConcatenated(MIGRATION)

        fun open(settings: RedemptionStorageSettings): MySqlDiscoveryLedger {
            require(settings.enabled) { "chunk discovery MySQL is disabled" }
            val poolSize = min(settings.maximumPoolSize, 2)
            val runtime = SqlRuntime.create(settings.toSqlConnectionConfig(poolSize), "ArcEcoJobs-exploration")
            return runCatching {
                MySqlMigrator(runtime.dataSource, "arcecojobs").migrate(
                    listOf(MIGRATION),
                    MIGRATION_COMPATIBILITY,
                )
                MySqlDiscoveryLedger(runtime)
            }.getOrElse { failure ->
                runtime.close()
                throw failure
            }
        }
    }
}

private fun java.sql.PreparedStatement.bind(key: DiscoveryKey) {
    setBytes(1, key.worldId.bytes())
    setInt(2, key.chunkX)
    setInt(3, key.chunkZ)
}

private fun UUID.bytes(): ByteArray = ByteBuffer.allocate(16)
    .putLong(mostSignificantBits)
    .putLong(leastSignificantBits)
    .array()
