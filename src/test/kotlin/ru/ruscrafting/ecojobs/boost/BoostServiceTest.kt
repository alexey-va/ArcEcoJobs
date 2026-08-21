package ru.ruscrafting.ecojobs.boost

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
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
import org.mockbukkit.mockbukkit.MockBukkit
import ru.ruscrafting.ecojobs.config.AddonSettings
import ru.ruscrafting.ecojobs.domain.BoostNodeCodec
import ru.ruscrafting.ecojobs.domain.BoostType
import ru.ruscrafting.ecojobs.domain.VoucherPayload
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

class BoostServiceTest : StringSpec({
    beforeSpec { MockBukkit.mock() }
    afterSpec { MockBukkit.unmock() }

    "redeem is replay-safe and revoke counts one multi-scope boost instance" {
        val server = MockBukkit.getMock()!!
        val plugin = MockBukkit.createMockPlugin("ArcEcoJobsBoostTest")
        val player = server.addPlayer("BoostQA")
        val stored = linkedSetOf<Node>()
        val data = mockk<NodeMap>()
        every { data.add(any()) } answers {
            if (stored.add(firstArg())) DataMutateResult.SUCCESS else DataMutateResult.FAIL_ALREADY_HAS
        }
        every { data.remove(any()) } answers {
            if (stored.remove(firstArg())) DataMutateResult.SUCCESS else DataMutateResult.FAIL_LACKS
        }
        val user = mockk<User>()
        every { user.data() } returns data
        every { user.getNodes(NodeType.PERMISSION) } answers {
            stored.filterIsInstance<PermissionNode>().toSet()
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
            requireMoneyPlaceholder = true,
            fillerMaterial = Material.BLACK_STAINED_GLASS_PANE,
        )
        val nodeFactory = BoostNodeFactory { permissionKey, expiry ->
            mockk<PermissionNode> {
                every { key } returns permissionKey
                every { permission } returns permissionKey
                every { value } returns true
                every { hasExpiry() } returns (expiry != null)
                every { hasExpired() } returns false
                every { getExpiry() } returns expiry
            }
        }
        val service = BoostService(plugin, luckPerms, { settings }, nodeFactory)
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
        stored.size shouldBe 3
        plugin.isEnabled shouldBe true
        server.scheduler.performTicks(2)

        redemption shouldBe listOf(GrantResult.GRANTED)
        stored.map { it.key } shouldContain BoostNodeCodec.used(voucherId)
        stored.mapNotNull { BoostNodeCodec.decode(it.key) }.map { it.scope }.toSet() shouldBe setOf("miner", "fisherman")

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
        stored.map { it.key } shouldBe listOf(BoostNodeCodec.used(voucherId))

        service.redeem(payload.copy(voucherId = UUID.randomUUID(), jobs = setOf("invalid.scope")), player, redemption::add)
        server.scheduler.performTicks(2)
        redemption.last() shouldBe GrantResult.FAILED
        stored.map { it.key } shouldBe listOf(BoostNodeCodec.used(voucherId))
        verify(exactly = 2) { users.saveUser(user) }
    }
})
