package ru.ruscrafting.ecojobs.paper

import com.willfp.ecojobs.jobs.Job
import net.luckperms.api.LuckPerms
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.BukkitTaskScheduler
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.paper.menu.PaperMenuFrame
import ru.arc.paper.menu.PaperMenuRuntime
import ru.ruscrafting.ecojobs.boost.BoostService
import ru.ruscrafting.ecojobs.boost.VoucherService
import ru.ruscrafting.ecojobs.config.AddonSettings
import ru.ruscrafting.ecojobs.config.BoosterRegistry
import ru.ruscrafting.ecojobs.config.GuiItemDefinition
import ru.ruscrafting.ecojobs.config.JobsLocale
import ru.ruscrafting.ecojobs.config.JobsMenuPresentation
import ru.ruscrafting.ecojobs.domain.BoostInstance
import ru.ruscrafting.ecojobs.domain.BoostType
import ru.ruscrafting.ecojobs.domain.Multipliers
import ru.ruscrafting.ecojobs.earnings.EarningsReport
import ru.ruscrafting.ecojobs.earnings.EarningsService
import ru.ruscrafting.ecojobs.earnings.EarningsTotals
import ru.ruscrafting.ecojobs.integration.EcoJobsBridge
import java.text.DecimalFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.ceil

sealed interface JobsView {
    data object Main : JobsView
    data class Catalog(val activeOnly: Boolean, val page: Int, val back: JobsView) : JobsView
    data class JobCard(val jobId: String, val back: JobsView) : JobsView
    data class LeaveConfirm(val jobId: String, val back: JobsView) : JobsView
    data class Levels(val jobId: String, val page: Int, val back: JobsView) : JobsView
    data class Earnings(val jobId: String, val page: Int, val back: JobsView) : JobsView
    data class EarningsHours(val jobId: String, val date: LocalDate, val back: JobsView) : JobsView
    data class LeaderboardSelector(val page: Int, val back: JobsView) : JobsView
    data class Leaderboard(val jobId: String?, val page: Int, val back: JobsView) : JobsView
    data class Boosts(val jobId: String?, val page: Int, val back: JobsView) : JobsView
    data class Help(val back: JobsView) : JobsView
    data class Admin(val back: JobsView) : JobsView
    data class Presets(val page: Int, val back: JobsView) : JobsView
}

class JobsMenu(
    private val plugin: JavaPlugin,
    private val settings: () -> AddonSettings,
    private val locale: JobsLocale,
    private val ecoJobs: EcoJobsBridge,
    private val boosts: BoostService,
    private val boosters: () -> BoosterRegistry,
    private val vouchers: VoucherService,
    private val earnings: () -> EarningsService?,
    private val reload: () -> Result<Unit>,
    private val layouts: JobsMenuLayouts,
    dialogDisplay: JobsDialogDisplay? = null,
    private val escapeGoesBack: (Player) -> Boolean = { player ->
        plugin.server.servicesManager.load(LuckPerms::class.java)?.userManager
            ?.getUser(player.uniqueId)?.cachedData?.metaData?.getMetaValue("arc-menu-escape") == "back"
    },
) : AutoCloseable, Listener {
    internal companion object {
        const val MAIN_MENU_COMMAND = "menu"
    }

    private val number = DecimalFormat("#,##0.##")
    private val plain = PlainTextComponentSerializer.plainText()
    private val pendingClicks = mutableSetOf<java.util.UUID>()
    private val menuRuntimeDelegate = lazy { PaperMenuRuntime(plugin, BukkitTaskScheduler(plugin), layouts.current()) }
    private val menuRuntime by menuRuntimeDelegate
    private val activeFrames = mutableMapOf<java.util.UUID, ActiveFrame>()

    private val dialogDisplayDelegate = lazy { dialogDisplay ?: NativeJobsDialogDisplay(plugin) }
    private val dialogs by dialogDisplayDelegate
    private val presentations = mutableMapOf<java.util.UUID, JobsMenuPresentation>()
    private var listening = false

    private class ActiveFrame(val view: JobsView, val frame: PaperMenuFrame, var detailSlot: Int? = null, var revision: Long = 0)

    fun openRoot(player: Player, presentation: JobsMenuPresentation? = null) {
        val next = presentation ?: settings().menuPresentation
        dismiss(player, closeDialog = next != JobsMenuPresentation.DIALOG)
        presentations[player.uniqueId] = next
        open(player)
    }

    private fun usesDialog(player: Player) = presentations[player.uniqueId] == JobsMenuPresentation.DIALOG

    private fun dismiss(player: Player, closeDialog: Boolean = true) {
        activeFrames.remove(player.uniqueId)
        if (presentations.remove(player.uniqueId) == JobsMenuPresentation.DIALOG && closeDialog) dialogs.close(player)
        if (menuRuntimeDelegate.isInitialized()) menuRuntime.session(player)?.close()
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        activeFrames.remove(event.player.uniqueId)
        presentations.remove(event.player.uniqueId)
        pendingClicks.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        // Dialog transitions close the old inventory before publishing their frame.
        if (presentations[event.player.uniqueId] == JobsMenuPresentation.INVENTORY) {
            activeFrames.remove(event.player.uniqueId)
            presentations.remove(event.player.uniqueId)
        }
    }

    fun open(player: Player, view: JobsView = JobsView.Main) {
        if (!listening) {
            plugin.server.pluginManager.registerEvents(this, plugin)
            listening = true
        }
        presentations.putIfAbsent(player.uniqueId, settings().menuPresentation)
        when (view) {
            JobsView.Main -> openMain(player)
            is JobsView.Catalog -> openCatalog(player, view)
            is JobsView.JobCard -> openJobCard(player, view)
            is JobsView.LeaveConfirm -> openLeave(player, view)
            is JobsView.Levels -> openLevels(player, view)
            is JobsView.Earnings -> openEarnings(player, view)
            is JobsView.EarningsHours -> openEarningsHours(player, view)
            is JobsView.LeaderboardSelector -> openLeaderboardSelector(player, view)
            is JobsView.Leaderboard -> openLeaderboard(player, view)
            is JobsView.Boosts -> openBoosts(player, view)
            is JobsView.Help -> openHelp(player, view)
            is JobsView.Admin -> openAdmin(player, view)
            is JobsView.Presets -> openPresets(player, view)
        }
    }

    private fun dispatchClick(player: Player, view: JobsView, slot: Int) {
        val expected = activeFrames[player.uniqueId] ?: return
        if (!pendingClicks.add(player.uniqueId)) return
        plugin.server.scheduler.runTask(plugin, Runnable {
            try {
                if (!player.isOnline || activeFrames[player.uniqueId] !== expected) return@Runnable
                if (!usesDialog(player) && menuRuntime.session(player) == null) return@Runnable
                if (!player.hasPermission("arcecojobs.use")) {
                    dismiss(player)
                    player.sendMessage(locale.render("message.no-permission", player))
                    return@Runnable
                }
                when (view) {
                    JobsView.Main -> clickMain(player, slot)
                    is JobsView.Catalog -> clickCatalog(player, view, slot)
                    is JobsView.JobCard -> clickJobCard(player, view, slot)
                    is JobsView.LeaveConfirm -> clickLeave(player, view, slot)
                    is JobsView.Levels -> clickPaged(player, view, slot, pageCount(ecoJobs.job(view.jobId)?.maxLevel ?: 0, content(view, player).size))
                    is JobsView.Earnings -> clickEarnings(player, view, slot)
                    is JobsView.EarningsHours -> if (slot == element(view, "back")) open(player, view.back)
                    is JobsView.LeaderboardSelector -> clickLeaderboardSelector(player, view, slot)
                    is JobsView.Leaderboard -> clickPaged(
                        player,
                        view,
                        slot,
                        pageCount(ecoJobs.rankings(view.jobId?.let(ecoJobs::job))?.size ?: 0, leaderboardPageSize(player)),
                    )
                    is JobsView.Boosts -> clickPaged(player, view, slot, pageCount(applicableBoosts(player, view.jobId).size, content(view, player).size))
                    is JobsView.Help -> if (slot == element(view, "back")) open(player, view.back)
                    is JobsView.Admin -> clickAdmin(player, view, slot)
                    is JobsView.Presets -> clickPresets(player, view, slot)
                }
            } finally {
                pendingClicks.remove(player.uniqueId)
                // Core consumes the whole registration, including rejected/no-op actions.
                if (usesDialog(player) && activeFrames[player.uniqueId] === expected) showDialog(player, expected)
            }
        })
    }

    fun replaceMenus(candidate: PaperMenuConfiguration) {
        layouts.replace(candidate)
        activeFrames.keys.toList().mapNotNull(Bukkit::getPlayer).forEach(::dismiss)
        if (menuRuntimeDelegate.isInitialized()) menuRuntime.replace(candidate)
    }

    override fun close() {
        activeFrames.keys.toList().mapNotNull(Bukkit::getPlayer).forEach(::dismiss)
        activeFrames.clear()
        presentations.clear()
        pendingClicks.clear()
        if (menuRuntimeDelegate.isInitialized()) menuRuntime.close()
        if (dialogDisplayDelegate.isInitialized()) dialogs.close()
        if (listening) HandlerList.unregisterAll(this)
    }

    private fun openMain(player: Player) {
        val view = JobsView.Main
        val inventory = inventory(player, view, "menu.main.title")
        inventory.setItem(element(view, "profile"), playerHead(player, "menu.main.profile-name", "menu.main.profile-lore", mapOf(
            "player" to text(player.name),
            "active" to text(ecoJobs.activeJobs(player).size),
            "limit" to text(ecoJobs.limit(player)),
            "level" to text(ecoJobs.totalLevel(player)),
        )))
        inventory.setItem(element(view, "catalog"), item(settings().guiItems.catalog, player, "menu.main.catalog-name", "menu.main.catalog-lore"))
        inventory.setItem(element(view, "active"), item(settings().guiItems["main-active"], player, "menu.main.active-name", "menu.main.active-lore", mapOf(
            "active" to text(ecoJobs.activeJobs(player).size),
        )))
        inventory.setItem(element(view, "leaderboard"), item(settings().guiItems["main-leaderboard"], player, "menu.main.leaderboard-name", "menu.main.leaderboard-lore"))
        inventory.setItem(element(view, "boosts"), item(settings().guiItems["main-boosts"], player, "menu.main.boosts-name", "menu.main.boosts-lore", mapOf(
            "count" to text(boosts.active(player).size),
        )))
        inventory.setItem(element(view, "help"), item(settings().guiItems["main-help"], player, "menu.main.help-name", "menu.main.help-lore"))
        inventory.setItem(element(view, "back"), backItem(player))
        if (player.hasPermission("arcecojobs.admin")) {
            inventory.setItem(element(view, "admin"), item(settings().guiItems["main-admin"], player, "menu.main.admin-name", "menu.main.admin-lore"))
        }
        show(player, view, inventory)
    }

    internal fun clickMain(player: Player, slot: Int) {
        val view = JobsView.Main
        when (slot) {
            element(view, "catalog") -> open(player, JobsView.Catalog(false, 1, view))
            element(view, "active") -> open(player, JobsView.Catalog(true, 1, view))
            element(view, "leaderboard") -> open(player, JobsView.LeaderboardSelector(1, view))
            element(view, "boosts") -> open(player, JobsView.Boosts(null, 1, view))
            element(view, "help") -> open(player, JobsView.Help(view))
            element(view, "back") -> {
                // The ARC root replaces the current dialog directly; closing first resets the mouse.
                dismiss(player, closeDialog = !usesDialog(player))
                player.performCommand(MAIN_MENU_COMMAND)
            }
            element(view, "admin") -> if (player.hasPermission("arcecojobs.admin")) open(player, JobsView.Admin(view))
        }
    }

    private fun openCatalog(player: Player, view: JobsView.Catalog) {
        val jobs = ecoJobs.jobs().filter { !view.activeOnly || ecoJobs.active(player, it) }
        val content = content(view, player)
        val pages = pageCount(jobs.size, content.size)
        val current = view.copy(page = view.page.coerceIn(1, pages))
        val inventory = inventory(player, current, if (view.activeOnly) "menu.catalog.title-active" else "menu.catalog.title")
        jobs.page(current.page, content.size).forEachIndexed { index, job ->
            val state = when {
                ecoJobs.active(player, job) -> locale.render("menu.catalog.state-active", player)
                ecoJobs.has(player, job) -> locale.render("menu.catalog.state-available", player)
                else -> locale.render("menu.catalog.state-locked", player)
            }
            inventory.setItem(content[index], jobItem(job, player, "menu.catalog.job-lore", mapOf(
                "description" to ecoJobs.description(job),
                "level" to text(ecoJobs.level(player, job)),
                "max" to text(job.maxLevel),
                "state" to state,
                "workers" to text(ecoJobs.onlineWorkers(job)),
            )))
        }
        if (jobs.isEmpty()) inventory.setItem(element(current, "empty"), item(settings().guiItems["empty"], player, "menu.catalog.empty-name", "menu.catalog.empty-lore"))
        navigation(inventory, player, current, current.page, pages)
        show(player, current, inventory)
    }

    private fun clickCatalog(player: Player, view: JobsView.Catalog, slot: Int) {
        val jobs = ecoJobs.jobs().filter { !view.activeOnly || ecoJobs.active(player, it) }
        val content = content(view, player)
        val index = content.indexOf(slot)
        if (index >= 0) jobs.getOrNull((view.page - 1) * content.size + index)?.let {
            open(player, JobsView.JobCard(it.id, view))
            return
        }
        clickPaged(player, view, slot, pageCount(jobs.size, content.size))
    }

    private fun openJobCard(player: Player, view: JobsView.JobCard) {
        val job = ecoJobs.job(view.jobId) ?: return open(player, view.back)
        val inventory = inventory(player, view, "menu.job.title", mapOf("job" to titleText(ecoJobs.name(job))))
        val active = ecoJobs.active(player, job)
        val state = locale.render(
            if (active) "menu.catalog.state-active" else if (ecoJobs.has(player, job)) "menu.catalog.state-available" else "menu.catalog.state-locked",
            player,
        )
        inventory.setItem(element(view, "overview"), jobItem(job, player, "menu.job.overview-lore", mapOf(
            "job" to ecoJobs.name(job),
            "description" to ecoJobs.description(job),
            "level" to text(ecoJobs.level(player, job)),
            "max" to text(job.maxLevel),
            "xp" to text(number.format(ecoJobs.xp(player, job))),
            "required" to text(ecoJobs.requiredXp(player, job)),
            "progress" to text(number.format((ecoJobs.progress(player, job) * 100).coerceIn(0.0, 100.0))),
            "state" to state,
        ), "menu.job.overview-name", mapOf("job" to ecoJobs.name(job))))
        inventory.setItem(element(view, "scale"), item(settings().guiItems["job-scale"], player, "menu.job.scale-name", "menu.job.scale-lore", mapOf("max" to text(job.maxLevel))))
        inventory.setItem(element(view, "earnings"), earningsSummaryItem(player, null))
        inventory.setItem(element(view, "leaderboard"), item(settings().guiItems["job-leaderboard"], player, "menu.job.leaderboard-name", "menu.job.leaderboard-lore", mapOf(
            "rank" to text(ecoJobs.rank(player, job)?.toString() ?: "—"),
        )))
        inventory.setItem(element(view, "boosts"), item(settings().guiItems["job-boosts"], player, "menu.job.boosts-name", "menu.job.boosts-lore", mapOf(
            "xp_multiplier" to text(formatMultiplier(boosts.multiplier(player, job.id, BoostType.XP))),
            "money_multiplier" to text(formatMultiplier(boosts.multiplier(player, job.id, BoostType.MONEY))),
        )))
        when {
            active -> inventory.setItem(element(view, "action"), item(settings().guiItems["job-leave"], player, "menu.job.leave-name", "menu.job.leave-lore"))
            ecoJobs.canJoin(player, job) -> inventory.setItem(element(view, "action"), item(settings().guiItems["job-join"], player, "menu.job.join-name", "menu.job.join-lore", mapOf(
                "free" to text((ecoJobs.limit(player) - ecoJobs.activeJobs(player).size).coerceAtLeast(0)),
            )))
            else -> inventory.setItem(element(view, "action"), item(settings().guiItems["job-unavailable"], player, "menu.job.unavailable-name", "menu.job.unavailable-lore"))
        }
        inventory.setItem(element(view, "back"), backItem(player))
        show(player, view, inventory)
        loadEarnings(player, view) { report ->
            updateFrame(player, view) { it.setItem(element(view, "earnings"), earningsSummaryItem(player, report)) }
        }
    }

    private fun clickJobCard(player: Player, view: JobsView.JobCard, slot: Int) {
        val job = ecoJobs.job(view.jobId) ?: return open(player, view.back)
        when (slot) {
            element(view, "earnings") -> if (settings().earnings.enabled) open(player, JobsView.Earnings(job.id, 1, view))
            element(view, "scale") -> {
                val levels = JobsView.Levels(job.id, 1, view)
                open(player, levels.copy(page = levelPage(ecoJobs.level(player, job), levels, player)))
            }
            element(view, "leaderboard") -> open(player, JobsView.Leaderboard(job.id, 1, view))
            element(view, "boosts") -> open(player, JobsView.Boosts(job.id, 1, view))
            element(view, "action") -> when {
                ecoJobs.active(player, job) -> open(player, JobsView.LeaveConfirm(job.id, view))
                !ecoJobs.canJoin(player, job) -> Unit
                ecoJobs.join(player, job) -> {
                    player.sendMessage(locale.render("message.joined", player, mapOf("job" to ecoJobs.name(job))))
                    open(player, view)
                }
                else -> player.sendMessage(locale.render("message.join-failed", player, mapOf("job" to ecoJobs.name(job))))
            }
            element(view, "back") -> open(player, view.back)
        }
    }

    private fun openLeave(player: Player, view: JobsView.LeaveConfirm) {
        val job = ecoJobs.job(view.jobId) ?: return open(player, view.back)
        val inventory = inventory(player, view, "menu.leave.title", mapOf("job" to titleText(ecoJobs.name(job))))
        inventory.setItem(element(view, "confirm"), item(settings().guiItems.confirm, player, "menu.leave.confirm-name", "menu.leave.confirm-lore", mapOf("job" to ecoJobs.name(job))))
        inventory.setItem(element(view, "cancel"), item(settings().guiItems.cancel, player, "menu.leave.cancel-name", "menu.leave.cancel-lore"))
        show(player, view, inventory)
    }

    private fun clickLeave(player: Player, view: JobsView.LeaveConfirm, slot: Int) {
        val job = ecoJobs.job(view.jobId) ?: return open(player, view.back)
        when (slot) {
            element(view, "confirm") -> if (ecoJobs.leave(player, job)) {
                player.sendMessage(locale.render("message.left", player, mapOf("job" to ecoJobs.name(job))))
                open(player, JobsView.JobCard(job.id, (view.back as? JobsView.JobCard)?.back ?: JobsView.Main))
            } else {
                player.sendMessage(locale.render("message.leave-failed", player, mapOf("job" to ecoJobs.name(job))))
                open(player, view.back)
            }
            element(view, "cancel") -> open(player, view.back)
        }
    }

    private fun openLevels(player: Player, view: JobsView.Levels) {
        val job = ecoJobs.job(view.jobId) ?: return open(player, view.back)
        val content = content(view, player)
        val pages = pageCount(job.maxLevel, content.size)
        val currentView = view.copy(page = view.page.coerceIn(1, pages))
        val inventory = inventory(player, currentView, "menu.levels.title", mapOf("job" to titleText(ecoJobs.name(job))))
        val playerLevel = ecoJobs.level(player, job)
        ((currentView.page - 1) * content.size + 1..minOf(currentView.page * content.size, job.maxLevel)).forEachIndexed { index, level ->
            val state = when {
                level < playerLevel -> Triple("level-reached", "menu.levels.reached-name", "menu.levels.status-reached")
                level == playerLevel -> Triple("level-current", "menu.levels.current-name", "menu.levels.status-current")
                else -> Triple("level-future", "menu.levels.future-name", "menu.levels.status-future")
            }
            val rewards = ecoJobs.rewards(player, job, level).ifEmpty { listOf(locale.render("menu.levels.no-rewards", player)) }
            inventory.setItem(content[index], item(settings().guiItems[state.first], player, state.second, "menu.levels.lore", mapOf(
                "level" to text(level),
                "required" to text(number.format(job.getExpForLevel(level))),
                "status" to locale.render(state.third, player),
            ), loreBlocks = mapOf("rewards" to rewards)))
        }
        navigation(inventory, player, currentView, currentView.page, pages)
        show(player, currentView, inventory)
    }

    private fun openEarnings(player: Player, view: JobsView.Earnings) {
        val job = ecoJobs.job(view.jobId) ?: return open(player, view.back)
        val inventory = inventory(
            player,
            view,
            "menu.earnings.title",
            mapOf("job" to titleText(ecoJobs.name(job))),
        )
        inventory.setItem(element(view, "status"), earningsLoadingItem(player))
        navigation(inventory, player, view, view.page, pageCount(settings().earnings.retentionDays, content(view, player).size))
        show(player, view, inventory)
        loadEarnings(player, view) { report -> renderEarnings(player, view, report) }
    }

    private fun renderEarnings(player: Player, view: JobsView.Earnings, report: EarningsReport) {
        val job = ecoJobs.job(view.jobId) ?: return open(player, view.back)
        val content = content(view, player)
        val pages = pageCount(settings().earnings.retentionDays, content.size)
        val current = view.copy(page = view.page.coerceIn(1, pages))
        val inventory = inventory(
            player,
            current,
            "menu.earnings.title",
            mapOf("job" to titleText(ecoJobs.name(job))),
        )
        inventory.setItem(element(current, "summary"), earningsSummaryItem(player, report))
        val today = LocalDate.now(settings().earnings.zoneId)
        val dates = (0 until settings().earnings.retentionDays).map { today.minusDays(it.toLong()) }
        dates.page(current.page, content.size).forEachIndexed { index, date ->
            val totals = report.forDate(date)
            inventory.setItem(
                content[index],
                item(
                    settings().guiItems[if (totals.empty) "earnings-day-empty" else "earnings-day-active"],
                    player,
                    if (date == today) "menu.earnings.today-name" else "menu.earnings.day-name",
                    "menu.earnings.day-lore",
                    earningsValues(totals) + ("date" to text(formatDate(date))),
                ),
            )
        }
        navigation(inventory, player, current, current.page, pages)
        replaceFrame(player, view, current, inventory)
    }

    private fun clickEarnings(player: Player, view: JobsView.Earnings, slot: Int) {
        if (slot == element(view, "summary")) {
            open(
                player,
                JobsView.EarningsHours(view.jobId, LocalDate.now(settings().earnings.zoneId), view),
            )
            return
        }
        val content = content(view, player)
        val index = content.indexOf(slot)
        if (index >= 0) {
            val offset = (view.page - 1) * content.size + index
            if (offset < settings().earnings.retentionDays) {
                val date = LocalDate.now(settings().earnings.zoneId).minusDays(offset.toLong())
                open(player, JobsView.EarningsHours(view.jobId, date, view))
                return
            }
        }
        clickPaged(
            player,
            view,
            slot,
            pageCount(settings().earnings.retentionDays, content.size),
        )
    }

    private fun openEarningsHours(player: Player, view: JobsView.EarningsHours) {
        val job = ecoJobs.job(view.jobId) ?: return open(player, view.back)
        val inventory = inventory(
            player,
            view,
            "menu.earnings.hours-title",
            mapOf("job" to titleText(ecoJobs.name(job)), "date" to text(formatDate(view.date))),
        )
        inventory.setItem(element(view, "status"), earningsLoadingItem(player))
        inventory.setItem(element(view, "back"), backItem(player))
        show(player, view, inventory)
        loadEarnings(player, view) { report ->
            val rendered = inventory(
                player,
                view,
                "menu.earnings.hours-title",
                mapOf("job" to titleText(ecoJobs.name(job)), "date" to text(formatDate(view.date))),
            )
            rendered.setItem(element(view, "summary"), item(
                settings().guiItems["earnings-loading"],
                player,
                "menu.earnings.day-summary-name",
                "menu.earnings.day-summary-lore",
                earningsValues(report.forDate(view.date)) + ("date" to text(formatDate(view.date))),
            ))
            (0..23).forEach { hour ->
                val totals = report.forHour(view.date, hour)
                rendered.setItem(
                    content(view, player)[hour],
                    item(
                        settings().guiItems[if (totals.empty) "earnings-hour-empty" else "earnings-hour-active"],
                        player,
                        "menu.earnings.hour-name",
                        "menu.earnings.hour-lore",
                        earningsValues(totals) + mapOf(
                            "from" to text(hourLabel(hour)),
                            "to" to text(hourLabel((hour + 1) % 24)),
                        ),
                    ),
                )
            }
            rendered.setItem(element(view, "back"), backItem(player))
            replaceFrame(player, view, view, rendered)
        }
    }

    private fun loadEarnings(player: Player, view: JobsView, render: (EarningsReport) -> Unit) {
        val expected = activeFrames[player.uniqueId] ?: return
        val service = earnings()
        if (!settings().earnings.enabled || service == null) {
            updateFrame(player, view) { it.setItem(earningsSlot(view), earningsUnavailableItem(player)) }
            return
        }
        val jobId = when (view) {
            is JobsView.JobCard -> view.jobId
            is JobsView.Earnings -> view.jobId
            is JobsView.EarningsHours -> view.jobId
            else -> return
        }
        service.report(player.uniqueId, jobId).whenComplete { report, failure ->
            if (!plugin.isEnabled) return@whenComplete
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!player.isOnline || activeFrames[player.uniqueId] !== expected) return@Runnable
                if (failure == null) render(report) else {
                    updateFrame(player, view) { it.setItem(earningsSlot(view), earningsUnavailableItem(player)) }
                }
            })
        }
    }

    private fun earningsSummaryItem(player: Player, report: EarningsReport?): ItemStack {
        if (!settings().earnings.enabled || earnings() == null) return earningsUnavailableItem(player)
        if (report == null) return earningsLoadingItem(player)
        val now = ZonedDateTime.now(settings().earnings.zoneId)
        val today = report.forDate(now.toLocalDate())
        val hour = report.forHour(now.toLocalDate(), now.hour)
        val week = (0L..6L).fold(EarningsTotals()) { total, daysAgo ->
            total + report.forDate(now.toLocalDate().minusDays(daysAgo))
        }
        return item(
            settings().guiItems["earnings-summary"],
            player,
            "menu.earnings.summary-name",
            "menu.earnings.summary-lore",
            mapOf(
                "today_money" to text(formatAmount(today.money)),
                "today_xp" to text(formatAmount(today.xp)),
                "hour_money" to text(formatAmount(hour.money)),
                "hour_xp" to text(formatAmount(hour.xp)),
                "week_money" to text(formatAmount(week.money)),
                "week_xp" to text(formatAmount(week.xp)),
            ),
        )
    }

    private fun earningsLoadingItem(player: Player): ItemStack =
        item(settings().guiItems["earnings-loading"], player, "menu.earnings.loading-name", "menu.earnings.loading-lore")

    private fun earningsUnavailableItem(player: Player): ItemStack =
        item(settings().guiItems["earnings-unavailable"], player, "menu.earnings.unavailable-name", "menu.earnings.unavailable-lore")

    private fun earningsValues(totals: EarningsTotals): Map<String, Component> = mapOf(
        "money" to text(formatAmount(totals.money)),
        "xp" to text(formatAmount(totals.xp)),
    )

    private fun formatAmount(value: java.math.BigDecimal): String = number.format(value.stripTrailingZeros())
    private fun formatDate(date: LocalDate): String = "%02d.%02d.%04d".format(date.dayOfMonth, date.monthValue, date.year)
    private fun hourLabel(hour: Int): String = "%02d:00".format(hour)

    private fun openLeaderboardSelector(player: Player, view: JobsView.LeaderboardSelector) {
        val jobs = ecoJobs.jobs()
        val content = content(view, player)
        val pages = pageCount(jobs.size, content.size)
        val currentView = view.copy(page = view.page.coerceIn(1, pages))
        val inventory = inventory(player, currentView, "menu.leaderboard.selector-title")
        inventory.setItem(element(currentView, "global"), item(settings().guiItems["leaderboard-global"], player, "menu.leaderboard.global-name", "menu.leaderboard.global-lore"))
        jobs.page(currentView.page, content.size).forEachIndexed { index, job ->
            inventory.setItem(content[index], jobItem(job, player, "menu.leaderboard.job-lore", mapOf("job" to ecoJobs.name(job))))
        }
        navigation(inventory, player, currentView, currentView.page, pages)
        show(player, currentView, inventory)
    }

    private fun clickLeaderboardSelector(player: Player, view: JobsView.LeaderboardSelector, slot: Int) {
        if (slot == element(view, "global")) return open(player, JobsView.Leaderboard(null, 1, view))
        val content = content(view, player)
        val index = content.indexOf(slot)
        if (index >= 0) ecoJobs.jobs().getOrNull((view.page - 1) * content.size + index)?.let {
            return open(player, JobsView.Leaderboard(it.id, 1, view))
        }
        clickPaged(player, view, slot, pageCount(ecoJobs.jobs().size, content.size))
    }

    private fun openLeaderboard(player: Player, view: JobsView.Leaderboard) {
        val job = view.jobId?.let(ecoJobs::job)
        if (view.jobId != null && job == null) return open(player, view.back)
        val titlePath = if (job == null) "menu.leaderboard.title-global" else "menu.leaderboard.title-job"
        val titleValues = mapOf("job" to (job?.let(ecoJobs::name)?.let(::titleText) ?: Component.empty()))
        val rankings = ecoJobs.rankings(job)
        if (rankings == null) {
            val loadingView = view.copy(page = view.page.coerceAtLeast(1))
            val inventory = inventory(player, loadingView, titlePath, titleValues)
            inventory.setItem(element(loadingView, "status"), item(settings().guiItems["leaderboard-loading"], player, "menu.leaderboard.loading-name", "menu.leaderboard.loading-lore"))
            navigation(inventory, player, loadingView, 1, 1)
            show(player, loadingView, inventory)
            val expected = activeFrames[player.uniqueId]
            ecoJobs.prepareLeaderboards { result ->
                if (!player.isOnline || activeFrames[player.uniqueId] !== expected) return@prepareLeaderboards
                if (result.isSuccess) open(player, loadingView) else openLeaderboardFailure(player, loadingView, titlePath, titleValues)
            }
            return
        }
        val pageSize = leaderboardPageSize(player)
        val pages = pageCount(rankings.size, pageSize)
        val currentView = view.copy(page = view.page.coerceIn(1, pages))
        val content = content(currentView, player)
        require(pageSize <= content.size) { "leaderboards.entries-per-page exceeds configured leaderboard content region" }
        val inventory = inventory(player, currentView, titlePath, titleValues)
        rankings.page(currentView.page, pageSize).forEachIndexed { index, entry ->
            val rank = (currentView.page - 1) * pageSize + index + 1
            inventory.setItem(content[index], playerHead(entry.uuid, entry.name, player, "menu.leaderboard.entry-name", if (job == null) "menu.leaderboard.entry-global-lore" else "menu.leaderboard.entry-job-lore", mapOf(
                "rank_color" to rankLabel(rank),
                "rank" to text(rank),
                "player" to text(entry.name),
                "level" to text(entry.level),
                "xp" to text(number.format(entry.xp)),
            )))
        }
        val own = rankings.firstOrNull { it.uuid == player.uniqueId }
        inventory.setItem(element(currentView, "self"), item(settings().guiItems["leaderboard-self"], player, "menu.leaderboard.self-name", "menu.leaderboard.self-lore", mapOf(
            "rank" to text(ecoJobs.rank(player, job)?.toString() ?: "—"),
            "level" to text(own?.level ?: 0),
            "xp" to text(number.format(own?.xp ?: 0.0)),
        )))
        if (rankings.isEmpty()) inventory.setItem(element(currentView, "status"), item(settings().guiItems["empty"], player, "menu.leaderboard.empty-name", "menu.leaderboard.empty-lore"))
        navigation(inventory, player, currentView, currentView.page, pages)
        show(player, currentView, inventory)
    }

    private fun openLeaderboardFailure(
        player: Player,
        view: JobsView.Leaderboard,
        titlePath: String,
        titleValues: Map<String, Component>,
    ) {
        val inventory = inventory(player, view, titlePath, titleValues)
        inventory.setItem(element(view, "status"), item(settings().guiItems["leaderboard-failed"], player, "menu.leaderboard.failed-name", "menu.leaderboard.failed-lore"))
        navigation(inventory, player, view, 1, 1)
        show(player, view, inventory)
    }

    private fun openBoosts(player: Player, view: JobsView.Boosts) {
        val job = view.jobId?.let(ecoJobs::job)
        if (view.jobId != null && job == null) return open(player, view.back)
        val applicable = applicableBoosts(player, view.jobId)
        val content = content(view, player)
        val pages = pageCount(applicable.size, content.size)
        val currentView = view.copy(page = view.page.coerceIn(1, pages))
        val inventory = inventory(
            player,
            currentView,
            if (job == null) "menu.boosts.title" else "menu.boosts.title-job",
            mapOf("job" to (job?.let(ecoJobs::name)?.let(::titleText) ?: Component.empty())),
        )
        val summaryJobs = job?.let { listOf(it.id) } ?: ecoJobs.jobs().map { it.id }
        val summaryXp = summaryJobs.maxOfOrNull { boosts.multiplier(player, it, BoostType.XP) } ?: 1.0
        val summaryMoney = summaryJobs.maxOfOrNull { boosts.multiplier(player, it, BoostType.MONEY) } ?: 1.0
        inventory.setItem(element(currentView, "summary"), item(settings().guiItems["boosts-summary"], player, "menu.boosts.summary-name", "menu.boosts.summary-lore", mapOf(
            "xp_multiplier" to text(formatMultiplier(summaryXp)),
            "money_multiplier" to text(formatMultiplier(summaryMoney)),
        )))
        applicable.page(currentView.page, content.size).forEachIndexed { index, boost ->
            inventory.setItem(content[index], boostItem(player, boost))
        }
        if (applicable.isEmpty()) inventory.setItem(element(currentView, "empty"), item(settings().guiItems["empty"], player, "menu.boosts.empty-name", "menu.boosts.empty-lore"))
        navigation(inventory, player, currentView, currentView.page, pages)
        show(player, currentView, inventory)
    }

    private fun openHelp(player: Player, view: JobsView.Help) {
        val inventory = inventory(player, view, "menu.help.title")
        inventory.setItem(element(view, "jobs"), item(settings().guiItems.catalog, player, "menu.help.jobs-name", "menu.help.jobs-lore"))
        inventory.setItem(element(view, "levels"), item(settings().guiItems["help-levels"], player, "menu.help.levels-name", "menu.help.levels-lore"))
        inventory.setItem(element(view, "boosts"), item(settings().guiItems["help-boosts"], player, "menu.help.boosts-name", "menu.help.boosts-lore"))
        inventory.setItem(element(view, "commands"), item(settings().guiItems["help-commands"], player, "menu.help.commands-name", "menu.help.commands-lore"))
        inventory.setItem(element(view, "back"), backItem(player))
        show(player, view, inventory)
    }

    private fun openAdmin(player: Player, view: JobsView.Admin) {
        if (!player.hasPermission("arcecojobs.admin")) return open(player, view.back)
        val problems = ecoJobs.moneyIntegrationProblems()
        val ok = Component.text("OK", NamedTextColor.GREEN)
        val bad = Component.text(if (problems.isEmpty()) "OK" else problems.joinToString(", "), if (problems.isEmpty()) NamedTextColor.GREEN else NamedTextColor.RED)
        val inventory = inventory(player, view, "menu.admin.title")
        inventory.setItem(element(view, "status"), item(settings().guiItems["admin-status"], player, "menu.admin.status-name", "menu.admin.status-lore", mapOf(
            "ecojobs" to ok,
            "luckperms" to ok,
            "papi" to ok,
            "jobs" to text(ecoJobs.jobs().size),
            "money" to bad,
        )))
        inventory.setItem(element(view, "reload"), item(settings().guiItems.refresh, player, "menu.admin.reload-name", "menu.admin.reload-lore"))
        inventory.setItem(element(view, "presets"), item(settings().guiItems["admin-presets"], player, "menu.admin.presets-name", "menu.admin.presets-lore", mapOf("count" to text(boosters().values().size))))
        inventory.setItem(element(view, "help"), item(settings().guiItems["admin-help"], player, "menu.admin.help-name", "menu.admin.help-lore"))
        inventory.setItem(element(view, "back"), backItem(player))
        show(player, view, inventory)
    }

    private fun clickAdmin(player: Player, view: JobsView.Admin, slot: Int) {
        if (!player.hasPermission("arcecojobs.admin")) return open(player, view.back)
        when (slot) {
            element(view, "reload") -> if (player.hasPermission("arcecojobs.admin.reload")) {
                reload().fold(
                    onSuccess = {
                        player.sendMessage(locale.render("message.reload-ok", player))
                        open(player, view)
                    },
                    onFailure = { failure -> player.sendMessage(locale.render("message.reload-failed", player, mapOf("reason" to text(failure.message ?: "unknown")))) },
                )
            }
            element(view, "presets") -> if (player.hasPermission("arcecojobs.admin.booster")) open(player, JobsView.Presets(1, view))
            element(view, "help") -> locale.lines("command.admin-help", player).forEach(player::sendMessage)
            element(view, "back") -> open(player, view.back)
        }
    }

    private fun openPresets(player: Player, view: JobsView.Presets) {
        if (!player.hasPermission("arcecojobs.admin.booster")) return open(player, view.back)
        val presets = boosters().values()
        val content = content(view, player)
        val pages = pageCount(presets.size, content.size)
        val currentView = view.copy(page = view.page.coerceIn(1, pages))
        val inventory = inventory(player, currentView, "menu.admin.presets-title")
        presets.page(currentView.page, content.size).forEachIndexed { index, preset ->
            val preview = vouchers.create(preset, player)
            preview.editMeta { meta ->
                meta.lore(locale.lines("menu.admin.preset-lore", player, mapOf(
                    "type" to locale.type(preset.type, player),
                    "multiplier" to text(Multipliers.format(preset.multiplierBasisPoints)),
                    "duration" to text(locale.duration(preset.duration, player)),
                    "jobs" to jobsLabel(preset.jobs, player),
                    "state" to locale.render(
                        if (preset.enabled) "menu.admin.preset-state-enabled" else "menu.admin.preset-state-disabled",
                        player,
                    ),
                    "action" to locale.render(
                        if (preset.enabled) "menu.admin.preset-action-enabled" else "menu.admin.preset-action-disabled",
                        player,
                    ),
                )).map { it.decoration(TextDecoration.ITALIC, false) })
            }
            inventory.setItem(content[index], preview)
        }
        navigation(inventory, player, currentView, currentView.page, pages)
        show(player, currentView, inventory)
    }

    private fun clickPresets(player: Player, view: JobsView.Presets, slot: Int) {
        if (!player.hasPermission("arcecojobs.admin.booster")) return open(player, view.back)
        val presets = boosters().values()
        val content = content(view, player)
        val index = content.indexOf(slot)
        if (index >= 0) presets.getOrNull((view.page - 1) * content.size + index)?.let { preset ->
            if (!preset.enabled) {
                player.sendMessage(locale.render("message.booster-disabled", player, mapOf("id" to text(preset.id))))
                return
            }
            val leftovers = player.inventory.addItem(vouchers.create(preset, player))
            if (leftovers.isEmpty()) {
                player.sendMessage(locale.render("message.booster-received", player, mapOf(
                    "amount" to text(1),
                    "item" to locale.render(preset.item.nameKey, player),
                )))
            } else player.sendMessage(locale.render("message.inventory-full", player))
            return
        }
        clickPaged(player, view, slot, pageCount(presets.size, content.size))
    }

    private fun clickPaged(player: Player, view: JobsView, slot: Int, pages: Int) {
        when (slot) {
            element(view, "back") -> open(player, when (view) {
                is JobsView.Catalog -> view.back
                is JobsView.Levels -> view.back
                is JobsView.Earnings -> view.back
                is JobsView.EarningsHours -> view.back
                is JobsView.LeaderboardSelector -> view.back
                is JobsView.Leaderboard -> view.back
                is JobsView.Boosts -> view.back
                is JobsView.Presets -> view.back
                else -> JobsView.Main
            })
            element(view, "previous") -> when (view) {
                is JobsView.Catalog -> if (view.page > 1) open(player, view.copy(page = view.page - 1))
                is JobsView.Levels -> if (view.page > 1) open(player, view.copy(page = view.page - 1))
                is JobsView.Earnings -> if (view.page > 1) open(player, view.copy(page = view.page - 1))
                is JobsView.LeaderboardSelector -> if (view.page > 1) open(player, view.copy(page = view.page - 1))
                is JobsView.Leaderboard -> if (view.page > 1) open(player, view.copy(page = view.page - 1))
                is JobsView.Boosts -> if (view.page > 1) open(player, view.copy(page = view.page - 1))
                is JobsView.Presets -> if (view.page > 1) open(player, view.copy(page = view.page - 1))
                else -> Unit
            }
            element(view, "next") -> when (view) {
                is JobsView.Catalog -> if (view.page < pages) open(player, view.copy(page = view.page + 1))
                is JobsView.Levels -> if (view.page < pages) open(player, view.copy(page = view.page + 1))
                is JobsView.Earnings -> if (view.page < pages) open(player, view.copy(page = view.page + 1))
                is JobsView.LeaderboardSelector -> if (view.page < pages) open(player, view.copy(page = view.page + 1))
                is JobsView.Leaderboard -> if (view.page < pages) open(player, view.copy(page = view.page + 1))
                is JobsView.Boosts -> if (view.page < pages) open(player, view.copy(page = view.page + 1))
                is JobsView.Presets -> if (view.page < pages) open(player, view.copy(page = view.page + 1))
                else -> Unit
            }
        }
    }

    private fun navigation(inventory: PaperMenuFrame, player: Player, view: JobsView, page: Int, pages: Int) {
        inventory.setItem(element(view, "back"), backItem(player))
        if (page > 1) inventory.setItem(element(view, "previous"), item(settings().guiItems.previous, player, "common.previous-name", "common.previous-lore", mapOf(
            "page" to text(page), "pages" to text(pages),
        )))
        if (page < pages) inventory.setItem(element(view, "next"), item(settings().guiItems.next, player, "common.next-name", "common.next-lore", mapOf(
            "page" to text(page), "pages" to text(pages),
        )))
    }

    private fun inventory(
        player: Player,
        view: JobsView,
        titlePath: String,
        values: Map<String, Component> = emptyMap(),
    ): PaperMenuFrame {
        val filler = styledItem(settings().guiItems.background).apply { editMeta { it.displayName(Component.empty()) } }
        return PaperMenuFrame.physical(
            layouts.current().catalog.require(JobsMenuLayouts.menu(view)),
            locale.render(titlePath, player, values),
            filler,
        )
    }

    private fun show(player: Player, view: JobsView, frame: PaperMenuFrame) {
        val presentation = presentations.getValue(player.uniqueId)
        if (presentation == JobsMenuPresentation.DIALOG) {
            // DialogAfterAction.NONE keeps the client screen open across navigation and refreshes.
            // closeInventory here would send close_window and recenter the mouse on every click.
            val active = ActiveFrame(view, frame)
            activeFrames[player.uniqueId] = active
            showDialog(player, active)
        } else {
            // Closing the previous inventory fires synchronously; publish ownership afterwards.
            if (menuRuntimeDelegate.isInitialized()) menuRuntime.session(player)?.close()
            player.closeInventory()
            presentations[player.uniqueId] = presentation
            activeFrames[player.uniqueId] = ActiveFrame(view, frame)
            menuRuntime.open(player, JobsMenuLayouts.menu(view)) {
                val active = activeFrames[player.uniqueId] ?: ActiveFrame(view, frame)
                active.frame.content { slot, _ -> dispatchClick(player, active.view, slot) }
            }
        }
    }

    private fun showDialog(player: Player, active: ActiveFrame) {
        val revision = ++active.revision
        fun current() = player.isOnline && activeFrames[player.uniqueId] === active &&
            active.revision == revision && player.uniqueId !in pendingClicks
        val screen = JobsDialogScreens.screen(
            player, active.view, active.frame, layouts, locale, active.detailSlot, escapeGoesBack(player),
            actionable = { id, index -> dialogActionable(player, active.view, id, index) },
            click = { slot -> if (current()) dispatchClick(player, active.view, slot) },
            detail = { slot -> if (current()) { active.detailSlot = slot; showDialog(player, active) } },
            close = { if (current()) dismiss(player) },
        )
        dialogs.show(player, screen)
    }

    private fun dialogActionable(player: Player, view: JobsView, id: String, index: Int?): Boolean {
        if (index != null) return when (view) {
            is JobsView.Catalog, is JobsView.LeaderboardSelector, is JobsView.Earnings -> true
            is JobsView.Presets -> player.hasPermission("arcecojobs.admin.booster") &&
                boosters().values().getOrNull((view.page - 1) * content(view, player).size + index)?.enabled == true
            else -> false
        }
        if (id in setOf("back", "cancel", "previous", "next")) return true
        return when (view) {
            JobsView.Main -> id != "profile"
            is JobsView.JobCard -> when (id) {
                "action" -> ecoJobs.job(view.jobId)?.let { ecoJobs.active(player, it) || ecoJobs.canJoin(player, it) } == true
                "earnings" -> settings().earnings.enabled && earnings() != null
                else -> id in setOf("scale", "leaderboard", "boosts")
            }
            is JobsView.LeaveConfirm -> id == "confirm"
            is JobsView.Earnings -> id == "summary"
            is JobsView.LeaderboardSelector -> id == "global"
            is JobsView.Admin -> when (id) {
                "reload" -> player.hasPermission("arcecojobs.admin.reload")
                "presets" -> player.hasPermission("arcecojobs.admin.booster")
                "help" -> true
                else -> false
            }
            else -> false
        }
    }

    private fun replaceFrame(player: Player, expected: JobsView, next: JobsView, frame: PaperMenuFrame) {
        if (activeFrames[player.uniqueId]?.view != expected) return
        val active = ActiveFrame(next, frame)
        activeFrames[player.uniqueId] = active
        if (usesDialog(player)) showDialog(player, active) else menuRuntime.session(player)?.refresh()
    }

    private fun updateFrame(player: Player, expected: JobsView, update: (PaperMenuFrame) -> Unit) {
        val active = activeFrames[player.uniqueId].takeIf { it?.view == expected } ?: return
        update(active.frame)
        if (usesDialog(player)) showDialog(player, active) else menuRuntime.session(player)?.requestRefresh()
    }

    private fun element(view: JobsView, id: String): Int = layouts.slot(view, id)

    private fun content(view: JobsView, player: Player): List<Int> = layouts.region(view, "content").let {
        if (usesDialog(player) && view !is JobsView.EarningsHours) it.take(if (view is JobsView.Earnings) 6 else 8) else it
    }

    private fun leaderboardPageSize(player: Player): Int =
        if (usesDialog(player)) minOf(8, settings().leaderboardEntriesPerPage) else settings().leaderboardEntriesPerPage

    private fun earningsSlot(view: JobsView): Int = when (view) {
        is JobsView.JobCard -> element(view, "earnings")
        is JobsView.Earnings, is JobsView.EarningsHours -> element(view, "status")
        else -> error("No earnings status slot for ${view::class.simpleName}")
    }

    private fun item(
        material: Material,
        player: Player,
        namePath: String,
        lorePath: String? = null,
        values: Map<String, Component> = emptyMap(),
        loreBlocks: Map<String, List<Component>> = emptyMap(),
    ): ItemStack = ItemStack(material).apply {
        editMeta { meta ->
            meta.displayName(locale.render(namePath, player, values).decoration(TextDecoration.ITALIC, false))
            if (lorePath != null) {
                meta.lore(locale.lines(lorePath, player, values, loreBlocks).map { it.decoration(TextDecoration.ITALIC, false) })
            }
        }
    }

    private fun item(
        definition: GuiItemDefinition,
        player: Player,
        namePath: String,
        lorePath: String? = null,
        values: Map<String, Component> = emptyMap(),
        loreBlocks: Map<String, List<Component>> = emptyMap(),
    ): ItemStack = styledItem(definition).apply {
        editMeta { meta ->
            meta.displayName(locale.render(namePath, player, values).decoration(TextDecoration.ITALIC, false))
            if (lorePath != null) {
                meta.lore(locale.lines(lorePath, player, values, loreBlocks).map { it.decoration(TextDecoration.ITALIC, false) })
            }
        }
    }

    private fun styledItem(definition: GuiItemDefinition): ItemStack = ItemStack(definition.material).apply {
        definition.customModelData?.let { modelData -> editMeta { it.setCustomModelData(modelData) } }
    }

    private fun jobItem(
        job: Job,
        player: Player,
        lorePath: String,
        loreValues: Map<String, Component>,
        namePath: String? = null,
        nameValues: Map<String, Component> = emptyMap(),
    ): ItemStack = job.getIcon(player).clone().apply {
        editMeta { meta ->
            meta.displayName(
                (namePath?.let { locale.render(it, player, nameValues) } ?: ecoJobs.name(job)).decoration(TextDecoration.ITALIC, false),
            )
            meta.lore(locale.lines(lorePath, player, loreValues).map { it.decoration(TextDecoration.ITALIC, false) })
        }
    }

    private fun playerHead(
        owner: OfflinePlayer,
        audience: Player,
        namePath: String,
        lorePath: String,
        values: Map<String, Component>,
    ): ItemStack = item(Material.PLAYER_HEAD, audience, namePath, lorePath, values).apply {
        editMeta(SkullMeta::class.java) { it.owningPlayer = owner }
    }

    private fun playerHead(
        player: Player,
        namePath: String,
        lorePath: String,
        values: Map<String, Component>,
    ): ItemStack = playerHead(player as OfflinePlayer, player, namePath, lorePath, values)

    private fun playerHead(
        uuid: java.util.UUID,
        name: String,
        audience: Player,
        namePath: String,
        lorePath: String,
        values: Map<String, Component>,
    ): ItemStack = item(Material.PLAYER_HEAD, audience, namePath, lorePath, values).apply {
        editMeta(SkullMeta::class.java) { it.setOwnerProfile(Bukkit.createPlayerProfile(uuid, name.take(16))) }
    }

    private fun boostItem(player: Player, boost: BoostInstance): ItemStack = item(
        Material.EXPERIENCE_BOTTLE,
        player,
        "menu.boosts.entry-name",
        "menu.boosts.entry-lore",
        mapOf(
            "multiplier" to text(Multipliers.format(boost.multiplierBasisPoints)),
            "type" to locale.type(boost.type, player),
            "jobs" to jobsLabel(boost.jobs, player),
            "duration" to text(locale.duration(Duration.between(Instant.now(), boost.expiresAt).coerceAtLeast(Duration.ZERO), player)),
            "instance" to text(boost.instanceId.toString().take(8)),
        ),
    )

    private fun applicableBoosts(player: Player, jobId: String?): List<BoostInstance> = boosts.active(player).filter { boost ->
        jobId == null || "all" in boost.jobs || jobId in boost.jobs
    }

    private fun jobsLabel(jobs: Set<String>, player: Player): Component =
        if ("all" in jobs) locale.allJobs(player) else ecoJobs.names(jobs)

    private fun backItem(player: Player): ItemStack = item(settings().guiItems.back, player, "common.back-name", "common.back-lore")
    private fun text(value: Any?): Component = locale.text(value)
    private fun titleText(value: Component): Component = Component.text(plain.serialize(value))
    private fun formatMultiplier(multiplier: Double): String = Multipliers.format(Multipliers.toBasisPoints(multiplier))
    private fun rankLabel(rank: Int): Component = Component.text("#$rank", when (rank) {
        1 -> NamedTextColor.GOLD
        2 -> NamedTextColor.GRAY
        3 -> NamedTextColor.RED
        else -> NamedTextColor.DARK_GRAY
    })

    private fun levelPage(level: Int, view: JobsView.Levels, player: Player): Int =
        ((level.coerceAtLeast(1) - 1) / content(view, player).size) + 1
    private fun pageCount(size: Int, pageSize: Int): Int = ceil(size.coerceAtLeast(1) / pageSize.toDouble()).toInt().coerceAtLeast(1)
    private fun <T> List<T>.page(page: Int, pageSize: Int): List<T> = drop((page - 1) * pageSize).take(pageSize)
}
