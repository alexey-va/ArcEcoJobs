package ru.ruscrafting.ecojobs.earnings

import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Carries the exact EcoJobs job through PlaceholderAPI's amount evaluation to
 * eco's immediately following economy deposit without changing the payout.
 */
class MoneyAttribution(
    private val nanoTime: () -> Long = System::nanoTime,
    private val ttlNanos: Long = TimeUnit.SECONDS.toNanos(1),
) {
    private data class Marker(
        val playerId: UUID,
        val jobId: String,
        val expiresAt: Long,
    )

    private val current = ThreadLocal<Marker>()

    fun mark(playerId: UUID, jobId: String) {
        current.set(Marker(playerId, jobId.lowercase(), nanoTime() + ttlNanos))
    }

    fun consume(playerId: UUID): String? {
        val marker = current.get() ?: return null
        current.remove()
        return marker.jobId.takeIf { marker.playerId == playerId && nanoTime() <= marker.expiresAt }
    }

    fun clear() = current.remove()
}
