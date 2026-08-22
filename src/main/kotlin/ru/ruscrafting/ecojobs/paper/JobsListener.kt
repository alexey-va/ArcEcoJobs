package ru.ruscrafting.ecojobs.paper

import com.willfp.ecojobs.api.event.PlayerJobExpGainEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerInteractEvent
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
    private val pending = mutableSetOf<UUID>()

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) = menu.onClick(event)

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) = menu.onDrag(event)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onRootJobsCommand(event: PlayerCommandPreprocessEvent) {
        if (!settings().interceptEcoJobsRoot) return
        val command = event.message.trim().lowercase()
        if (command != "/jobs" && command != "/job") return
        event.isCancelled = true
        if (event.player.hasPermission("arcecojobs.use")) menu.open(event.player)
        else event.player.sendMessage(locale.render("message.no-permission", event.player))
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVoucherUse(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val inspection = vouchers.inspect(event.item)
        if (inspection == VoucherInspection.NotVoucher) return
        event.isCancelled = true
        val player = event.player
        if (!player.hasPermission("arcecojobs.booster.use")) {
            player.sendMessage(locale.render("message.no-permission", player))
            return
        }
        when (inspection) {
            VoucherInspection.NotVoucher -> return
            VoucherInspection.Legacy -> {
                player.sendMessage(locale.render("message.booster-legacy", player))
                return
            }
            is VoucherInspection.Invalid -> {
                player.sendMessage(locale.render("message.booster-invalid", player))
                return
            }
            is VoucherInspection.Valid -> Unit
        }
        val payload = inspection.payload
        if (payload.recipientId != player.uniqueId) {
            player.sendMessage(locale.render("message.booster-wrong-owner", player))
            return
        }
        if (!pending.add(player.uniqueId)) {
            player.sendMessage(locale.render("message.booster-busy", player))
            return
        }
        boosts.redeem(payload, player) { result ->
            pending.remove(player.uniqueId)
            when (result) {
                GrantResult.GRANTED -> {
                    vouchers.remove(player, payload.voucherId)
                    player.sendMessage(locale.render("message.booster-redeemed", player, vouchers.displayValues(payload, player)))
                }
                GrantResult.ALREADY_USED -> {
                    vouchers.remove(player, payload.voucherId)
                    player.sendMessage(locale.render("message.booster-already-used", player))
                }
                GrantResult.WRONG_OWNER -> player.sendMessage(locale.render("message.booster-wrong-owner", player))
                GrantResult.FAILED -> player.sendMessage(locale.render("message.booster-save-failed", player))
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onJobXp(event: PlayerJobExpGainEvent) {
        val multiplier = boosts.multiplier(event.player, event.job.id, BoostType.XP)
        if (multiplier > 1.0) event.amount *= multiplier
    }
}
