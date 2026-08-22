package ru.ruscrafting.ecojobs.boost

import ru.ruscrafting.ecojobs.domain.VoucherPayload
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal class TestVoucherLedger : VoucherLedger {
    private data class Row(
        val payloadHash: ByteArray,
        val redeemerId: UUID,
        val token: UUID,
        var applied: Boolean,
    )

    private val rows = linkedMapOf<UUID, Row>()
    var failNextMark = false

    override val available: Boolean = true

    @Synchronized
    override fun claim(payload: VoucherPayload, redeemerId: UUID): CompletableFuture<VoucherClaimResult> {
        val existing = rows[payload.voucherId]
        if (existing != null) {
            val result = when {
                !existing.payloadHash.contentEquals(payload.fingerprint()) -> VoucherClaimResult.Conflict
                existing.applied -> VoucherClaimResult.AlreadyApplied
                existing.redeemerId != redeemerId -> VoucherClaimResult.Busy
                else -> VoucherClaimResult.Acquired(
                    VoucherClaim(payload.voucherId, redeemerId, existing.token, newlyCreated = false),
                )
            }
            return CompletableFuture.completedFuture(result)
        }
        val token = UUID.randomUUID()
        rows[payload.voucherId] = Row(payload.fingerprint(), redeemerId, token, applied = false)
        return CompletableFuture.completedFuture(
            VoucherClaimResult.Acquired(VoucherClaim(payload.voucherId, redeemerId, token, newlyCreated = true)),
        )
    }

    @Synchronized
    override fun markApplied(claim: VoucherClaim): CompletableFuture<Boolean> {
        if (failNextMark) {
            failNextMark = false
            return CompletableFuture.failedFuture(IllegalStateException("ledger unavailable"))
        }
        val row = rows[claim.voucherId]
        val applied = row != null && row.token == claim.token
        if (applied) row.applied = true
        return CompletableFuture.completedFuture(applied)
    }

    @Synchronized
    override fun release(claim: VoucherClaim): CompletableFuture<Boolean> {
        val row = rows[claim.voucherId]
        val removed = row != null && !row.applied && row.token == claim.token && rows.remove(claim.voucherId) != null
        return CompletableFuture.completedFuture(removed)
    }

    @Synchronized
    override fun abandon(claim: VoucherClaim): CompletableFuture<Boolean> {
        val row = rows[claim.voucherId]
        return CompletableFuture.completedFuture(row != null && row.token == claim.token)
    }
}
