package ru.ruscrafting.ecojobs.earnings

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.onetime.OneTimeUseIdentity
import ru.ruscrafting.ecojobs.boost.VoucherLedgerStorage
import ru.ruscrafting.ecojobs.config.RedemptionStorageSettings
import ru.ruscrafting.ecojobs.testing.ArcEcoJobsMySqlFixture
import java.math.BigDecimal
import java.util.UUID
import java.sql.DriverManager
import java.util.concurrent.CompletionException

class MySqlHourlyEarningsStoreTest : StringSpec({
    lateinit var mysql: ArcEcoJobsMySqlFixture
    lateinit var settings: RedemptionStorageSettings

    beforeSpec {
        mysql = ArcEcoJobsMySqlFixture.start(
            "arcecojobs_exploration_test",
            "mysql/arcecojobs-least-privilege.sql",
        )
        settings = mysql.settings
    }

    afterSpec { mysql.close() }

    "least-privilege storage aggregates batches idempotently and prunes expired hours" {
        MySqlHourlyEarningsStore.open(settings).use { store ->
            val player = UUID.randomUUID()
            val hour = 500_000L
            val batch = UUID.randomUUID()
            val first = HourlyEarnings(
                player,
                "builder",
                hour,
                EarningsTotals(BigDecimal("4.25"), BigDecimal("2.5"), 1, 1),
            )

            store.write(batch, listOf(first)).join()
            store.write(batch, listOf(first)).join()
            store.write(
                UUID.randomUUID(),
                listOf(first.copy(totals = EarningsTotals(BigDecimal("0.75"), BigDecimal("1.5"), 1, 1))),
            ).join()

            val rows = store.read(player, "builder", hour).join()
            rows shouldHaveSize 1
            rows.single().totals shouldBe EarningsTotals(BigDecimal("5.000000"), BigDecimal("4.000000"), 2, 2)

            store.prune(hour + 1).join() shouldBe 1
            store.read(player, "builder", hour).join() shouldBe emptyList()
        }
    }

    "legacy decimal rows survive capacity migration and a failed batch retries exactly once" {
        val player = UUID.randomUUID()
        val other = UUID.randomUUID()
        val hour = 600_000L
        val maximum = BigDecimal("999999999999999999.999999")
        val entry = HourlyEarnings(player, "builder", hour, EarningsTotals(maximum, maximum, 1, 1))
        val sibling = entry.copy(playerId = other, totals = EarningsTotals(BigDecimal.ONE, BigDecimal.ONE, 1, 1))
        val retryId = UUID.randomUUID()
        val voucherId = UUID.randomUUID()
        val voucher = OneTimeUseClaimRequest(
            identity = OneTimeUseIdentity(voucherId, OneTimeUseFingerprint.fromBytes(ByteArray(32) { 1 })),
            claimId = voucherId,
            claimantId = player,
        )
        VoucherLedgerStorage.open(settings).use { ledger ->
            val acquired = ledger.claim(voucher).join().shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>()
            ledger.commit(acquired.claim).join() shouldBe OneTimeUseCommitResult.COMMITTED
        }

        MySqlHourlyEarningsStore.open(settings).use { store ->
            store.write(UUID.randomUUID(), listOf(entry)).join()
            // Recreate the deployed v4 column contract while retaining its real checksum
            // and data. Version 5 belongs to the voucher ledger, not earnings.
            DriverManager.getConnection(
                "jdbc:mysql://${settings.host}:${settings.port}/${settings.database}",
                settings.username,
                settings.password,
            ).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "ALTER TABLE arcecojobs_earnings_hourly " +
                            "MODIFY money DECIMAL(24,6) NOT NULL DEFAULT 0, " +
                            "MODIFY xp DECIMAL(24,6) NOT NULL DEFAULT 0",
                    )
                    statement.executeUpdate("DELETE FROM arcecojobs_schema_history WHERE version = 6")
                }
            }
            shouldThrow<CompletionException> { store.write(retryId, listOf(sibling, entry)).join() }
            store.read(other, "builder", hour).join() shouldBe emptyList()
            store.read(player, "builder", hour).join().single().totals.money shouldBe maximum
        }

        MySqlHourlyEarningsStore.open(settings).use { store ->
            store.read(player, "builder", hour).join().single().totals.money shouldBe maximum
            repeat(2) { store.write(retryId, listOf(sibling, entry)).join() }
            store.read(player, "builder", hour).join().single().totals shouldBe
                EarningsTotals(maximum * BigDecimal(2), maximum * BigDecimal(2), 2, 2)
            store.read(other, "builder", hour).join().single().totals.money shouldBe BigDecimal("1.000000")
        }
        MySqlHourlyEarningsStore.open(settings).use { store ->
            store.read(player, "builder", hour).join().single().totals.money shouldBe maximum * BigDecimal(2)
        }
        VoucherLedgerStorage.open(settings).use { ledger ->
            ledger.claim(voucher).join() shouldBe OneTimeUseClaimResult.AlreadyConsumed
        }
    }
})
