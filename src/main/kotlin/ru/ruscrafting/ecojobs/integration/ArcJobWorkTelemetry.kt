package ru.ruscrafting.ecojobs.integration

import java.util.UUID

/** Optional ARC bridge for accepted EcoJobs XP-event observations. */
internal object ArcJobWorkTelemetry {
    private val bridge by lazy { discover() }

    fun record(playerId: UUID, jobId: String): Boolean = runCatching {
        bridge.record(playerId, jobId)
    }.getOrDefault(false)

    fun breakPlayer(playerId: UUID) {
        runCatching { bridge.breakPlayer(playerId) }
    }

    internal fun discover(className: String = BRIDGE_CLASS): Bridge = runCatching {
        val type = Class.forName(className)
        Bridge(
            type.getMethod("recordJobWork", UUID::class.java, String::class.java),
            type.getMethod("breakJobWork", UUID::class.java),
        )
    }.getOrElse { Bridge(null, null) }

    internal class Bridge(
        private val recordMethod: java.lang.reflect.Method?,
        private val breakMethod: java.lang.reflect.Method?,
    ) {
        fun record(playerId: UUID, jobId: String): Boolean =
            (recordMethod?.invoke(null, playerId, jobId) as? Boolean) == true

        fun breakPlayer(playerId: UUID) {
            breakMethod?.invoke(null, playerId)
        }
    }

    private const val BRIDGE_CLASS = "ru.arc.metrics.ExternalProductTelemetryBridge"
}
