package ru.ruscrafting.ecojobs.paper

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent
import com.willfp.ecojobs.api.event.PlayerJobExpGainEvent
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.entity.Player
import ru.ruscrafting.ecojobs.boost.BoostService
import ru.ruscrafting.ecojobs.boost.GrantResult
import ru.ruscrafting.ecojobs.boost.VoucherInspection
import ru.ruscrafting.ecojobs.boost.VoucherService
import ru.ruscrafting.ecojobs.config.AddonSettings
import ru.ruscrafting.ecojobs.config.JobsLocale
import ru.ruscrafting.ecojobs.domain.BoostType
import ru.ruscrafting.ecojobs.domain.DurationParser
import ru.ruscrafting.ecojobs.domain.Multipliers
import java.time.Duration
import java.util.UUID

class JobsListener(
    private val settings: () -> AddonSettings,
    private val locale: JobsLocale,
    private val menu: JobsMenu,
    private val boosts: BoostService,
    private val vouchers: VoucherService,
) : Listener {
    private val pending = mutableMapOf<UUID, UUID>()

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player
        val voucherId = player?.let { pending[it.uniqueId] }
        if (player != null && voucherId != null && (
                vouchers.matchesVoucherId(event.currentItem, voucherId) ||
                    vouchers.matchesVoucherId(event.cursor, voucherId) ||
                    event.hotbarButton.takeIf { it >= 0 }
                        ?.let { vouchers.matchesVoucherId(player.inventory.getItem(it), voucherId) } == true ||
                    event.click == ClickType.SWAP_OFFHAND &&
                    vouchers.matchesVoucherId(player.inventory.itemInOffHand, voucherId)
                )
        ) {
            event.isCancelled = true
            return
        }
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player
        val voucherId = player?.let { pending[it.uniqueId] }
        if (voucherId != null && vouchers.matchesVoucherId(event.oldCursor, voucherId)) {
            event.isCancelled = true
            return
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onVoucherDrop(event: PlayerDropItemEvent) {
        val voucherId = pending[event.player.uniqueId] ?: return
        if (vouchers.matchesVoucherId(event.itemDrop.itemStack, voucherId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onVoucherSwap(event: PlayerSwapHandItemsEvent) {
        val voucherId = pending[event.player.uniqueId] ?: return
        if (
            vouchers.matchesVoucherId(event.mainHandItem, voucherId) ||
            vouchers.matchesVoucherId(event.offHandItem, voucherId)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onVoucherDeath(event: PlayerDeathEvent) {
        val voucherId = pending[event.player.uniqueId] ?: return
        val protected = event.drops.filter { vouchers.matchesVoucherId(it, voucherId) }
        if (protected.isEmpty()) return
        event.drops.removeAll(protected.toSet())
        event.itemsToKeep.addAll(protected)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onRootJobsCommand(event: PlayerCommandPreprocessEvent) {
        if (!settings().interceptEcoJobsRoot) return
        val command = event.message.trim().lowercase()
        if (command != "/jobs" && command != "/job") return
        event.isCancelled = true
        if (event.player.hasPermission("arcecojobs.use")) menu.openRoot(event.player)
        else event.player.sendMessage(locale.render("message.no-permission", event.player))
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onVoucherUse(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val inspection = vouchers.inspect(event.item)
        if (inspection == VoucherInspection.NotVoucher) return
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        event.isCancelled = true
        activateVoucher(event.player, inspection)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onVoucherConsume(event: PlayerItemConsumeEvent) {
        val inspection = vouchers.inspect(event.item)
        if (inspection == VoucherInspection.NotVoucher) return
        event.isCancelled = true
        activateVoucher(event.player, inspection)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onVoucherLaunch(event: PlayerLaunchProjectileEvent) {
        val inspection = vouchers.inspect(event.itemStack)
        if (inspection == VoucherInspection.NotVoucher) return
        event.isCancelled = true
        event.setShouldConsume(false)
        activateVoucher(event.player, inspection)
    }

    private fun activateVoucher(player: Player, inspection: VoucherInspection) {
        if (!player.hasPermission("arcecojobs.booster.use")) {
            player.sendMessage(locale.render("message.no-permission", player))
            return
        }
        when (inspection) {
            VoucherInspection.NotVoucher -> return
            is VoucherInspection.Invalid -> {
                player.sendMessage(locale.render("message.booster-invalid", player))
                return
            }
            is VoucherInspection.Valid -> Unit
        }
        val payload = inspection.payload
        if (pending.putIfAbsent(player.uniqueId, payload.voucherId) != null) {
            player.sendMessage(locale.render("message.booster-busy", player))
            return
        }
        boosts.redeemDetailed(payload, player) { outcome ->
            pending.remove(player.uniqueId, payload.voucherId)
            when (outcome.result) {
                GrantResult.GRANTED -> {
                    vouchers.remove(player, payload.voucherId)
                    val totalRemaining = requireNotNull(outcome.remaining) { "Granted voucher has no remaining duration" }
                    val values = vouchers.displayValues(payload, player) +
                        ("duration" to locale.text(locale.duration(totalRemaining, player)))
                    player.sendMessage(locale.render("message.booster-redeemed", player, values))
                }
                GrantResult.ALREADY_USED -> {
                    vouchers.remove(player, payload.voucherId)
                    player.sendMessage(locale.render("message.booster-already-used", player))
                }
                GrantResult.BUSY -> player.sendMessage(locale.render("message.booster-busy", player))
                GrantResult.TYPE_CONFLICT -> player.sendMessage(locale.render(
                    "message.booster-type-conflict",
                    player,
                    remainingValues(outcome.remaining, player),
                ))
                GrantResult.EFFECT_CONFLICT -> player.sendMessage(locale.render(
                    "message.booster-effect-conflict",
                    player,
                    remainingValues(outcome.remaining, player),
                ))
                GrantResult.STACK_LIMIT -> player.sendMessage(locale.render("message.booster-stack-limit", player))
                GrantResult.FAILED -> player.sendMessage(locale.render("message.booster-save-failed", player))
            }
        }
    }

    private fun remainingValues(remaining: Duration?, player: Player) = mapOf(
        "duration" to locale.text(locale.duration(requireNotNull(remaining), player)),
    )

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onJobXp(event: PlayerJobExpGainEvent) {
        val multiplier = boosts.multiplier(event.player, event.job.id, BoostType.XP)
        if (multiplier > 1.0) event.amount *= multiplier
    }
}
