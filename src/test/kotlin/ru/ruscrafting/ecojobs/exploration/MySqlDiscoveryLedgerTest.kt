package ru.ruscrafting.ecojobs.exploration

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import ru.ruscrafting.ecojobs.config.RedemptionStorageSettings
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

class MySqlDiscoveryLedgerTest : StringSpec({
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

    "least-privilege production grants can create the discovery schema" {
        MySqlDiscoveryLedger.open(settings).use { ledger ->
            val claim = ledger.claim(
                DiscoveryKey(UUID.randomUUID(), 0, 0),
                UUID.randomUUID(),
            ).join().shouldBeInstanceOf<DiscoveryClaimResult.Acquired>().claim
            ledger.finish(claim, DiscoveryDeliveryStatus.APPLIED).join() shouldBe true
        }
    }

    "two nodes assign the first five different explorers unique ordered ranks" {
        val firstNode = MySqlDiscoveryLedger.open(settings)
        val secondNode = MySqlDiscoveryLedger.open(settings)
        try {
            val key = DiscoveryKey(UUID.randomUUID(), 128, -64)
            val gate = CountDownLatch(1)
            val attempts = List(6) { index ->
                CompletableFuture.supplyAsync {
                    gate.await()
                    val node = if (index % 2 == 0) firstNode else secondNode
                    node.claim(key, UUID.randomUUID()).join()
                }
            }
            gate.countDown()
            val results = attempts.map(CompletableFuture<DiscoveryClaimResult>::join)
            val acquired = results.filterIsInstance<DiscoveryClaimResult.Acquired>()

            acquired shouldHaveSize 5
            acquired.map { it.claim.rank }.sorted() shouldBe listOf(1, 2, 3, 4, 5)
            results.filterIsInstance<DiscoveryClaimResult.Saturated>() shouldHaveSize 1
            acquired.forEach { result ->
                val node = if (result.claim.rank % 2 == 0) firstNode else secondNode
                node.finish(result.claim, DiscoveryDeliveryStatus.APPLIED).join() shouldBe true
            }
        } finally {
            firstNode.close()
            secondNode.close()
        }
    }

    "one player can never earn twice from the same chunk" {
        MySqlDiscoveryLedger.open(settings).use { ledger ->
            val key = DiscoveryKey(UUID.randomUUID(), 4, 9)
            val player = UUID.randomUUID()
            val claim = ledger.claim(key, player).join().shouldBeInstanceOf<DiscoveryClaimResult.Acquired>().claim
            claim.rank shouldBe 1
            ledger.finish(claim, DiscoveryDeliveryStatus.APPLIED).join() shouldBe true
            ledger.finish(claim, DiscoveryDeliveryStatus.APPLIED).join() shouldBe true
            ledger.claim(key, player).join() shouldBe DiscoveryClaimResult.AlreadyDiscovered
        }
    }

    "world and chunk coordinates form independent discovery scopes" {
        MySqlDiscoveryLedger.open(settings).use { ledger ->
            val world = UUID.randomUUID()
            val player = UUID.randomUUID()
            val claims = listOf(
                DiscoveryKey(world, 1, 1),
                DiscoveryKey(world, 1, 2),
                DiscoveryKey(UUID.randomUUID(), 1, 1),
            ).map { key ->
                ledger.claim(key, player).join().shouldBeInstanceOf<DiscoveryClaimResult.Acquired>().claim
            }

            claims.map { it.rank } shouldBe listOf(1, 1, 1)
            claims.forEach { ledger.finish(it, DiscoveryDeliveryStatus.ABANDONED).join() shouldBe true }
        }
    }

    "migration replay preserves completed discoveries" {
        val key = DiscoveryKey(UUID.randomUUID(), -7, 12)
        val player = UUID.randomUUID()
        MySqlDiscoveryLedger.open(settings).use { ledger ->
            val claim = ledger.claim(key, player).join().shouldBeInstanceOf<DiscoveryClaimResult.Acquired>().claim
            ledger.finish(claim, DiscoveryDeliveryStatus.APPLIED).join() shouldBe true
        }
        MySqlDiscoveryLedger.open(settings).use { reopened ->
            reopened.claim(key, player).join() shouldBe DiscoveryClaimResult.AlreadyDiscovered
        }
    }
})
