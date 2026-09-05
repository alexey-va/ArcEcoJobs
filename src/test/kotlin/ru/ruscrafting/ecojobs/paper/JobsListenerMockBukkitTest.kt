package ru.ruscrafting.ecojobs.paper

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent
import com.willfp.ecojobs.api.event.PlayerJobExpGainEvent
import com.willfp.ecojobs.jobs.Job
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Bukkit
import org.bukkit.block.BlockFace
import org.bukkit.command.CommandSender
import org.bukkit.entity.Projectile
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ecojobs.boost.BoostService
import ru.ruscrafting.ecojobs.boost.GrantOutcome
import ru.ruscrafting.ecojobs.boost.GrantResult
import ru.ruscrafting.ecojobs.boost.VoucherInspection
import ru.ruscrafting.ecojobs.boost.VoucherService
import ru.ruscrafting.ecojobs.config.AddonSettings
import ru.ruscrafting.ecojobs.config.JobsLocale
import ru.ruscrafting.ecojobs.domain.BoostType
import ru.ruscrafting.ecojobs.domain.VoucherPayload
import ru.ruscrafting.ecojobs.domain.VoucherSigner
import ru.ruscrafting.ecojobs.earnings.EarningsService
import java.time.Duration
import java.time.Instant
import java.util.UUID

class JobsListenerMockBukkitTest : StringSpec({
    "XP boost and earnings observation execute in Bukkit priority order and ignore cancelled gains" {
        MockBukkitTestRuntime.open().use { paper ->
            requireSupportedMockBukkit {
                val plugin = paper.createSimplePlugin("ArcEcoJobsXpListenerTest")
                val player = paper.addPlayer("XpQA")
                val job = mockk<Job> { every { id } returns "miner" }
                val boosts = mockk<BoostService> {
                    every { multiplier(player, "miner", BoostType.XP) } returns 1.5
                }
                val earnings = mockk<EarningsService>(relaxed = true)
                plugin.server.pluginManager.registerEvents(
                    JobsListener(
                        settings = { mockk<AddonSettings>() },
                        locale = mockk<JobsLocale>(),
                        menu = mockk<JobsMenu>(),
                        boosts = boosts,
                        vouchers = mockk<VoucherService>(),
                    ),
                    plugin,
                )
                plugin.server.pluginManager.registerEvents(EarningsListener(earnings), plugin)

                val accepted = PlayerJobExpGainEvent(player, job, 12.5, false)
                paper.callEvent(accepted)

                accepted.amount shouldBe 18.75
                verify(exactly = 1) { earnings.recordXp(player.uniqueId, "miner", 18.75) }

                val cancelled = PlayerJobExpGainEvent(player, job, 20.0, false).apply { isCancelled = true }
                paper.callEvent(cancelled)

                cancelled.amount shouldBe 20.0
                verify(exactly = 1) { boosts.multiplier(player, "miner", BoostType.XP) }
                verify(exactly = 1) { earnings.recordXp(any(), any(), any()) }
            }
        }
    }

    "root EcoJobs command interception handles aliases, permissions and arguments through the event bus" {
        MockBukkitTestRuntime.open().use { paper ->
            requireSupportedMockBukkit {
                val plugin = paper.createSimplePlugin("ArcEcoJobsCommandListenerTest")
                val allowed = paper.addPlayer("AllowedQA")
                val denied = paper.addPlayer("DeniedQA")
                allowed.addAttachment(plugin, "arcecojobs.use", true)
                val settings = mockk<AddonSettings> {
                    every { interceptEcoJobsRoot } returns true
                }
                val locale = mockk<JobsLocale> {
                    every { render(any(), any<CommandSender>(), any()) } answers {
                        Component.text(firstArg<String>())
                    }
                }
                val menu = mockk<JobsMenu>(relaxed = true)
                val listener = JobsListener(
                    settings = { settings },
                    locale = locale,
                    menu = menu,
                    boosts = mockk<BoostService>(),
                    vouchers = mockk<VoucherService>(),
                )
                plugin.server.pluginManager.registerEvents(listener, plugin)

                paper.callEvent(PlayerCommandPreprocessEvent(allowed, "  /JoBs  ")).isCancelled shouldBe true
                paper.callEvent(PlayerCommandPreprocessEvent(allowed, "/job")).isCancelled shouldBe true
                verify(exactly = 2) { menu.openRoot(allowed) }

                paper.callEvent(PlayerCommandPreprocessEvent(allowed, "/jobs list")).isCancelled shouldBe false
                paper.callEvent(PlayerCommandPreprocessEvent(allowed, "/minecraft:jobs")).isCancelled shouldBe false
                verify(exactly = 2) { menu.openRoot(allowed) }

                paper.callEvent(PlayerCommandPreprocessEvent(denied, "/jobs")).isCancelled shouldBe true
                verify(exactly = 0) { menu.openRoot(denied) }
                PlainTextComponentSerializer.plainText().serialize(requireNotNull(denied.nextComponentMessage())) shouldBe
                    "message.no-permission"
            }
        }
    }

    "voucher activation denies vanilla use and serializes repeated clicks until completion" {
        MockBukkitTestRuntime.open().use { paper ->
            requireSupportedMockBukkit {
                val plugin = paper.createSimplePlugin("ArcEcoJobsVoucherListenerTest")
                val player = paper.addPlayer("VoucherQA")
                player.addAttachment(plugin, "arcecojobs.booster.use", true)
                val payload = VoucherPayload(
                    presetId = "workday",
                    voucherId = UUID.randomUUID(),
                    type = BoostType.XP,
                    multiplierBasisPoints = 150,
                    durationSeconds = 3_600,
                    jobs = setOf("miner"),
                    issuedAtEpochSecond = Instant.now().epochSecond,
                )
                val signingKey = ByteArray(32) { it.toByte() }
                val item = signedVoucher(plugin, payload, signingKey)
                player.inventory.setItemInMainHand(item)
                val callbacks = mutableListOf<(GrantOutcome) -> Unit>()
                val boosts = mockk<BoostService> {
                    every { redeemDetailed(payload, player, any()) } answers {
                        callbacks += thirdArg<(GrantOutcome) -> Unit>()
                    }
                }
                val locale = mockk<JobsLocale> {
                    every { duration(any(), player) } returns "1ч"
                    every { text(any()) } answers { Component.text(firstArg<Any?>()?.toString().orEmpty()) }
                    every { type(any(), player) } returns Component.text("опыт")
                    every { render(any(), player, any()) } returns Component.empty()
                }
                val settings = mockk<AddonSettings> {
                    every { minimumMultiplierBasisPoints } returns 101
                    every { maximumMultiplierBasisPoints } returns 1_000
                    every { maximumBoostDuration } returns Duration.ofDays(30)
                }
                val vouchers = VoucherService(
                    plugin = plugin,
                    locale = locale,
                    settings = { settings },
                    validJobIds = { setOf("miner") },
                    jobNames = { Component.text("Шахтёр") },
                    signingKey = signingKey,
                )
                val listener = JobsListener(
                    settings = { mockk<AddonSettings>() },
                    locale = locale,
                    menu = mockk<JobsMenu>(),
                    boosts = boosts,
                    vouchers = vouchers,
                )
                plugin.server.pluginManager.registerEvents(listener, plugin)

                val first = voucherClick(player, item)
                paper.callEvent(first)
                first.useInteractedBlock() shouldBe Event.Result.DENY
                first.useItemInHand() shouldBe Event.Result.DENY
                callbacks.size shouldBe 1

                paper.callEvent(voucherClick(player, item))
                callbacks.size shouldBe 1
                verify(exactly = 1) { boosts.redeemDetailed(payload, player, any()) }

                player.openInventory(Bukkit.createInventory(null, 9, Component.text("Voucher movement")))
                player.setItemOnCursor(item.clone())
                val inventoryClick = InventoryClickEvent(
                    player.openInventory,
                    InventoryType.SlotType.CONTAINER,
                    0,
                    ClickType.LEFT,
                    InventoryAction.PLACE_ALL,
                )
                paper.callEvent(inventoryClick).isCancelled shouldBe true
                player.setItemOnCursor(ItemStack(Material.AIR))

                val drag = InventoryDragEvent(
                    player.openInventory,
                    ItemStack(Material.AIR),
                    item.clone(),
                    false,
                    mapOf(0 to item.clone()),
                )
                paper.callEvent(drag).isCancelled shouldBe true

                val dropped = player.world.dropItem(player.location, item.clone())
                paper.callEvent(PlayerDropItemEvent(player, dropped)).isCancelled shouldBe true
                paper.callEvent(PlayerSwapHandItemsEvent(player, item.clone(), ItemStack(Material.AIR))).isCancelled shouldBe true

                val drops = mutableListOf(item.clone())
                val kept = mutableListOf<ItemStack>()
                val death = mockk<PlayerDeathEvent> {
                    every { getPlayer() } returns player
                    every { getDrops() } returns drops
                    every { getItemsToKeep() } returns kept
                }
                listener.onVoucherDeath(death)
                drops shouldBe emptyList()
                kept shouldBe listOf(item)

                callbacks.single().invoke(GrantOutcome(GrantResult.GRANTED, Duration.ofHours(1)))
                player.inventory.contents.none { vouchers.inspect(it) is VoucherInspection.Valid } shouldBe true

                player.inventory.setItemInMainHand(item.clone())
                paper.callEvent(voucherClick(player, item))
                callbacks.size shouldBe 2
                callbacks.last().invoke(GrantOutcome(GrantResult.FAILED))

                paper.callEvent(voucherClick(player, item))
                callbacks.size shouldBe 3
                verify(exactly = 3) { boosts.redeemDetailed(payload, player, any()) }
            }
        }
    }

    "legacy consume and projectile routes cancel item loss without redeeming an invalid voucher" {
        MockBukkitTestRuntime.open().use { paper ->
            requireSupportedMockBukkit {
                val plugin = paper.createSimplePlugin("ArcEcoJobsLegacyVoucherListenerTest")
                val player = paper.addPlayer("LegacyVoucherQA")
                player.addAttachment(plugin, "arcecojobs.booster.use", true)
                val item = ItemStack(Material.EXPERIENCE_BOTTLE)
                val boosts = mockk<BoostService>(relaxed = true)
                val vouchers = mockk<VoucherService> {
                    every { inspect(any()) } returns VoucherInspection.Invalid("bad-signature")
                }
                val locale = mockk<JobsLocale> {
                    every { render(any(), player, any()) } returns Component.empty()
                }
                plugin.server.pluginManager.registerEvents(
                    JobsListener(
                        settings = { mockk<AddonSettings>() },
                        locale = locale,
                        menu = mockk<JobsMenu>(),
                        boosts = boosts,
                        vouchers = vouchers,
                    ),
                    plugin,
                )
                val consume = PlayerItemConsumeEvent(player, item, EquipmentSlot.HAND)
                val launch = PlayerLaunchProjectileEvent(player, item, mockk<Projectile>())

                paper.callEvent(consume)
                paper.callEvent(launch)

                consume.isCancelled shouldBe true
                launch.isCancelled shouldBe true
                launch.shouldConsume() shouldBe false
                verify(exactly = 0) { boosts.redeemDetailed(any(), any(), any()) }
            }
        }
    }
})

private fun voucherClick(player: org.bukkit.entity.Player, item: ItemStack) = PlayerInteractEvent(
    player,
    Action.RIGHT_CLICK_AIR,
    item,
    null,
    BlockFace.SELF,
    EquipmentSlot.HAND,
)

private fun signedVoucher(plugin: JavaPlugin, payload: VoucherPayload, signingKey: ByteArray): ItemStack =
    ItemStack(Material.EXPERIENCE_BOTTLE).apply {
        editMeta { meta ->
            val data = meta.persistentDataContainer
            data.set(NamespacedKey(plugin, "voucher_version"), PersistentDataType.STRING, payload.signatureVersion)
            data.set(NamespacedKey(plugin, "voucher_preset"), PersistentDataType.STRING, payload.presetId)
            data.set(NamespacedKey(plugin, "voucher_id"), PersistentDataType.STRING, payload.voucherId.toString())
            data.set(NamespacedKey(plugin, "voucher_type"), PersistentDataType.STRING, payload.type.token)
            data.set(NamespacedKey(plugin, "voucher_multiplier_bp"), PersistentDataType.INTEGER, payload.multiplierBasisPoints)
            data.set(NamespacedKey(plugin, "voucher_duration_seconds"), PersistentDataType.LONG, payload.durationSeconds)
            data.set(NamespacedKey(plugin, "voucher_jobs"), PersistentDataType.STRING, payload.jobs.sorted().joinToString(","))
            data.set(NamespacedKey(plugin, "voucher_issued_at"), PersistentDataType.LONG, payload.issuedAtEpochSecond)
            data.set(
                NamespacedKey(plugin, "voucher_signature"),
                PersistentDataType.BYTE_ARRAY,
                VoucherSigner(signingKey).sign(payload),
            )
        }
    }
