package ru.ruscrafting.ecojobs.earnings

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import ru.ruscrafting.ecojobs.config.RedemptionStorageSettings
import java.math.BigDecimal
import java.util.UUID

class MySqlHourlyEarningsStoreTest : StringSpec({
    val mysql: GenericContainer<*> = GenericContainer(DockerImageName.parse("mysql:8.0.46"))
        .withEnv("MYSQL_DATABASE", "arcecojobs_exploration_test")
        .withEnv("MYSQL_ROOT_PASSWORD", "root-password")
        .withCopyFileToContainer(
            MountableFile.forClasspathResource("mysql/arcecojobs-least-privilege.sql"),
            "/docker-entrypoint-initdb.d/10-arcecojobs-least-privilege.sql",
        )
        .withExposedPorts(3306)
        .waitingFor(Wait.forLogMessage(".*ready for connections.*\\n", 2))

    lateinit var settings: RedemptionStorageSettings

    beforeSpec {
        mysql.start()
        settings = RedemptionStorageSettings(
            enabled = true,
            host = mysql.host,
            port = mysql.getMappedPort(3306),
            database = "arcecojobs_exploration_test",
            username = "arcecojobs_test",
            password = "test-password",
            sslMode = "DISABLED",
            minimumIdle = 0,
            maximumPoolSize = 2,
            connectionTimeoutMs = 10_000,
            validationTimeoutMs = 5_000,
            maxLifetimeMs = 60_000,
        )
    }

    afterSpec { mysql.stop() }

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
