package ru.ruscrafting.ecojobs.boost

import net.luckperms.api.LuckPerms
import net.luckperms.api.model.user.User
import net.luckperms.api.node.Node
import net.luckperms.api.node.NodeType
import net.luckperms.api.node.matcher.NodeMatcher
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.ecojobs.config.AddonSettings
import ru.ruscrafting.ecojobs.domain.BoostInstance
import ru.ruscrafting.ecojobs.domain.BoostNodeCodec
import ru.ruscrafting.ecojobs.domain.BoostType
import ru.ruscrafting.ecojobs.domain.Multipliers
import ru.ruscrafting.ecojobs.domain.VoucherPayload
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Level

enum class GrantResult { GRANTED, ALREADY_USED, BUSY, FAILED }

internal fun interface BoostNodeFactory {
    fun create(permission: String, expiry: Instant?): Node
}

internal fun interface UsedVoucherLookup {
    fun isUsed(permission: String): CompletableFuture<Boolean>
}

private object LuckPermsBoostNodeFactory : BoostNodeFactory {
    override fun create(permission: String, expiry: Instant?): Node = Node.builder(permission).apply {
        if (expiry != null) expiry(expiry)
    }.build()
}

private class LuckPermsUsedVoucherLookup(private val luckPerms: LuckPerms) : UsedVoucherLookup {
    override fun isUsed(permission: String) = luckPerms.userManager
        .searchAll(NodeMatcher.key(permission))
        .thenApply { matches -> matches.values.flatten().any { it.value && it.key == permission } }
}

class BoostService internal constructor(
    private val plugin: JavaPlugin,
    private val luckPerms: LuckPerms,
    private val settings: () -> AddonSettings,
    private val nodeFactory: BoostNodeFactory = LuckPermsBoostNodeFactory,
    private val voucherLedger: VoucherLedger = UnavailableVoucherLedger,
    private val usedVoucherLookup: UsedVoucherLookup = LuckPermsUsedVoucherLookup(luckPerms),
) {
    private data class CacheEntry(val validUntilMillis: Long, val boosts: List<BoostInstance>)

    private val cache = ConcurrentHashMap<UUID, CacheEntry>()

    fun active(player: Player): List<BoostInstance> = active(player.uniqueId)

    fun active(uuid: UUID): List<BoostInstance> {
        val now = System.currentTimeMillis()
        cache[uuid]?.takeIf { it.validUntilMillis >= now }?.let { return it.boosts }
        val user = luckPerms.userManager.getUser(uuid) ?: return emptyList()
        return decode(user).also {
            cache[uuid] = CacheEntry(now + settings().boostCacheMillis, it)
        }
    }

    fun multiplier(player: Player, jobId: String, type: BoostType): Double =
        Multipliers.effective(active(player), jobId, type)

    fun redeem(payload: VoucherPayload, player: Player, callback: (GrantResult) -> Unit) {
        voucherLedger.claim(payload, player.uniqueId).whenComplete { result, claimFailure ->
            if (claimFailure != null || result == null) {
                logFailure("Could not claim voucher ${payload.voucherId}", claimFailure)
                onMain { callback(GrantResult.FAILED) }
                return@whenComplete
            }
            when (result) {
                VoucherClaimResult.AlreadyApplied -> onMain { callback(GrantResult.ALREADY_USED) }
                VoucherClaimResult.Busy -> onMain { callback(GrantResult.BUSY) }
                VoucherClaimResult.Conflict -> {
                    plugin.logger.severe("Voucher ${payload.voucherId} conflicts with an existing signed redemption identity")
                    onMain { callback(GrantResult.FAILED) }
                }
                is VoucherClaimResult.Acquired -> reconcileAndApply(payload, result.claim, callback)
            }
        }
    }

    fun grant(
        uuid: UUID,
        type: BoostType,
        multiplierBasisPoints: Int,
        duration: Duration,
        jobs: Set<String>,
        callback: (GrantResult) -> Unit,
    ) {
        val payload = VoucherPayload(
            presetId = "admin",
            voucherId = UUID.randomUUID(),
            type = type,
            multiplierBasisPoints = multiplierBasisPoints,
            durationSeconds = duration.seconds,
            jobs = jobs,
            issuedAtEpochSecond = Instant.now().epochSecond,
        )
        mutate(uuid, payload, addUsedMarker = false, callback)
    }

    private fun reconcileAndApply(
        payload: VoucherPayload,
        claim: VoucherClaim,
        callback: (GrantResult) -> Unit,
    ) {
        if (payload.signatureVersion == VoucherPayload.CURRENT_SIGNATURE_VERSION) {
            applyClaim(payload, claim, callback)
            return
        }
        val usedKey = BoostNodeCodec.used(payload.voucherId)
        val lookup = runCatching { usedVoucherLookup.isUsed(usedKey) }.getOrElse {
            logFailure("Could not start legacy voucher reconciliation for ${payload.voucherId}", it)
            failBeforeMutation(claim, callback)
            return
        }
        lookup.orTimeout(LP_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete { previouslyUsed, searchFailure ->
            if (searchFailure != null || previouslyUsed == null) {
                logFailure("Could not reconcile legacy voucher ${payload.voucherId}", searchFailure)
                failBeforeMutation(claim, callback)
                return@whenComplete
            }
            if (previouslyUsed) {
                finishClaim(claim, GrantResult.ALREADY_USED, callback)
                return@whenComplete
            }
            applyClaim(payload, claim, callback)
        }
    }

    private fun applyClaim(
        payload: VoucherPayload,
        claim: VoucherClaim,
        callback: (GrantResult) -> Unit,
    ) {
        luckPerms.userManager.loadUser(claim.redeemerId)
            .orTimeout(LP_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .whenComplete { user, loadFailure ->
                if (loadFailure != null || user == null) {
                    logFailure("Could not load voucher redeemer for ${payload.voucherId}", loadFailure)
                    failBeforeMutation(claim, callback)
                    return@whenComplete
                }
                val usedKey = BoostNodeCodec.used(payload.voucherId)
                if (user.getNodes(NodeType.PERMISSION).any { it.value && it.permission == usedKey }) {
                    finishClaim(claim, GrantResult.ALREADY_USED, callback)
                    return@whenComplete
                }
                val expiry = Instant.now().plusSeconds(payload.durationSeconds)
                val boostNodes = runCatching {
                    payload.jobs.map { scope ->
                        nodeFactory.create(
                            BoostNodeCodec.encode(payload.type, payload.multiplierBasisPoints, scope, payload.voucherId),
                            expiry,
                        )
                    }
                }.getOrElse {
                    failBeforeMutation(claim, callback)
                    return@whenComplete
                }
                val added = mutableListOf<Node>()
                val marker = nodeFactory.create(usedKey, null)
                if (!user.data().add(marker).wasSuccessful()) {
                    if (user.getNodes(NodeType.PERMISSION).any { it.value && it.permission == usedKey }) {
                        finishClaim(claim, GrantResult.ALREADY_USED, callback)
                    } else {
                        failBeforeMutation(claim, callback)
                    }
                    return@whenComplete
                }
                added += marker
                boostNodes.forEach { node ->
                    if (!user.data().add(node).wasSuccessful()) {
                        if (rollback(user, added)) {
                            failBeforeMutation(claim, callback)
                        } else {
                            failAfterPossibleMutation(claim, callback)
                        }
                        return@whenComplete
                    }
                    added += node
                }
                val save = runCatching { luckPerms.userManager.saveUser(user) }.getOrElse {
                    rollback(user, added)
                    logFailure("Could not start LuckPerms save for voucher ${payload.voucherId}", it)
                    failAfterPossibleMutation(claim, callback)
                    return@whenComplete
                }
                save.orTimeout(LP_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete { _, saveFailure ->
                    if (saveFailure != null) {
                        rollback(user, added)
                        cache.remove(claim.redeemerId)
                        logFailure("LuckPerms save failed for voucher ${payload.voucherId}", saveFailure)
                        failAfterPossibleMutation(claim, callback)
                        return@whenComplete
                    }
                    cache.remove(claim.redeemerId)
                    finishClaim(claim, GrantResult.GRANTED, callback)
                }
            }
    }

    private fun failBeforeMutation(claim: VoucherClaim, callback: (GrantResult) -> Unit) {
        val released = if (claim.newlyCreated) voucherLedger.release(claim) else voucherLedger.abandon(claim)
        released.whenComplete { closed, failure ->
            if (failure != null || closed != true) {
                logFailure("Could not close failed voucher claim ${claim.voucherId}", failure)
            }
            onMain { callback(GrantResult.FAILED) }
        }
    }

    private fun failAfterPossibleMutation(claim: VoucherClaim, callback: (GrantResult) -> Unit) {
        voucherLedger.abandon(claim).whenComplete { closed, failure ->
            if (failure != null || closed != true) {
                logFailure("Could not release uncertain voucher claim ${claim.voucherId}", failure)
            }
            onMain { callback(GrantResult.FAILED) }
        }
    }

    private fun finishClaim(
        claim: VoucherClaim,
        result: GrantResult,
        callback: (GrantResult) -> Unit,
    ) {
        voucherLedger.markApplied(claim).whenComplete { applied, failure ->
            if (failure != null || applied != true) {
                logFailure("Could not finalize voucher claim ${claim.voucherId}", failure)
            }
            onMain { callback(if (failure == null && applied == true) result else GrantResult.FAILED) }
        }
    }

    fun loadActive(uuid: UUID, callback: (List<BoostInstance>?, Throwable?) -> Unit) {
        luckPerms.userManager.loadUser(uuid).whenComplete { user, failure ->
            if (failure != null || user == null) {
                onMain { callback(null, failure ?: IllegalStateException("LuckPerms user was unavailable")) }
            } else {
                val boosts = decode(user)
                cache[uuid] = CacheEntry(System.currentTimeMillis() + settings().boostCacheMillis, boosts)
                onMain { callback(boosts, null) }
            }
        }
    }

    fun revoke(uuid: UUID, selector: String, callback: (Int?, Throwable?) -> Unit) {
        luckPerms.userManager.loadUser(uuid).whenComplete { user, loadFailure ->
            if (loadFailure != null || user == null) {
                onMain { callback(null, loadFailure ?: IllegalStateException("LuckPerms user was unavailable")) }
                return@whenComplete
            }
            val normalized = selector.lowercase()
            val byInstance = user.getNodes(NodeType.PERMISSION).mapNotNull { permission ->
                val decoded = BoostNodeCodec.decode(permission.permission) ?: return@mapNotNull null
                decoded.instanceId to permission
            }.groupBy({ it.first }, { it.second })
            val selectedIds = when {
                normalized == "all" -> byInstance.keys
                !INSTANCE_SELECTOR.matches(normalized) -> emptySet()
                else -> byInstance.keys.filter { it.toString().startsWith(normalized) }.takeIf { it.size == 1 }?.toSet().orEmpty()
            }
            val nodes = selectedIds.flatMap { byInstance.getValue(it) }
            val removed = nodes.filter { user.data().remove(it).wasSuccessful() }
            val instanceCount = removed.mapNotNull { BoostNodeCodec.decode(it.permission)?.instanceId }.distinct().size
            if (removed.isEmpty()) {
                onMain { callback(0, null) }
                return@whenComplete
            }
            val save = runCatching { luckPerms.userManager.saveUser(user) }.getOrElse { failure ->
                restore(user, removed)
                onMain { callback(null, failure) }
                return@whenComplete
            }
            save.whenComplete { _, saveFailure ->
                if (saveFailure == null) {
                    cache.remove(uuid)
                } else {
                    restore(user, removed)
                }
                onMain { callback(instanceCount.takeIf { saveFailure == null }, saveFailure) }
            }
        }
    }

    fun invalidate(uuid: UUID) {
        cache.remove(uuid)
    }

    private fun mutate(
        uuid: UUID,
        payload: VoucherPayload,
        addUsedMarker: Boolean,
        callback: (GrantResult) -> Unit,
    ) {
        luckPerms.userManager.loadUser(uuid).whenComplete { user, loadFailure ->
            if (loadFailure != null || user == null) {
                onMain { callback(GrantResult.FAILED) }
                return@whenComplete
            }
            val usedKey = BoostNodeCodec.used(payload.voucherId)
            if (addUsedMarker && user.getNodes(NodeType.PERMISSION).any { it.value && it.permission == usedKey }) {
                onMain { callback(GrantResult.ALREADY_USED) }
                return@whenComplete
            }
            val expiry = Instant.now().plusSeconds(payload.durationSeconds)
            val boostNodes = runCatching {
                payload.jobs.map { scope ->
                    nodeFactory.create(
                        BoostNodeCodec.encode(payload.type, payload.multiplierBasisPoints, scope, payload.voucherId),
                        expiry,
                    )
                }
            }.getOrElse {
                onMain { callback(GrantResult.FAILED) }
                return@whenComplete
            }
            val added = mutableListOf<Node>()
            val save = runCatching {
                if (addUsedMarker) {
                    val marker = nodeFactory.create(usedKey, null)
                    if (!user.data().add(marker).wasSuccessful()) {
                        onMain { callback(GrantResult.ALREADY_USED) }
                        return@whenComplete
                    }
                    added += marker
                }
                boostNodes.forEach { node ->
                    require(user.data().add(node).wasSuccessful()) { "Boost node was not added" }
                    added += node
                }
                luckPerms.userManager.saveUser(user)
            }.getOrElse {
                rollback(user, added)
                onMain { callback(GrantResult.FAILED) }
                return@whenComplete
            }
            save.whenComplete { _, saveFailure ->
                if (saveFailure != null) {
                    rollback(user, added)
                }
                cache.remove(uuid)
                onMain { callback(if (saveFailure == null) GrantResult.GRANTED else GrantResult.FAILED) }
            }
        }
    }

    private fun decode(user: User): List<BoostInstance> {
        data class Key(val id: UUID, val type: BoostType, val multiplier: Int, val expiry: Instant)
        val grouped = linkedMapOf<Key, MutableSet<String>>()
        user.getNodes(NodeType.PERMISSION).asSequence()
            .filter { it.value && it.hasExpiry() && !it.hasExpired() }
            .forEach { permission ->
                val decoded = BoostNodeCodec.decode(permission.permission) ?: return@forEach
                val expiry = permission.expiry ?: return@forEach
                val key = Key(decoded.instanceId, decoded.type, decoded.multiplierBasisPoints, expiry)
                grouped.getOrPut(key, ::linkedSetOf).add(decoded.scope)
            }
        return grouped.map { (key, scopes) ->
            BoostInstance(key.id, key.type, key.multiplier, scopes, key.expiry)
        }.sortedBy(BoostInstance::expiresAt)
    }

    private fun rollback(user: User, nodes: Collection<Node>): Boolean {
        var complete = true
        nodes.forEach { node ->
            runCatching { require(user.data().remove(node).wasSuccessful()) { "node was not present" } }
                .onFailure {
                    complete = false
                    plugin.logger.warning("Could not roll back an unsaved ArcEcoJobs node: ${it.message}")
                }
        }
        return complete
    }

    private fun restore(user: User, nodes: Collection<Node>) {
        nodes.forEach { node ->
            runCatching { require(user.data().add(node).wasSuccessful()) { "node was not restored" } }
                .onFailure { plugin.logger.severe("Could not restore an ArcEcoJobs node after a failed save: ${it.message}") }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (!plugin.isEnabled) return
        plugin.server.scheduler.runTask(plugin, Runnable(block))
    }

    private fun logFailure(message: String, failure: Throwable?) {
        if (failure == null) plugin.logger.severe(message) else plugin.logger.log(Level.SEVERE, message, failure)
    }

    companion object {
        private const val LP_OPERATION_TIMEOUT_SECONDS = 15L
        private val INSTANCE_SELECTOR = Regex("(?:[0-9a-f]{8}|[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})")
    }
}
