package ru.ruscrafting.ecojobs.boost

import ru.arc.onetime.OneTimeUseAbandonResult
import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.onetime.OneTimeUseReleaseResult
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal class TestVoucherLedger : OneTimeUseLedger {
    private data class Row(
        val request: OneTimeUseClaimRequest,
        var committed: Boolean,
    )

    private val rows = linkedMapOf<UUID, Row>()
    var failNextCommit = false

    @Synchronized
    override fun claim(request: OneTimeUseClaimRequest): CompletableFuture<OneTimeUseClaimResult> {
        val existing = rows[request.identity.useId]
        if (existing != null) {
            val result = when {
                existing.request.identity.fingerprint != request.identity.fingerprint -> OneTimeUseClaimResult.IdentityConflict
                existing.committed -> OneTimeUseClaimResult.AlreadyConsumed
                existing.request != request -> OneTimeUseClaimResult.Busy
                else -> OneTimeUseClaimResult.Acquired(OneTimeUseClaim.acquired(request, newlyCreated = false))
            }
            return CompletableFuture.completedFuture(result)
        }
        rows[request.identity.useId] = Row(request, committed = false)
        return CompletableFuture.completedFuture(
            OneTimeUseClaimResult.Acquired(OneTimeUseClaim.acquired(request, newlyCreated = true)),
        )
    }

    @Synchronized
    override fun commit(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseCommitResult> {
        if (failNextCommit) {
            failNextCommit = false
            return CompletableFuture.failedFuture(IllegalStateException("ledger unavailable"))
        }
        val row = rows[claim.identity.useId] ?: return CompletableFuture.completedFuture(OneTimeUseCommitResult.REJECTED)
        if (row.request != claim.asRequest()) return CompletableFuture.completedFuture(OneTimeUseCommitResult.REJECTED)
        val result = if (row.committed) OneTimeUseCommitResult.ALREADY_COMMITTED else OneTimeUseCommitResult.COMMITTED
        row.committed = true
        return CompletableFuture.completedFuture(result)
    }

    @Synchronized
    override fun release(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseReleaseResult> {
        val row = rows[claim.identity.useId]
        val removed = row != null && !row.committed && row.request == claim.asRequest() && rows.remove(claim.identity.useId) != null
        return CompletableFuture.completedFuture(
            if (removed) OneTimeUseReleaseResult.RELEASED else OneTimeUseReleaseResult.REJECTED,
        )
    }

    @Synchronized
    override fun abandon(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseAbandonResult> {
        val row = rows[claim.identity.useId]
        val result = when {
            row == null || row.request != claim.asRequest() -> OneTimeUseAbandonResult.REJECTED
            row.committed -> OneTimeUseAbandonResult.ALREADY_COMMITTED
            else -> OneTimeUseAbandonResult.RETAINED_FOR_RECOVERY
        }
        return CompletableFuture.completedFuture(result)
    }
}
