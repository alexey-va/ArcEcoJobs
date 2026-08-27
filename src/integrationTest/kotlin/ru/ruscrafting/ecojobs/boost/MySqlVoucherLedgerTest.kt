package ru.ruscrafting.ecojobs.boost

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.onetime.OneTimeUseAbandonResult
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.onetime.OneTimeUseIdentity
import ru.arc.onetime.OneTimeUseReleaseResult
import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlMigration
import ru.arc.sql.SqlRuntime
import ru.ruscrafting.ecojobs.config.RedemptionStorageSettings
import ru.ruscrafting.ecojobs.config.toSqlConnectionConfig
import ru.ruscrafting.ecojobs.domain.BoostType
import ru.ruscrafting.ecojobs.domain.VoucherPayload
import ru.ruscrafting.ecojobs.testing.ArcEcoJobsMySqlFixture
import java.nio.ByteBuffer
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MySqlVoucherLedgerTest : StringSpec({
    lateinit var mysql: ArcEcoJobsMySqlFixture
    lateinit var settings: RedemptionStorageSettings

    beforeSpec {
        mysql = ArcEcoJobsMySqlFixture.start("arcecojobs_test")
        settings = mysql.settings
    }

    afterSpec { mysql.close() }

    "schema v5 preserves historical namespace versions and imports a pending legacy claim" {
        val payload = voucher()
        val player = UUID.randomUUID()
        SqlRuntime.create(settings.toSqlConnectionConfig(1), "legacy-voucher-fixture").use { runtime ->
            MySqlMigrator(runtime.dataSource, "arcecojobs").migrate(
                listOf(legacyVoucherMigration(), historicalDiscoveryMigration()),
            )
            runtime.executor.write { connection ->
                connection.prepareStatement(
                    "INSERT INTO arcecojobs_voucher_redemptions " +
                        "(voucher_id, payload_hash, redeemer_id, claim_token, status, claimed_at) " +
                        "VALUES (?, ?, ?, ?, 'CLAIMED', ?)",
                ).use { statement ->
                    statement.setBytes(1, payload.voucherId.bytes())
                    statement.setBytes(2, payload.fingerprint())
                    statement.setBytes(3, player.bytes())
                    statement.setBytes(4, UUID.randomUUID().bytes())
                    statement.setTimestamp(5, Timestamp.from(Instant.now()))
                    statement.executeUpdate() shouldBe 1
                }
            }.join()
        }

        VoucherLedgerStorage.open(settings).use { ledger ->
            val recovered = ledger.claim(request(payload, player)).join()
                .shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>().claim
            recovered.newlyCreated shouldBe false
            recovered.claimId shouldBe payload.voucherId
            ledger.commit(recovered).join() shouldBe OneTimeUseCommitResult.COMMITTED
        }
    }

    "two server nodes can claim one voucher ID only once" {
        val firstNode = VoucherLedgerStorage.open(settings)
        val secondNode = VoucherLedgerStorage.open(settings)
        try {
            val payload = voucher()
            val players = listOf(UUID.randomUUID(), UUID.randomUUID())
            val nodes = listOf(firstNode, secondNode)
            val gate = CountDownLatch(1)
            val attempts = players.mapIndexed { index, player ->
                CompletableFuture.supplyAsync {
                    gate.await()
                    nodes[index] to nodes[index].claim(request(payload, player)).join()
                }
            }
            gate.countDown()
            val results = attempts.map { it.join() }
            results.map { it.second }.filterIsInstance<OneTimeUseClaimResult.Acquired>() shouldHaveSize 1
            results.map { it.second }.filterIsInstance<OneTimeUseClaimResult.Busy>() shouldHaveSize 1

            val (ownerNode, result) = results.single { it.second is OneTimeUseClaimResult.Acquired }
            val acquired = result.shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>()
            ownerNode.commit(acquired.claim).join() shouldBe OneTimeUseCommitResult.COMMITTED
            firstNode.claim(request(payload, players[0])).join() shouldBe OneTimeUseClaimResult.AlreadyConsumed
            secondNode.claim(request(payload, players[1])).join() shouldBe OneTimeUseClaimResult.AlreadyConsumed
        } finally {
            firstNode.close()
            secondNode.close()
        }
    }

    "active claim excludes copied attempts and safe release restores transferability" {
        VoucherLedgerStorage.open(settings).use { ledger ->
            val payload = voucher()
            val firstPlayer = UUID.randomUUID()
            val first = ledger.claim(request(payload, firstPlayer)).join().shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>()
            first.claim.newlyCreated shouldBe true

            ledger.claim(request(payload, firstPlayer)).join() shouldBe OneTimeUseClaimResult.Busy
            ledger.claim(request(payload, UUID.randomUUID())).join() shouldBe OneTimeUseClaimResult.Busy

            ledger.release(first.claim).join() shouldBe OneTimeUseReleaseResult.RELEASED
            val transferred = ledger.claim(request(payload, UUID.randomUUID())).join()
                .shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>()
            ledger.release(transferred.claim).join() shouldBe OneTimeUseReleaseResult.RELEASED
        }
    }

    "same player can recover a pending claim after the original node disconnects" {
        val payload = voucher()
        val player = UUID.randomUUID()
        val firstNode = VoucherLedgerStorage.open(settings)
        val original = firstNode.claim(request(payload, player)).join().shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>()
        firstNode.close()

        VoucherLedgerStorage.open(settings).use { recoveredNode ->
            recoveredNode.claim(request(payload, UUID.randomUUID())).join() shouldBe OneTimeUseClaimResult.Busy
            val recovered = recoveredNode.claim(request(payload, player)).join()
                .shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>()
            recovered.claim.claimId shouldBe original.claim.claimId
            recovered.claim.newlyCreated shouldBe false
            recoveredNode.commit(recovered.claim).join() shouldBe OneTimeUseCommitResult.COMMITTED
        }
    }

    "completion cannot be starved when every pool connection is held by active claims" {
        VoucherLedgerStorage.open(settings).use { ledger ->
            val first = ledger.claim(request(voucher(), UUID.randomUUID())).join()
                .shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>()
            val second = ledger.claim(request(voucher(), UUID.randomUUID())).join()
                .shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>()
            val waiting = ledger.claim(request(voucher(), UUID.randomUUID()))

            ledger.commit(first.claim).get(2, TimeUnit.SECONDS) shouldBe OneTimeUseCommitResult.COMMITTED
            val third = waiting.get(2, TimeUnit.SECONDS).shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>()
            ledger.release(second.claim).join() shouldBe OneTimeUseReleaseResult.RELEASED
            ledger.release(third.claim).join() shouldBe OneTimeUseReleaseResult.RELEASED
        }
    }

    "abandon releases the live lock but keeps an uncertain claim reserved" {
        VoucherLedgerStorage.open(settings).use { ledger ->
            val payload = voucher()
            val player = UUID.randomUUID()
            val original = ledger.claim(request(payload, player)).join()
                .shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>()
            ledger.abandon(original.claim).join() shouldBe OneTimeUseAbandonResult.RETAINED_FOR_RECOVERY

            ledger.claim(request(payload, UUID.randomUUID())).join() shouldBe OneTimeUseClaimResult.Busy
            val recovered = ledger.claim(request(payload, player)).join()
                .shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>()
            recovered.claim.newlyCreated shouldBe false
            recovered.claim.claimId shouldBe original.claim.claimId
            ledger.commit(recovered.claim).join() shouldBe OneTimeUseCommitResult.COMMITTED
        }
    }

    "one signed identity cannot be reused with different payload fields" {
        VoucherLedgerStorage.open(settings).use { ledger ->
            val payload = voucher()
            val claim = ledger.claim(request(payload, UUID.randomUUID())).join()
                .shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>()
            ledger.commit(claim.claim).join() shouldBe OneTimeUseCommitResult.COMMITTED
            ledger.claim(request(payload.copy(durationSeconds = 7_200), UUID.randomUUID())).join() shouldBe
                OneTimeUseClaimResult.IdentityConflict
        }
    }

    "applied redemption survives pool restart and migration replay" {
        val payload = voucher()
        val player = UUID.randomUUID()
        VoucherLedgerStorage.open(settings).use { ledger ->
            val claim = ledger.claim(request(payload, player)).join().shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>().claim
            ledger.commit(claim).join() shouldBe OneTimeUseCommitResult.COMMITTED
        }
        VoucherLedgerStorage.open(settings).use { reopened ->
            reopened.claim(request(payload, UUID.randomUUID())).join() shouldBe OneTimeUseClaimResult.AlreadyConsumed
        }
    }
}) {
    companion object {
        private fun request(payload: VoucherPayload, playerId: UUID): OneTimeUseClaimRequest =
            OneTimeUseClaimRequest(
                identity = OneTimeUseIdentity(payload.voucherId, OneTimeUseFingerprint.fromBytes(payload.fingerprint())),
                claimId = payload.voucherId,
                claimantId = playerId,
            )

        private fun voucher(): VoucherPayload = VoucherPayload(
            presetId = "workday",
            voucherId = UUID.randomUUID(),
            type = BoostType.ALL,
            multiplierBasisPoints = 150,
            durationSeconds = 3_600,
            jobs = setOf("miner", "fisherman"),
            issuedAtEpochSecond = Instant.now().epochSecond,
        )

        private fun legacyVoucherMigration(): SqlMigration = SqlMigration(
            version = 1,
            description = "create voucher redemption ledger",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS `arcecojobs_voucher_redemptions` (
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
                """.trimIndent(),
            ),
        )

        private fun historicalDiscoveryMigration(): SqlMigration = SqlMigration(
            version = 2,
            description = "historical discovery schema fixture",
            statements = listOf(
                """
                CREATE TABLE IF NOT EXISTS `arcecojobs_historical_discovery_fixture` (
                    `id` INT NOT NULL,
                    PRIMARY KEY (`id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent(),
            ),
        )
    }
}

private fun UUID.bytes(): ByteArray = ByteBuffer.allocate(16)
    .putLong(mostSignificantBits)
    .putLong(leastSignificantBits)
    .array()
