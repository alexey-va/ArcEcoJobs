package ru.ruscrafting.ecojobs.exploration

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
    private val dataSource: HikariDataSource,
    private val executor: ExecutorService,
) : DiscoveryLedger {
    override val available: Boolean = true

    override fun claim(key: DiscoveryKey, playerId: UUID): CompletableFuture<DiscoveryClaimResult> = submit {
        dataSource.connection.use { connection ->
            transaction(connection) {
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
        }
    }

    override fun finish(
        claim: DiscoveryClaim,
        status: DiscoveryDeliveryStatus,
    ): CompletableFuture<Boolean> = submit {
        dataSource.connection.use { connection ->
            transaction(connection) {
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
        const val MAX_DISCOVERERS = 5
        private const val HISTORY_TABLE = "arcecojobs_schema_history"
        private const val CHUNKS_TABLE = "arcecojobs_explored_chunks"
        private const val DISCOVERIES_TABLE = "arcecojobs_chunk_discoveries"
        private const val MIGRATION_LOCK = "arc:arcecojobs:migrations"
        private const val MIGRATION_VERSION = 2
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
                    CHECK (`status` IN ('CLAIMED', 'APPLIED', 'ABANDONED')),
                CONSTRAINT `arcecojobs_discovery_chunk_fk`
                    FOREIGN KEY (`world_id`, `chunk_x`, `chunk_z`)
                    REFERENCES `$CHUNKS_TABLE` (`world_id`, `chunk_x`, `chunk_z`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """.trimIndent()
        private val MIGRATION_CHECKSUM = MessageDigest.getInstance("SHA-256")
            .digest("$CREATE_CHUNKS\n$CREATE_DISCOVERIES".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        fun open(settings: RedemptionStorageSettings): MySqlDiscoveryLedger {
            require(settings.enabled) { "chunk discovery MySQL is disabled" }
            val poolSize = min(settings.maximumPoolSize, 2)
            val hikari = HikariConfig().apply {
                poolName = "ArcEcoJobs-exploration"
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
                minimumIdle = min(settings.minimumIdle, poolSize)
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
                MySqlDiscoveryLedger(
                    dataSource,
                    Executors.newFixedThreadPool(poolSize, DiscoveryThreadFactory()),
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
                        statement.setInt(1, MIGRATION_VERSION)
                        statement.executeQuery().use { result ->
                            result.takeIf { it.next() }?.getString("checksum")
                        }
                    }
                    if (existing != null) {
                        check(existing == MIGRATION_CHECKSUM) { "chunk discovery migration checksum mismatch" }
                        return
                    }
                    connection.createStatement().use { statement ->
                        statement.executeUpdate(CREATE_CHUNKS)
                        statement.executeUpdate(CREATE_DISCOVERIES)
                    }
                    connection.prepareStatement(
                        """
                        INSERT INTO `$HISTORY_TABLE` (`version`, `description`, `checksum`, `applied_at`)
                        VALUES (?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setInt(1, MIGRATION_VERSION)
                        statement.setString(2, "create chunk discovery ledger")
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

private class DiscoveryThreadFactory : ThreadFactory {
    private val sequence = AtomicInteger()

    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, "ArcEcoJobs-exploration-${sequence.incrementAndGet()}").apply { isDaemon = true }
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
