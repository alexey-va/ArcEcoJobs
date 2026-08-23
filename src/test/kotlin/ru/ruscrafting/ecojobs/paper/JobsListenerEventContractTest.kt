package ru.ruscrafting.ecojobs.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.block.Action
import org.bukkit.block.BlockFace
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import ru.ruscrafting.ecojobs.boost.BoostService
import ru.ruscrafting.ecojobs.boost.GrantOutcome
import ru.ruscrafting.ecojobs.boost.GrantResult
import ru.ruscrafting.ecojobs.boost.VoucherInspection
import ru.ruscrafting.ecojobs.boost.VoucherService
import ru.ruscrafting.ecojobs.config.AddonSettings
import ru.ruscrafting.ecojobs.config.JobsLocale
import ru.ruscrafting.ecojobs.domain.BoostType
import ru.ruscrafting.ecojobs.domain.VoucherPayload
import java.time.Duration
import java.time.Instant
import java.util.UUID

class JobsListenerEventContractTest : StringSpec({
    beforeSpec { MockBukkit.mock() }
    afterSpec { MockBukkit.unmock() }

    fun listener(player: Player, vouchers: VoucherService): JobsListener {
        val locale = mockk<JobsLocale> {
            every { render(any(), player, any()) } returns Component.empty()
        }
        return JobsListener(
            settings = mockk<() -> AddonSettings>(),
            locale = locale,
            menu = mockk<JobsMenu>(),
            boosts = mockk<BoostService>(),
            vouchers = vouchers,
        )
    }

    "voucher clicks include Paper pre-cancelled air interactions" {
        val handler = JobsListener::class.java
            .getDeclaredMethod("onVoucherUse", PlayerInteractEvent::class.java)
            .getAnnotation(EventHandler::class.java)

        handler.ignoreCancelled shouldBe false
    }

    "legacy consumable and projectile vouchers have non-consuming fallbacks" {
        val consume = JobsListener::class.java
            .getDeclaredMethod("onVoucherConsume", PlayerItemConsumeEvent::class.java)
            .getAnnotation(EventHandler::class.java)
        val launch = JobsListener::class.java
            .getDeclaredMethod("onVoucherLaunch", PlayerLaunchProjectileEvent::class.java)
            .getAnnotation(EventHandler::class.java)

        consume.ignoreCancelled shouldBe false
        launch.ignoreCancelled shouldBe false
    }

    "voucher interaction denies vanilla use even when Paper pre-cancelled an air click" {
        val item = ItemStack(Material.EXPERIENCE_BOTTLE)
        val player = mockk<Player>(relaxed = true)
        val vouchers = mockk<VoucherService> {
            every { inspect(any()) } returns VoucherInspection.Invalid("test")
        }
        val listener = listener(player, vouchers)
        val event = PlayerInteractEvent(
            player,
            Action.RIGHT_CLICK_AIR,
            item,
            null,
            BlockFace.SELF,
            EquipmentSlot.HAND,
        )

        event.isCancelled shouldBe true
        listener.onVoucherUse(event)

        event.useInteractedBlock() shouldBe Event.Result.DENY
        event.useItemInHand() shouldBe Event.Result.DENY
        event.isCancelled shouldBe true
    }

    "legacy bottle fallbacks prevent both consumption and projectile loss" {
        val item = ItemStack(Material.EXPERIENCE_BOTTLE)
        val player = mockk<Player>(relaxed = true)
        val vouchers = mockk<VoucherService> {
            every { inspect(any()) } returns VoucherInspection.Invalid("test")
        }
        val listener = listener(player, vouchers)
        val consume = PlayerItemConsumeEvent(player, item, EquipmentSlot.HAND)
        val launch = PlayerLaunchProjectileEvent(player, item, mockk<Projectile>())

        listener.onVoucherConsume(consume)
        listener.onVoucherLaunch(launch)

        consume.isCancelled shouldBe true
        launch.isCancelled shouldBe true
        launch.shouldConsume() shouldBe false
    }

    "success message uses the total stacked duration returned by storage" {
        val item = ItemStack(Material.LAPIS_LAZULI)
        val player = mockk<Player>(relaxed = true) {
            every { hasPermission("arcecojobs.booster.use") } returns true
        }
        val payload = VoucherPayload(
            presetId = "focused-xp",
            voucherId = UUID.randomUUID(),
            type = BoostType.XP,
            multiplierBasisPoints = 200,
            durationSeconds = 1_800,
            jobs = setOf("all"),
            issuedAtEpochSecond = Instant.now().epochSecond,
        )
        val originalDuration = Component.text("30м")
        val totalDuration = Component.text("1ч")
        val vouchers = mockk<VoucherService> {
            every { inspect(any()) } returns VoucherInspection.Valid(payload)
            every { displayValues(payload, player) } returns mapOf("duration" to originalDuration)
            every { remove(player, payload.voucherId) } returns true
        }
        val locale = mockk<JobsLocale> {
            every { duration(Duration.ofHours(1), player) } returns "1ч"
            every { text("1ч") } returns totalDuration
            every { render(any(), player, any()) } returns Component.empty()
        }
        val boosts = mockk<BoostService> {
            every { redeemDetailed(payload, player, any()) } answers {
                arg<(GrantOutcome) -> Unit>(2).invoke(GrantOutcome(GrantResult.GRANTED, Duration.ofHours(1)))
            }
        }
        val listener = JobsListener(
            settings = mockk<() -> AddonSettings>(),
            locale = locale,
            menu = mockk<JobsMenu>(),
            boosts = boosts,
            vouchers = vouchers,
        )
        val event = PlayerInteractEvent(
            player,
            Action.RIGHT_CLICK_AIR,
            item,
            null,
            BlockFace.SELF,
            EquipmentSlot.HAND,
        )

        listener.onVoucherUse(event)

        verify { vouchers.remove(player, payload.voucherId) }
        verify {
            locale.render(
                "message.booster-redeemed",
                player,
                match { it["duration"] == totalDuration },
            )
        }
    }
})
