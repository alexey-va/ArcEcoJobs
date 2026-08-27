package ru.ruscrafting.ecojobs.boost

import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlExecutor
import ru.arc.sql.SqlMigration
import ru.arc.sql.SqlRuntime
import ru.ruscrafting.ecojobs.config.RedemptionStorageSettings
import ru.ruscrafting.ecojobs.config.toSqlConnectionConfig
import ru.ruscrafting.ecojobs.domain.VoucherPayload
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

data class VoucherClaim(
    val voucherId: UUID,
    val redeemerId: UUID,
    val token: UUID,
    val newlyCreated: Boolean,
)

sealed interface VoucherClaimResult {
    data class Acquired(val claim: VoucherClaim) : VoucherClaimResult
    data object AlreadyApplied : VoucherClaimResult
    data object Busy : VoucherClaimResult
    data object Conflict : VoucherClaimResult
}

interface VoucherLedger : AutoCloseable {
    val available: Boolean
    val recoveryBacklog: Int get() = 0

    fun claim(payload: VoucherPayload, redeemerId: UUID): CompletableFuture<VoucherClaimResult>

    fun markApplied(claim: VoucherClaim): CompletableFuture<Boolean>

    fun release(claim: VoucherClaim): CompletableFuture<Boolean>

    fun abandon(claim: VoucherClaim): CompletableFuture<Boolean>

    override fun close() = Unit
}

object UnavailableVoucherLedger : VoucherLedger {
    override val available: Boolean = false

    override fun claim(payload: VoucherPayload, redeemerId: UUID): CompletableFuture<VoucherClaimResult> =
        CompletableFuture.failedFuture(IllegalStateException("voucher redemption ledger is unavailable"))

    override fun markApplied(claim: VoucherClaim): CompletableFuture<Boolean> =
        CompletableFuture.failedFuture(IllegalStateException("voucher redemption ledger is unavailable"))

    override fun release(claim: VoucherClaim): CompletableFuture<Boolean> =
        CompletableFuture.failedFuture(IllegalStateException("voucher redemption ledger is unavailable"))

    override fun abandon(claim: VoucherClaim): CompletableFuture<Boolean> =
        CompletableFuture.failedFuture(IllegalStateException("voucher redemption ledger is unavailable"))
}

class MySqlVoucherLedger private constructor(
    private val runtime: SqlRuntime,
    private val completionExecutor: SqlExecutor,
) : VoucherLedger {
    private data class ClaimHandle(
        val voucherId: UUID,
        val redeemerId: UUID,
        val lockName: String,
        val connection: Connection,
    )

    private val activeClaims = ConcurrentHashMap<UUID, ClaimHandle>()

    override val available: Boolean = true
    override val recoveryBacklog: Int get() = activeClaims.size

    override fun claim(payload: VoucherPayload, redeemerId: UUID): CompletableFuture<VoucherClaimResult> =
        runtime.executor.submit {
            val connection = runtime.dataSource.connection
            val lockName = "$CLAIM_LOCK_PREFIX${payload.voucherId}"
            var retained = false
            try {
                if (!acquireNamedLock(connection, lockName)) return@submit VoucherClaimResult.Busy
                val fingerprint = payload.fingerprint()
                val result = transaction(connection) {
                    val proposedToken = UUID.randomUUID()
                    connection.prepareStatement(
                        """
                        INSERT INTO `$REDEMPTIONS_TABLE`
                            (`voucher_id`, `payload_hash`, `redeemer_id`, `claim_token`, `status`, `claimed_at`)
                        VALUES (?, ?, ?, ?, 'CLAIMED', ?)
                        ON DUPLICATE KEY UPDATE `voucher_id` = `voucher_id`
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setBytes(1, payload.voucherId.bytes())
                        statement.setBytes(2, fingerprint)
                        statement.setBytes(3, redeemerId.bytes())
                        statement.setBytes(4, proposedToken.bytes())
                        statement.setTimestamp(5, Timestamp.from(Instant.now()))
                        statement.executeUpdate()
                    }
                    connection.prepareStatement(
                        """
                        SELECT `payload_hash`, `redeemer_id`, `claim_token`, `status`
                        FROM `$REDEMPTIONS_TABLE`
                        WHERE `voucher_id` = ?
                        FOR UPDATE
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setBytes(1, payload.voucherId.bytes())
                        statement.executeQuery().use { resultSet ->
                            check(resultSet.next()) { "voucher claim disappeared during transaction" }
                            if (!resultSet.getBytes("payload_hash").contentEquals(fingerprint)) {
                                return@transaction VoucherClaimResult.Conflict
                            }
                            if (resultSet.getString("status") == "APPLIED") {
                                return@transaction VoucherClaimResult.AlreadyApplied
                            }
                            check(resultSet.getString("status") == "CLAIMED") {
                                "unknown voucher redemption status"
                            }
                            val existingRedeemer = resultSet.getBytes("redeemer_id").uuid()
                            if (existingRedeemer != redeemerId) return@transaction VoucherClaimResult.Busy
                            val existingToken = resultSet.getBytes("claim_token").uuid()
                            VoucherClaimResult.Acquired(
                                VoucherClaim(
                                    voucherId = payload.voucherId,
                                    redeemerId = redeemerId,
                                    token = existingToken,
                                    newlyCreated = existingToken == proposedToken,
                                ),
                            )
                        }
                    }
                }
                if (result is VoucherClaimResult.Acquired) {
                    val handle = ClaimHandle(payload.voucherId, redeemerId, lockName, connection)
                    check(activeClaims.putIfAbsent(result.claim.token, handle) == null) {
                        "voucher claim token already has a local lock handle"
                    }
                    retained = true
                }
                result
            } finally {
                if (!retained) releaseNamedLock(connection, lockName)
            }
        }

    override fun markApplied(claim: VoucherClaim): CompletableFuture<Boolean> = completionExecutor.submit {
        withClaimHandle(claim) { connection ->
            transaction(connection) {
                val updated = connection.prepareStatement(
                    """
                    UPDATE `$REDEMPTIONS_TABLE`
                    SET `status` = 'APPLIED', `applied_at` = ?
                    WHERE `voucher_id` = ? AND `claim_token` = ? AND `status` = 'CLAIMED'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(Instant.now()))
                    statement.setBytes(2, claim.voucherId.bytes())
                    statement.setBytes(3, claim.token.bytes())
                    statement.executeUpdate()
                }
                if (updated == 1) return@transaction true
                connection.prepareStatement(
                    "SELECT `claim_token`, `status` FROM `$REDEMPTIONS_TABLE` WHERE `voucher_id` = ? FOR UPDATE",
                ).use { statement ->
                    statement.setBytes(1, claim.voucherId.bytes())
                    statement.executeQuery().use { result ->
                        result.next() &&
                            result.getBytes("claim_token").uuid() == claim.token &&
                            result.getString("status") == "APPLIED"
                    }
                }
            }
        }
    }

    override fun release(claim: VoucherClaim): CompletableFuture<Boolean> = completionExecutor.submit {
        withClaimHandle(claim) { connection ->
            connection.prepareStatement(
                "DELETE FROM `$REDEMPTIONS_TABLE` WHERE `voucher_id` = ? AND `claim_token` = ? AND `status` = 'CLAIMED'",
            ).use { statement ->
                statement.setBytes(1, claim.voucherId.bytes())
                statement.setBytes(2, claim.token.bytes())
                statement.executeUpdate() == 1
            }
        }
    }

    override fun abandon(claim: VoucherClaim): CompletableFuture<Boolean> = completionExecutor.submit {
        withClaimHandle(claim) { true }
    }

    override fun close() {
        // Advisory locks belong to retained JDBC sessions. Stop both queues before
        // returning those sessions, then close the shared pool last.
        completionExecutor.close()
        runtime.executor.close()
        activeClaims.entries.toList().forEach { (token, handle) ->
            if (activeClaims.remove(token, handle)) releaseNamedLock(handle.connection, handle.lockName)
        }
        runtime.dataSource.close()
    }

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

    private fun acquireNamedLock(connection: Connection, lockName: String): Boolean =
        connection.prepareStatement("SELECT GET_LOCK(?, 0)").use { statement ->
            statement.setString(1, lockName)
            statement.executeQuery().use { result -> result.next() && result.getInt(1) == 1 }
        }

    private fun releaseNamedLock(connection: Connection, lockName: String) {
        val released = runCatching {
            connection.prepareStatement("SELECT RELEASE_LOCK(?)").use { statement ->
                statement.setString(1, lockName)
                statement.executeQuery().use { result -> result.next() && result.getInt(1) == 1 }
            }
        }
        if (released.isFailure) {
            runCatching { runtime.dataSource.evictConnection(connection) }
        } else {
            runCatching { connection.close() }
        }
    }

    private fun withClaimHandle(claim: VoucherClaim, block: (Connection) -> Boolean): Boolean {
        val handle = activeClaims[claim.token]
            ?.takeIf { it.voucherId == claim.voucherId && it.redeemerId == claim.redeemerId }
            ?: return false
        check(activeClaims.remove(claim.token, handle)) { "voucher claim handle changed unexpectedly" }
        return try {
            block(handle.connection)
        } finally {
            releaseNamedLock(handle.connection, handle.lockName)
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val REDEMPTIONS_TABLE = "arcecojobs_voucher_redemptions"
        private const val CLAIM_LOCK_PREFIX = "arc:arcecojobs:voucher:"
        private val CREATE_REDEMPTIONS = """
            CREATE TABLE IF NOT EXISTS `$REDEMPTIONS_TABLE` (
                `voucher_id` BINARY(16) NOT NULL,
                `payload_hash` BINARY(32) NOT NULL,
                `redeemer_id` BINARY(16) NOT NULL,
                `claim_token` BINARY(16) NOT NULL,
                `status` VARCHAR(16) NOT NULL,
                `claimed_at` TIMESTAMP(3) NOT NULL,
                `applied_at` TIMESTAMP(3) NULL,
                PRIMARY KEY (`voucher_id`),
                CONSTRAINT `arcecojobs_voucher_status_chk` CHECK (`status` IN ('CLAIMED', 'APPLIED'))
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """.trimIndent()
        private val MIGRATION = SqlMigration(
            version = SCHEMA_VERSION,
            description = "create voucher redemption ledger",
            statements = listOf(CREATE_REDEMPTIONS),
        )

        fun open(settings: RedemptionStorageSettings): MySqlVoucherLedger {
            require(settings.enabled) { "voucher redemption MySQL is disabled" }
            val runtime = SqlRuntime.create(
                settings.toSqlConnectionConfig(settings.maximumPoolSize),
                "ArcEcoJobs-redemptions",
            )
            // Completion must never queue behind claims blocked on an exhausted
            // pool: it releases the retained sessions that make capacity available.
            val completionExecutor = SqlExecutor(
                runtime.dataSource,
                threads = 2,
                threadNamePrefix = "ArcEcoJobs-redemptions-complete",
            )
            return runCatching {
                MySqlMigrator(runtime.dataSource, "arcecojobs").migrate(listOf(MIGRATION))
                MySqlVoucherLedger(runtime, completionExecutor)
            }.getOrElse { failure ->
                completionExecutor.close()
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

private fun ByteArray.uuid(): UUID {
    require(size == 16) { "invalid UUID byte length" }
    val buffer = ByteBuffer.wrap(this)
    return UUID(buffer.long, buffer.long)
}
