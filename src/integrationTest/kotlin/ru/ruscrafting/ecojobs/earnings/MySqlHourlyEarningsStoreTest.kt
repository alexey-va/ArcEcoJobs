package ru.ruscrafting.ecojobs.earnings

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import ru.ruscrafting.ecojobs.config.RedemptionStorageSettings
import ru.ruscrafting.ecojobs.testing.ArcEcoJobsMySqlFixture
import java.math.BigDecimal
import java.util.UUID

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
})
