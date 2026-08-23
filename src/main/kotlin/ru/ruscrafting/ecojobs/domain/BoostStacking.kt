package ru.ruscrafting.ecojobs.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

sealed interface BoostApplicationDecision {
    data class Stack(
        val totalRemaining: Duration,
        val replacedInstanceIds: Set<UUID>,
    ) : BoostApplicationDecision

    data class TypeConflict(val remaining: Duration) : BoostApplicationDecision

    data class EffectConflict(val remaining: Duration) : BoostApplicationDecision

    data object LimitExceeded : BoostApplicationDecision
}

object BoostStacking {
    fun decide(
        active: Collection<BoostInstance>,
        payload: VoucherPayload,
        now: Instant,
        maximumStackedDuration: Duration,
    ): BoostApplicationDecision {
        val current = active.filter { it.expiresAt > now }
        val otherTypes = current.filter { it.type != payload.type }
        if (otherTypes.isNotEmpty()) {
            return BoostApplicationDecision.TypeConflict(maximumRemaining(otherTypes, now))
        }

        val normalizedJobs = payload.jobs.map(String::lowercase).toSet()
        val incompatible = current.filter {
            it.multiplierBasisPoints != payload.multiplierBasisPoints ||
                it.jobs.map(String::lowercase).toSet() != normalizedJobs
        }
        if (incompatible.isNotEmpty()) {
            return BoostApplicationDecision.EffectConflict(maximumRemaining(incompatible, now))
        }

        val totalSeconds = runCatching {
            current.fold(payload.durationSeconds) { total, instance ->
                Math.addExact(total, remainingSeconds(instance.expiresAt, now))
            }
        }.getOrNull() ?: return BoostApplicationDecision.LimitExceeded
        if (totalSeconds > maximumStackedDuration.seconds) return BoostApplicationDecision.LimitExceeded

        return BoostApplicationDecision.Stack(
            totalRemaining = Duration.ofSeconds(totalSeconds),
            replacedInstanceIds = current.mapTo(linkedSetOf(), BoostInstance::instanceId),
        )
    }

    private fun maximumRemaining(instances: Collection<BoostInstance>, now: Instant): Duration =
        Duration.ofSeconds(instances.maxOf { remainingSeconds(it.expiresAt, now) })

    private fun remainingSeconds(expiry: Instant, now: Instant): Long {
        val remaining = Duration.between(now, expiry)
        return remaining.seconds + if (remaining.nano > 0) 1 else 0
    }
}
