package ru.ruscrafting.ecojobs.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.sql.SqlSslMode

class ArcEcoJobsSqlConfigTest : StringSpec({
    val settings = RedemptionStorageSettings(
        enabled = true,
        host = "mysql.internal",
        port = 3307,
        database = "arcecojobs",
        username = "jobs",
        password = "test-password",
        sslMode = "VERIFY_IDENTITY",
        minimumIdle = 3,
        maximumPoolSize = 4,
        connectionTimeoutMs = 8_000,
        validationTimeoutMs = 2_000,
        maxLifetimeMs = 120_000,
    )

    "validated plugin settings map completely into the shared SQL contract" {
        val config = settings.toSqlConnectionConfig(poolSize = 2)

        config.host shouldBe "mysql.internal"
        config.port shouldBe 3307
        config.database shouldBe "arcecojobs"
        config.username shouldBe "jobs"
        config.password shouldBe "test-password"
        config.sslMode shouldBe SqlSslMode.VERIFY_IDENTITY
        config.minimumIdle shouldBe 2
        config.maximumPoolSize shouldBe 2
        config.connectionTimeoutMs shouldBe 8_000
        config.socketTimeoutMs shouldBe 30_000
        config.validationTimeoutMs shouldBe 2_000
        config.maxLifetimeMs shouldBe 120_000
        config.failFast shouldBe true
        config.toString().contains("test-password") shouldBe false
    }

    "feature pool size remains inside the validated operator maximum" {
        shouldThrow<IllegalArgumentException> { settings.toSqlConnectionConfig(poolSize = 0) }
        shouldThrow<IllegalArgumentException> { settings.toSqlConnectionConfig(poolSize = 5) }
    }
})
