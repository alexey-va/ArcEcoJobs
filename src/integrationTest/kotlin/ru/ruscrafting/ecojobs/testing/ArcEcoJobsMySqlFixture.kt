package ru.ruscrafting.ecojobs.testing

import ru.arc.testing.containers.MySqlInitScript
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import ru.ruscrafting.ecojobs.config.RedemptionStorageSettings

class ArcEcoJobsMySqlFixture private constructor(
    private val service: MySqlTestService,
    val settings: RedemptionStorageSettings,
) : AutoCloseable {
    override fun close() = service.close()

    companion object {
        fun start(
            database: String,
            leastPrivilegeScript: String? = null,
        ): ArcEcoJobsMySqlFixture {
            val service = MySqlTestService.start(
                MySqlTestSettings(
                    database = database,
                    username = "arcecojobs_test",
                    password = "test-password",
                    provisionUser = leastPrivilegeScript == null,
                    initScripts = leastPrivilegeScript?.let { listOf(MySqlInitScript(it)) }.orEmpty(),
                ),
            )
            val endpoint = service.endpoint
            return ArcEcoJobsMySqlFixture(
                service,
                RedemptionStorageSettings(
                    enabled = true,
                    host = endpoint.host,
                    port = endpoint.port,
                    database = endpoint.database,
                    username = endpoint.username,
                    password = endpoint.password,
                    sslMode = "DISABLED",
                    minimumIdle = 0,
                    maximumPoolSize = 2,
                    connectionTimeoutMs = 10_000,
                    validationTimeoutMs = 5_000,
                    maxLifetimeMs = 60_000,
                ),
            )
        }
    }
}
