package ru.ruscrafting.ecojobs.boost

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.luckperms.api.LuckPerms
import net.luckperms.api.model.data.DataMutateResult
import net.luckperms.api.model.data.NodeMap
import net.luckperms.api.model.user.User
import net.luckperms.api.model.user.UserManager
import net.luckperms.api.node.Node
import net.luckperms.api.node.NodeType
import net.luckperms.api.node.types.PermissionNode
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ecojobs.config.AddonSettings
import ru.ruscrafting.ecojobs.config.GuiItems
import ru.ruscrafting.ecojobs.domain.BoostNodeCodec
import ru.ruscrafting.ecojobs.domain.BoostType
import ru.ruscrafting.ecojobs.domain.VoucherPayload
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

class BoostServiceTest : StringSpec({
    "redeem is replay-safe and revoke counts one multi-scope boost instance" {
        MockBukkitTestRuntime.open().use(::runBoostServiceScenario)
    }

    "mutations for one player are serialized through the LuckPerms save" {
        MockBukkitTestRuntime.open().use { paper ->
            ru.ruscrafting.ecojobs.paper.requireSupportedMockBukkit {
                val plugin = paper.createSimplePlugin("ArcEcoJobsBoostQueueTest")
                val playerId = UUID.randomUUID()
                val stored = linkedMapOf<String, Node>()
                val data = mockk<NodeMap>()
                every { data.add(any()) } answers {
                    val node = firstArg<Node>()
                    if (stored.putIfAbsent(node.key, node) == null) DataMutateResult.SUCCESS else DataMutateResult.FAIL_ALREADY_HAS
                }
                every { data.remove(any()) } answers {
                    val node = firstArg<Node>()
                    if (stored.remove(node.key) != null) DataMutateResult.SUCCESS else DataMutateResult.FAIL_LACKS
                }
                val user = mockk<User> {
                    every { data() } returns data
                    every { getNodes(NodeType.PERMISSION) } answers {
                        stored.values.filterIsInstance<PermissionNode>().toSet()
                    }
                }
                val firstSave = CompletableFuture<Void>()
                var saveCount = 0
                val users = mockk<UserManager> {
                    every { loadUser(playerId) } returns CompletableFuture.completedFuture(user)
                    every { saveUser(user) } answers {
                        if (saveCount++ == 0) firstSave else CompletableFuture.completedFuture(null)
                    }
                }
                val luckPerms = mockk<LuckPerms> {
                    every { userManager } returns users
                }
                val nodeFactory = BoostNodeFactory { permissionKey, expiry ->
                    mockk<PermissionNode> {
                        every { key } returns permissionKey
                        every { permission } returns permissionKey
                        every { value } returns true
                        every { hasExpiry() } returns true
                        every { hasExpired() } returns false
                        every { getExpiry() } returns expiry
                    }
                }
                val service = BoostService(plugin, luckPerms, { testSettings() }, nodeFactory)
                val outcomes = mutableListOf<GrantOutcome>()

                repeat(2) {
                    service.grantDetailed(
                        playerId,
                        BoostType.XP,
                        150,
                        Duration.ofHours(1),
                        setOf("miner"),
                        outcomes::add,
                    )
                }

                verify(exactly = 1) { users.loadUser(playerId) }
                firstSave.complete(null)
                paper.performTicks(2)

                verify(exactly = 2) { users.loadUser(playerId) }
                outcomes.map(GrantOutcome::result) shouldBe listOf(GrantResult.GRANTED, GrantResult.GRANTED)
            }
        }
    }
})

private fun testSettings() = AddonSettings(
    defaultLocale = "ru",
    useClientLocale = true,
    interceptEcoJobsRoot = true,
    leaderboardCache = Duration.ofSeconds(30),
    leaderboardEntriesPerPage = 10,
    boostCacheMillis = 1_000,
    minimumMultiplierBasisPoints = 101,
    maximumMultiplierBasisPoints = 1_000,
    maximumBoostDuration = Duration.ofDays(30),
    maximumStackedBoostDuration = Duration.ofDays(365),
    requireMoneyPlaceholder = true,
    guiItems = GuiItems.vanilla(),
)

private fun runBoostServiceScenario(paper: MockBukkitTestRuntime) =
    ru.ruscrafting.ecojobs.paper.requireSupportedMockBukkit {
        val server = paper.server
        val plugin = paper.createSimplePlugin("ArcEcoJobsBoostTest")
        val player = server.addPlayer("BoostQA")
        val stored = linkedMapOf<String, Node>()
        val data = mockk<NodeMap>()
        every { data.add(any()) } answers {
            val node = firstArg<Node>()
            if (stored.putIfAbsent(node.key, node) == null) DataMutateResult.SUCCESS else DataMutateResult.FAIL_ALREADY_HAS
        }
        every { data.remove(any()) } answers {
            val node = firstArg<Node>()
            if (stored.remove(node.key) != null) DataMutateResult.SUCCESS else DataMutateResult.FAIL_LACKS
        }
        val user = mockk<User>()
        every { user.data() } returns data
        every { user.getNodes(NodeType.PERMISSION) } answers {
            stored.values.filterIsInstance<PermissionNode>().toSet()
        }
        val users = mockk<UserManager>()
        every { users.loadUser(player.uniqueId, null) } returns CompletableFuture.completedFuture(user)
        every { users.loadUser(player.uniqueId) } returns CompletableFuture.completedFuture(user)
        every { users.saveUser(user) } returns CompletableFuture.completedFuture(null)
        val luckPerms = mockk<LuckPerms>()
        every { luckPerms.userManager } returns users
        val settings = AddonSettings(
            defaultLocale = "ru",
            useClientLocale = true,
            interceptEcoJobsRoot = true,
            leaderboardCache = Duration.ofSeconds(30),
            leaderboardEntriesPerPage = 10,
            boostCacheMillis = 1_000,
            minimumMultiplierBasisPoints = 101,
            maximumMultiplierBasisPoints = 1_000,
            maximumBoostDuration = Duration.ofDays(30),
            maximumStackedBoostDuration = Duration.ofDays(365),
            requireMoneyPlaceholder = true,
            guiItems = GuiItems.vanilla(),
        )
        val createdNodes = mutableMapOf<String, PermissionNode>()
        val nodeFactory = BoostNodeFactory { permissionKey, expiry ->
            createdNodes.getOrPut(permissionKey) {
                mockk<PermissionNode> {
                    every { key } returns permissionKey
                    every { permission } returns permissionKey
                    every { value } returns true
                    every { hasExpiry() } returns (expiry != null)
                    every { hasExpired() } returns false
                    every { getExpiry() } returns expiry
                }
            }
        }
        val ledger = TestVoucherLedger()
        var globallyUsed = false
        val service = BoostService(
            plugin,
            luckPerms,
            { settings },
            nodeFactory,
            ledger,
            UsedVoucherLookup { CompletableFuture.completedFuture(globallyUsed) },
        )
        val voucherId = UUID.randomUUID()
        val payload = VoucherPayload(
            presetId = "workday",
            voucherId = voucherId,
            type = BoostType.ALL,
            multiplierBasisPoints = 150,
            durationSeconds = 3_600,
            jobs = setOf("miner", "fisherman"),
            issuedAtEpochSecond = Instant.now().epochSecond,
        )

        val redemption = mutableListOf<GrantResult>()
        service.redeem(payload, player, redemption::add)
        plugin.isEnabled shouldBe true
        server.scheduler.performTicks(2)

        redemption shouldBe listOf(GrantResult.GRANTED)
        stored.size shouldBe 3
        stored.keys shouldContain BoostNodeCodec.used(voucherId)
        stored.keys.mapNotNull(BoostNodeCodec::decode).map { it.scope }.toSet() shouldBe setOf("miner", "fisherman")

        service.redeem(payload, player, redemption::add)
        server.scheduler.performTicks(2)
        redemption shouldBe listOf(GrantResult.GRANTED, GrantResult.ALREADY_USED)

        var revoked: Int? = null
        var revokeFailure: Throwable? = null
        service.revoke(player.uniqueId, "all") { count, failure ->
            revoked = count
            revokeFailure = failure
        }
        server.scheduler.performTicks(2)

        revoked shouldBe 1
        revokeFailure shouldBe null
        stored.keys.toList() shouldBe listOf(BoostNodeCodec.used(voucherId))

        service.redeem(payload.copy(voucherId = UUID.randomUUID(), jobs = setOf("invalid.scope")), player, redemption::add)
        server.scheduler.performTicks(2)
        redemption.last() shouldBe GrantResult.FAILED
        stored.keys.toList() shouldBe listOf(BoostNodeCodec.used(voucherId))

        val ambiguousIds = listOf(
            UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001"),
            UUID.fromString("aaaaaaaa-0000-4000-8000-000000000002"),
        )
        ambiguousIds.forEach { id ->
            val key = BoostNodeCodec.encode(BoostType.XP, 150, "miner", id)
            stored[key] = nodeFactory.create(key, Instant.now().plusSeconds(3_600))
        }
        var ambiguousCount: Int? = null
        service.revoke(player.uniqueId, "aaaaaaaa") { count, _ -> ambiguousCount = count }
        server.scheduler.performTicks(2)
        ambiguousCount shouldBe 0
        stored.keys.mapNotNull(BoostNodeCodec::decode).map { it.instanceId }.toSet() shouldBe ambiguousIds.toSet()
        ambiguousIds.forEach { id ->
            stored.remove(BoostNodeCodec.encode(BoostType.XP, 150, "miner", id))
        }

        val otherPlayer = server.addPlayer("BoostCopyQA")
        every { users.loadUser(otherPlayer.uniqueId, null) } returns CompletableFuture.completedFuture(user)
        every { users.loadUser(otherPlayer.uniqueId) } returns CompletableFuture.completedFuture(user)
        val transferablePayload = payload.copy(voucherId = UUID.randomUUID())
        val copiedRedemption = mutableListOf<GrantResult>()
        service.redeem(transferablePayload, otherPlayer, copiedRedemption::add)
        server.scheduler.performTicks(2)
        copiedRedemption shouldBe listOf(GrantResult.GRANTED)

        val stackedPayload = payload.copy(voucherId = UUID.randomUUID())
        val stackedRedemption = mutableListOf<GrantOutcome>()
        service.redeemDetailed(stackedPayload, otherPlayer, stackedRedemption::add)
        server.scheduler.performTicks(2)
        val stackedOutcome = stackedRedemption.single()
        stackedOutcome.result shouldBe GrantResult.GRANTED
        val stackedRemaining = requireNotNull(stackedOutcome.remaining)
        stackedRemaining shouldBeGreaterThan Duration.ofHours(1)
        stackedRemaining shouldBeLessThanOrEqualTo Duration.ofHours(2)
        stored.keys.mapNotNull(BoostNodeCodec::decode).map { it.instanceId }.toSet() shouldBe setOf(stackedPayload.voucherId)
        stored.keys.mapNotNull(BoostNodeCodec::decode).map { it.scope }.toSet() shouldBe setOf("miner", "fisherman")

        val beforeConflicts = stored.keys.toSet()
        val typeConflict = mutableListOf<GrantOutcome>()
        service.redeemDetailed(
            payload.copy(voucherId = UUID.randomUUID(), type = BoostType.MONEY),
            otherPlayer,
            typeConflict::add,
        )
        server.scheduler.performTicks(2)
        typeConflict.single().result shouldBe GrantResult.TYPE_CONFLICT
        stored.keys.toSet() shouldBe beforeConflicts

        val effectConflict = mutableListOf<GrantOutcome>()
        service.redeemDetailed(
            payload.copy(voucherId = UUID.randomUUID(), multiplierBasisPoints = 200),
            otherPlayer,
            effectConflict::add,
        )
        server.scheduler.performTicks(2)
        effectConflict.single().result shouldBe GrantResult.EFFECT_CONFLICT
        stored.keys.toSet() shouldBe beforeConflicts

        service.redeem(transferablePayload, player, redemption::add)
        server.scheduler.performTicks(2)
        redemption.last() shouldBe GrantResult.ALREADY_USED

        val legacyUsedPayload = payload.copy(
            voucherId = UUID.randomUUID(),
            signatureVersion = VoucherPayload.LEGACY_SIGNATURE_VERSION,
        )
        globallyUsed = true
        ledger.failNextCommit = true
        service.redeem(legacyUsedPayload, otherPlayer, copiedRedemption::add)
        server.scheduler.performTicks(2)
        copiedRedemption.last() shouldBe GrantResult.FAILED
        service.redeem(legacyUsedPayload, otherPlayer, copiedRedemption::add)
        server.scheduler.performTicks(2)
        copiedRedemption.last() shouldBe GrantResult.ALREADY_USED
        globallyUsed = false

        val uncertainLedgerPayload = payload.copy(voucherId = UUID.randomUUID())
        ledger.failNextCommit = true
        service.redeem(uncertainLedgerPayload, player, redemption::add)
        server.scheduler.performTicks(2)
        redemption.last() shouldBe GrantResult.FAILED
        service.redeem(uncertainLedgerPayload, player, redemption::add)
        server.scheduler.performTicks(2)
        redemption.last() shouldBe GrantResult.ALREADY_USED

        val failedSavePayload = payload.copy(voucherId = UUID.randomUUID())
        val beforeFailedSaveAttempt = stored.keys.toSet()
        every { users.saveUser(user) } returns CompletableFuture.failedFuture(IllegalStateException("storage unavailable"))
        service.redeem(failedSavePayload, player, redemption::add)
        server.scheduler.performTicks(2)
        redemption.last() shouldBe GrantResult.FAILED
        stored.keys.toSet() shouldBe beforeFailedSaveAttempt
        service.redeem(failedSavePayload, otherPlayer, copiedRedemption::add)
        server.scheduler.performTicks(2)
        copiedRedemption.last() shouldBe GrantResult.BUSY
        every { users.saveUser(user) } returns CompletableFuture.completedFuture(null)
        service.redeem(failedSavePayload, player, redemption::add)
        server.scheduler.performTicks(2)
        redemption.last() shouldBe GrantResult.GRANTED

        val partialVoucher = payload.copy(voucherId = UUID.randomUUID())
        val preexistingKey = BoostNodeCodec.encode(
            partialVoucher.type,
            partialVoucher.multiplierBasisPoints,
            "miner",
            partialVoucher.voucherId,
        )
        stored[preexistingKey] = nodeFactory.create(preexistingKey, Instant.now().plusSeconds(3_600))
        val beforeFailedSave = stored.keys.toSet()

        service.redeem(partialVoucher, player, redemption::add)
        server.scheduler.performTicks(2)
        redemption.last() shouldBe GrantResult.EFFECT_CONFLICT
        stored.keys.toSet() shouldBe beforeFailedSave

        every { users.saveUser(user) } returns CompletableFuture.failedFuture(IllegalStateException("storage unavailable"))
        var failedRevokeCount: Int? = 999
        var failedRevokeCause: Throwable? = null
        service.revoke(player.uniqueId, "all") { count, failure ->
            failedRevokeCount = count
            failedRevokeCause = failure
        }
        server.scheduler.performTicks(2)
        failedRevokeCount shouldBe null
        (failedRevokeCause is IllegalStateException) shouldBe true
        stored.keys.toSet() shouldBe beforeFailedSave
        verify(exactly = 8) { users.saveUser(user) }
    }
