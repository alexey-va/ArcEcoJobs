package ru.ruscrafting.ecojobs.config

import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlSslMode

/** Maps the validated plugin profile into the one shared arc-core SQL contract. */
fun RedemptionStorageSettings.toSqlConnectionConfig(poolSize: Int): SqlConnectionConfig {
    require(poolSize in 1..maximumPoolSize) {
        "ArcEcoJobs SQL pool size must be between 1 and the configured maximum"
    }
    return SqlConnectionConfig(
        host = host,
        port = port,
        database = database,
        username = username,
        password = password,
        sslMode = SqlSslMode.valueOf(sslMode),
        minimumIdle = minimumIdle.coerceAtMost(poolSize),
        maximumPoolSize = poolSize,
        connectionTimeoutMs = connectionTimeoutMs,
        socketTimeoutMs = 30_000,
        validationTimeoutMs = validationTimeoutMs,
        maxLifetimeMs = maxLifetimeMs,
        failFast = true,
    )
}
