package ru.ruscrafting.ecojobs.boost

import net.luckperms.api.LuckPerms
import net.luckperms.api.model.user.User
import net.luckperms.api.node.Node
import net.luckperms.api.node.NodeType
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
import java.util.concurrent.ConcurrentHashMap

enum class GrantResult { GRANTED, ALREADY_USED, WRONG_OWNER, FAILED }

internal fun interface BoostNodeFactory {
    fun create(permission: String, expiry: Instant?): Node
}

private object LuckPermsBoostNodeFactory : BoostNodeFactory {
    override fun create(permission: String, expiry: Instant?): Node = Node.builder(permission).apply {
        if (expiry != null) expiry(expiry)
    }.build()
}

class BoostService internal constructor(
    private val plugin: JavaPlugin,
    private val luckPerms: LuckPerms,
    private val settings: () -> AddonSettings,
    private val nodeFactory: BoostNodeFactory = LuckPermsBoostNodeFactory,
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
        if (payload.recipientId != player.uniqueId) {
            onMain { callback(GrantResult.WRONG_OWNER) }
            return
        }
        mutate(player.uniqueId, payload, addUsedMarker = true, callback)
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
            recipientId = uuid,
            type = type,
            multiplierBasisPoints = multiplierBasisPoints,
            durationSeconds = duration.seconds,
            jobs = jobs,
            issuedAtEpochSecond = Instant.now().epochSecond,
        )
        mutate(uuid, payload, addUsedMarker = false, callback)
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
                    if (user.data().add(node).wasSuccessful()) added += node
                }
                require(added.isNotEmpty()) { "No boost nodes were added" }
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

    private fun rollback(user: User, nodes: Collection<Node>) {
        nodes.forEach { node ->
            runCatching { require(user.data().remove(node).wasSuccessful()) { "node was not present" } }
                .onFailure { plugin.logger.warning("Could not roll back an unsaved ArcEcoJobs node: ${it.message}") }
        }
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

    companion object {
        private val INSTANCE_SELECTOR = Regex("(?:[0-9a-f]{8}|[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})")
    }
}
