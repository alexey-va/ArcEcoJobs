package ru.ruscrafting.ecojobs.paper

import com.willfp.ecojobs.jobs.Job
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import ru.ruscrafting.ecojobs.boost.BoostService
import ru.ruscrafting.ecojobs.boost.VoucherService
import ru.ruscrafting.ecojobs.config.AddonSettings
import ru.ruscrafting.ecojobs.config.BoosterRegistry
import ru.ruscrafting.ecojobs.config.JobsLocale
import ru.ruscrafting.ecojobs.domain.BoostInstance
import ru.ruscrafting.ecojobs.domain.BoostType
import ru.ruscrafting.ecojobs.domain.DurationParser
import ru.ruscrafting.ecojobs.domain.Multipliers
import ru.ruscrafting.ecojobs.integration.EcoJobsBridge
import java.text.DecimalFormat
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

sealed interface JobsView {
    data object Main : JobsView
    data class Catalog(val activeOnly: Boolean, val page: Int, val back: JobsView) : JobsView
    data class JobCard(val jobId: String, val back: JobsView) : JobsView
    data class LeaveConfirm(val jobId: String, val back: JobsView) : JobsView
    data class Levels(val jobId: String, val page: Int, val back: JobsView) : JobsView
    data class LeaderboardSelector(val back: JobsView) : JobsView
    data class Leaderboard(val jobId: String?, val page: Int, val back: JobsView) : JobsView
    data class Boosts(val jobId: String?, val page: Int, val back: JobsView) : JobsView
    data class Help(val back: JobsView) : JobsView
    data class Admin(val back: JobsView) : JobsView
    data class Presets(val page: Int, val back: JobsView) : JobsView
}

class JobsMenu(
    private val settings: () -> AddonSettings,
    private val locale: JobsLocale,
    private val ecoJobs: EcoJobsBridge,
    private val boosts: BoostService,
    private val boosters: () -> BoosterRegistry,
    private val vouchers: VoucherService,
    private val reload: () -> Result<Unit>,
) {
    private class Holder(val view: JobsView) : InventoryHolder {
        lateinit var backing: Inventory
        override fun getInventory(): Inventory = backing
    }

    private val number = DecimalFormat("#,##0.##")
    private val contentSlots = listOf(
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43,
    )

    fun open(player: Player, view: JobsView = JobsView.Main) {
        when (view) {
            JobsView.Main -> openMain(player)
            is JobsView.Catalog -> openCatalog(player, view)
            is JobsView.JobCard -> openJobCard(player, view)
            is JobsView.LeaveConfirm -> openLeave(player, view)
            is JobsView.Levels -> openLevels(player, view)
            is JobsView.LeaderboardSelector -> openLeaderboardSelector(player, view)
            is JobsView.Leaderboard -> openLeaderboard(player, view)
            is JobsView.Boosts -> openBoosts(player, view)
            is JobsView.Help -> openHelp(player, view)
            is JobsView.Admin -> openAdmin(player, view)
            is JobsView.Presets -> openPresets(player, view)
        }
    }

    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.isCancelled = true
        if (event.clickedInventory !== event.view.topInventory) return
        val player = event.whoClicked as? Player ?: return
        val slot = event.rawSlot
        when (val view = holder.view) {
            JobsView.Main -> clickMain(player, slot)
            is JobsView.Catalog -> clickCatalog(player, view, slot)
            is JobsView.JobCard -> clickJobCard(player, view, slot)
            is JobsView.LeaveConfirm -> clickLeave(player, view, slot)
            is JobsView.Levels -> clickPaged(player, view, slot, pageCount(ecoJobs.job(view.jobId)?.maxLevel ?: 0, contentSlots.size))
            is JobsView.LeaderboardSelector -> clickLeaderboardSelector(player, view, slot)
            is JobsView.Leaderboard -> clickPaged(player, view, slot, pageCount(ecoJobs.rankings(view.jobId?.let(ecoJobs::job)).size, settings().leaderboardEntriesPerPage))
            is JobsView.Boosts -> clickPaged(player, view, slot, pageCount(applicableBoosts(player, view.jobId).size, contentSlots.size))
            is JobsView.Help -> if (slot == 36) open(player, view.back) else if (slot == 44) player.closeInventory()
            is JobsView.Admin -> clickAdmin(player, view, slot)
            is JobsView.Presets -> clickPresets(player, view, slot)
        }
    }

    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Holder) event.isCancelled = true
    }

    private fun openMain(player: Player) {
        val inventory = inventory(player, JobsView.Main, 54, "menu.main.title")
        inventory.setItem(4, playerHead(player, "menu.main.profile-name", "menu.main.profile-lore", mapOf(
            "player" to text(player.name),
            "active" to text(ecoJobs.activeJobs(player).size),
            "limit" to text(ecoJobs.limit(player)),
            "level" to text(ecoJobs.totalLevel(player)),
        )))
        inventory.setItem(20, item(Material.COMPASS, player, "menu.main.catalog-name", "menu.main.catalog-lore"))
        inventory.setItem(22, item(Material.WRITABLE_BOOK, player, "menu.main.active-name", "menu.main.active-lore", mapOf(
            "active" to text(ecoJobs.activeJobs(player).size),
        )))
        inventory.setItem(24, item(Material.GOLDEN_HELMET, player, "menu.main.leaderboard-name", "menu.main.leaderboard-lore"))
        inventory.setItem(30, item(Material.EXPERIENCE_BOTTLE, player, "menu.main.boosts-name", "menu.main.boosts-lore", mapOf(
            "count" to text(boosts.active(player).size),
        )))
        inventory.setItem(32, item(Material.KNOWLEDGE_BOOK, player, "menu.main.help-name", "menu.main.help-lore"))
        if (player.hasPermission("arcecojobs.admin")) {
            inventory.setItem(49, item(Material.COMMAND_BLOCK, player, "menu.main.admin-name", "menu.main.admin-lore"))
        }
        inventory.setItem(53, closeItem(player))
        player.openInventory(inventory)
    }

    private fun clickMain(player: Player, slot: Int) {
        when (slot) {
            20 -> open(player, JobsView.Catalog(false, 1, JobsView.Main))
            22 -> open(player, JobsView.Catalog(true, 1, JobsView.Main))
            24 -> open(player, JobsView.LeaderboardSelector(JobsView.Main))
            30 -> open(player, JobsView.Boosts(null, 1, JobsView.Main))
            32 -> open(player, JobsView.Help(JobsView.Main))
            49 -> if (player.hasPermission("arcecojobs.admin")) open(player, JobsView.Admin(JobsView.Main))
            53 -> player.closeInventory()
        }
    }

    private fun openCatalog(player: Player, view: JobsView.Catalog) {
        val jobs = ecoJobs.jobs().filter { !view.activeOnly || ecoJobs.active(player, it) }
        val pages = pageCount(jobs.size, contentSlots.size)
        val current = view.copy(page = view.page.coerceIn(1, pages))
        val inventory = inventory(player, current, 54, if (view.activeOnly) "menu.catalog.title-active" else "menu.catalog.title")
        jobs.page(current.page, contentSlots.size).forEachIndexed { index, job ->
            val state = when {
                ecoJobs.active(player, job) -> locale.render("menu.catalog.state-active", player)
                ecoJobs.has(player, job) -> locale.render("menu.catalog.state-available", player)
                else -> locale.render("menu.catalog.state-locked", player)
            }
            inventory.setItem(contentSlots[index], jobItem(job, player, "menu.catalog.job-lore", mapOf(
                "description" to ecoJobs.description(job),
                "level" to text(ecoJobs.level(player, job)),
                "max" to text(job.maxLevel),
                "state" to state,
                "workers" to text(ecoJobs.workers(job)),
            )))
        }
        if (jobs.isEmpty()) inventory.setItem(22, item(Material.GRAY_DYE, player, "menu.catalog.empty-name", "menu.catalog.empty-lore"))
        navigation(inventory, player, current.back, current.page, pages)
        player.openInventory(inventory)
    }

    private fun clickCatalog(player: Player, view: JobsView.Catalog, slot: Int) {
        val jobs = ecoJobs.jobs().filter { !view.activeOnly || ecoJobs.active(player, it) }
        val index = contentSlots.indexOf(slot)
        if (index >= 0) jobs.getOrNull((view.page - 1) * contentSlots.size + index)?.let {
            open(player, JobsView.JobCard(it.id, view))
            return
        }
        clickPaged(player, view, slot, pageCount(jobs.size, contentSlots.size))
    }

    private fun openJobCard(player: Player, view: JobsView.JobCard) {
        val job = ecoJobs.job(view.jobId) ?: return open(player, view.back)
        val inventory = inventory(player, view, 54, "menu.job.title", mapOf("job" to ecoJobs.name(job)))
        val active = ecoJobs.active(player, job)
        val state = locale.render(
            if (active) "menu.catalog.state-active" else if (ecoJobs.has(player, job)) "menu.catalog.state-available" else "menu.catalog.state-locked",
            player,
        )
        inventory.setItem(13, jobItem(job, player, "menu.job.overview-lore", mapOf(
            "job" to ecoJobs.name(job),
            "description" to ecoJobs.description(job),
            "level" to text(ecoJobs.level(player, job)),
            "max" to text(job.maxLevel),
            "xp" to text(number.format(ecoJobs.xp(player, job))),
            "required" to text(ecoJobs.requiredXp(player, job)),
            "progress" to text(number.format((ecoJobs.progress(player, job) * 100).coerceIn(0.0, 100.0))),
            "state" to state,
        ), "menu.job.overview-name", mapOf("job" to ecoJobs.name(job))))
        inventory.setItem(29, item(Material.REPEATER, player, "menu.job.scale-name", "menu.job.scale-lore", mapOf("max" to text(job.maxLevel))))
        inventory.setItem(31, item(Material.GOLDEN_HELMET, player, "menu.job.leaderboard-name", "menu.job.leaderboard-lore", mapOf(
            "rank" to text(ecoJobs.rank(player, job)?.toString() ?: "—"),
        )))
        inventory.setItem(33, item(Material.EXPERIENCE_BOTTLE, player, "menu.job.boosts-name", "menu.job.boosts-lore", mapOf(
            "xp_multiplier" to text(formatMultiplier(boosts.multiplier(player, job.id, BoostType.XP))),
            "money_multiplier" to text(formatMultiplier(boosts.multiplier(player, job.id, BoostType.MONEY))),
        )))
        when {
            active -> inventory.setItem(40, item(Material.RED_DYE, player, "menu.job.leave-name", "menu.job.leave-lore"))
            ecoJobs.canJoin(player, job) -> inventory.setItem(40, item(Material.LIME_DYE, player, "menu.job.join-name", "menu.job.join-lore", mapOf(
                "free" to text((ecoJobs.limit(player) - ecoJobs.activeJobs(player).size).coerceAtLeast(0)),
            )))
            else -> inventory.setItem(40, item(Material.GRAY_DYE, player, "menu.job.unavailable-name", "menu.job.unavailable-lore"))
        }
        navigation(inventory, player, view.back, 1, 1)
        player.openInventory(inventory)
    }

    private fun clickJobCard(player: Player, view: JobsView.JobCard, slot: Int) {
        val job = ecoJobs.job(view.jobId) ?: return open(player, view.back)
        when (slot) {
            29 -> open(player, JobsView.Levels(job.id, levelPage(ecoJobs.level(player, job)), view))
            31 -> open(player, JobsView.Leaderboard(job.id, 1, view))
            33 -> open(player, JobsView.Boosts(job.id, 1, view))
            40 -> when {
                ecoJobs.active(player, job) -> open(player, JobsView.LeaveConfirm(job.id, view))
                ecoJobs.join(player, job) -> {
                    player.sendMessage(locale.render("message.joined", player, mapOf("job" to ecoJobs.name(job))))
                    open(player, view)
                }
                else -> player.sendMessage(locale.render("message.join-failed", player, mapOf("job" to ecoJobs.name(job))))
            }
            45 -> open(player, view.back)
            53 -> player.closeInventory()
        }
    }

    private fun openLeave(player: Player, view: JobsView.LeaveConfirm) {
        val job = ecoJobs.job(view.jobId) ?: return open(player, view.back)
        val inventory = inventory(player, view, 27, "menu.leave.title", mapOf("job" to ecoJobs.name(job)))
        inventory.setItem(11, item(Material.RED_CONCRETE, player, "menu.leave.confirm-name", "menu.leave.confirm-lore", mapOf("job" to ecoJobs.name(job))))
        inventory.setItem(15, item(Material.LIME_CONCRETE, player, "menu.leave.cancel-name", "menu.leave.cancel-lore"))
        player.openInventory(inventory)
    }

    private fun clickLeave(player: Player, view: JobsView.LeaveConfirm, slot: Int) {
        val job = ecoJobs.job(view.jobId) ?: return open(player, view.back)
        when (slot) {
            11 -> if (ecoJobs.leave(player, job)) {
                player.sendMessage(locale.render("message.left", player, mapOf("job" to ecoJobs.name(job))))
                open(player, JobsView.JobCard(job.id, (view.back as? JobsView.JobCard)?.back ?: JobsView.Main))
            } else {
                player.sendMessage(locale.render("message.leave-failed", player, mapOf("job" to ecoJobs.name(job))))
                open(player, view.back)
            }
            15 -> open(player, view.back)
        }
    }

    private fun openLevels(player: Player, view: JobsView.Levels) {
        val job = ecoJobs.job(view.jobId) ?: return open(player, view.back)
        val pages = pageCount(job.maxLevel, contentSlots.size)
        val currentView = view.copy(page = view.page.coerceIn(1, pages))
        val inventory = inventory(player, currentView, 54, "menu.levels.title", mapOf("job" to ecoJobs.name(job)))
        val playerLevel = ecoJobs.level(player, job)
        ((currentView.page - 1) * contentSlots.size + 1..minOf(currentView.page * contentSlots.size, job.maxLevel)).forEachIndexed { index, level ->
            val state = when {
                level < playerLevel -> Triple(Material.LIME_DYE, "menu.levels.reached-name", "menu.levels.status-reached")
                level == playerLevel -> Triple(Material.YELLOW_DYE, "menu.levels.current-name", "menu.levels.status-current")
                else -> Triple(Material.RED_DYE, "menu.levels.future-name", "menu.levels.status-future")
            }
            val rewards = ecoJobs.rewards(player, job, level).ifEmpty { listOf(locale.render("menu.levels.no-rewards", player)) }
            val rewardBlock = Component.join(JoinConfiguration.newlines(), rewards)
            inventory.setItem(contentSlots[index], item(state.first, player, state.second, "menu.levels.lore", mapOf(
                "level" to text(level),
                "required" to text(number.format(job.getExpForLevel(level))),
                "rewards" to rewardBlock,
                "status" to locale.render(state.third, player),
            )))
        }
        navigation(inventory, player, currentView.back, currentView.page, pages)
        player.openInventory(inventory)
    }

    private fun openLeaderboardSelector(player: Player, view: JobsView.LeaderboardSelector) {
        val inventory = inventory(player, view, 54, "menu.leaderboard.selector-title")
        inventory.setItem(4, item(Material.NETHER_STAR, player, "menu.leaderboard.global-name", "menu.leaderboard.global-lore"))
        ecoJobs.jobs().take(contentSlots.size).forEachIndexed { index, job ->
            inventory.setItem(contentSlots[index], jobItem(job, player, "menu.leaderboard.job-lore", mapOf("job" to ecoJobs.name(job))))
        }
        navigation(inventory, player, view.back, 1, 1)
        player.openInventory(inventory)
    }

    private fun clickLeaderboardSelector(player: Player, view: JobsView.LeaderboardSelector, slot: Int) {
        if (slot == 4) return open(player, JobsView.Leaderboard(null, 1, view))
        val index = contentSlots.indexOf(slot)
        if (index >= 0) ecoJobs.jobs().getOrNull(index)?.let { return open(player, JobsView.Leaderboard(it.id, 1, view)) }
        if (slot == 45) open(player, view.back) else if (slot == 53) player.closeInventory()
    }

    private fun openLeaderboard(player: Player, view: JobsView.Leaderboard) {
        val job = view.jobId?.let(ecoJobs::job)
        if (view.jobId != null && job == null) return open(player, view.back)
        val rankings = ecoJobs.rankings(job)
        val pageSize = settings().leaderboardEntriesPerPage
        val pages = pageCount(rankings.size, pageSize)
        val currentView = view.copy(page = view.page.coerceIn(1, pages))
        val titlePath = if (job == null) "menu.leaderboard.title-global" else "menu.leaderboard.title-job"
        val inventory = inventory(player, currentView, 54, titlePath, mapOf("job" to (job?.let(ecoJobs::name) ?: Component.empty())))
        rankings.page(currentView.page, pageSize).forEachIndexed { index, entry ->
            val rank = (currentView.page - 1) * pageSize + index + 1
            val offline = Bukkit.getOfflinePlayer(entry.uuid)
            inventory.setItem(contentSlots[index], playerHead(offline, player, "menu.leaderboard.entry-name", if (job == null) "menu.leaderboard.entry-global-lore" else "menu.leaderboard.entry-job-lore", mapOf(
                "rank_color" to rankLabel(rank),
                "rank" to text(rank),
                "player" to text(entry.name),
                "level" to text(entry.level),
                "xp" to text(number.format(entry.xp)),
            )))
        }
        val own = rankings.firstOrNull { it.uuid == player.uniqueId }
        inventory.setItem(40, item(Material.NAME_TAG, player, "menu.leaderboard.self-name", "menu.leaderboard.self-lore", mapOf(
            "rank" to text(ecoJobs.rank(player, job)?.toString() ?: "—"),
            "level" to text(own?.level ?: 0),
            "xp" to text(number.format(own?.xp ?: 0.0)),
        )))
        if (rankings.isEmpty()) inventory.setItem(22, item(Material.GRAY_DYE, player, "menu.leaderboard.empty-name", "menu.leaderboard.empty-lore"))
        navigation(inventory, player, currentView.back, currentView.page, pages)
        player.openInventory(inventory)
    }

    private fun openBoosts(player: Player, view: JobsView.Boosts) {
        val job = view.jobId?.let(ecoJobs::job)
        if (view.jobId != null && job == null) return open(player, view.back)
        val applicable = applicableBoosts(player, view.jobId)
        val pages = pageCount(applicable.size, contentSlots.size)
        val currentView = view.copy(page = view.page.coerceIn(1, pages))
        val inventory = inventory(
            player,
            currentView,
            54,
            if (job == null) "menu.boosts.title" else "menu.boosts.title-job",
            mapOf("job" to (job?.let(ecoJobs::name) ?: Component.empty())),
        )
        val summaryJobs = job?.let { listOf(it.id) } ?: ecoJobs.jobs().map { it.id }
        val summaryXp = summaryJobs.maxOfOrNull { boosts.multiplier(player, it, BoostType.XP) } ?: 1.0
        val summaryMoney = summaryJobs.maxOfOrNull { boosts.multiplier(player, it, BoostType.MONEY) } ?: 1.0
        inventory.setItem(4, item(Material.BEACON, player, "menu.boosts.summary-name", "menu.boosts.summary-lore", mapOf(
            "xp_multiplier" to text(formatMultiplier(summaryXp)),
            "money_multiplier" to text(formatMultiplier(summaryMoney)),
        )))
        applicable.page(currentView.page, contentSlots.size).forEachIndexed { index, boost ->
            inventory.setItem(contentSlots[index], boostItem(player, boost))
        }
        if (applicable.isEmpty()) inventory.setItem(22, item(Material.GRAY_DYE, player, "menu.boosts.empty-name", "menu.boosts.empty-lore"))
        navigation(inventory, player, currentView.back, currentView.page, pages)
        player.openInventory(inventory)
    }

    private fun openHelp(player: Player, view: JobsView.Help) {
        val inventory = inventory(player, view, 45, "menu.help.title")
        inventory.setItem(10, item(Material.COMPASS, player, "menu.help.jobs-name", "menu.help.jobs-lore"))
        inventory.setItem(12, item(Material.REPEATER, player, "menu.help.levels-name", "menu.help.levels-lore"))
        inventory.setItem(14, item(Material.EXPERIENCE_BOTTLE, player, "menu.help.boosts-name", "menu.help.boosts-lore"))
        inventory.setItem(16, item(Material.WRITABLE_BOOK, player, "menu.help.commands-name", "menu.help.commands-lore"))
        inventory.setItem(36, backItem(player))
        inventory.setItem(44, closeItem(player))
        player.openInventory(inventory)
    }

    private fun openAdmin(player: Player, view: JobsView.Admin) {
        if (!player.hasPermission("arcecojobs.admin")) return open(player, view.back)
        val problems = ecoJobs.moneyIntegrationProblems()
        val ok = Component.text("OK", NamedTextColor.GREEN)
        val bad = Component.text(if (problems.isEmpty()) "OK" else problems.joinToString(", "), if (problems.isEmpty()) NamedTextColor.GREEN else NamedTextColor.RED)
        val inventory = inventory(player, view, 45, "menu.admin.title")
        inventory.setItem(4, item(Material.COMPARATOR, player, "menu.admin.status-name", "menu.admin.status-lore", mapOf(
            "ecojobs" to ok,
            "luckperms" to ok,
            "papi" to ok,
            "jobs" to text(ecoJobs.jobs().size),
            "money" to bad,
        )))
        inventory.setItem(20, item(Material.REPEATER, player, "menu.admin.reload-name", "menu.admin.reload-lore"))
        inventory.setItem(22, item(Material.CHEST, player, "menu.admin.presets-name", "menu.admin.presets-lore", mapOf("count" to text(boosters().values().size))))
        inventory.setItem(24, item(Material.COMMAND_BLOCK, player, "menu.admin.help-name", "menu.admin.help-lore"))
        inventory.setItem(36, backItem(player))
        inventory.setItem(44, closeItem(player))
        player.openInventory(inventory)
    }

    private fun clickAdmin(player: Player, view: JobsView.Admin, slot: Int) {
        when (slot) {
            20 -> if (player.hasPermission("arcecojobs.admin.reload")) {
                reload().fold(
                    onSuccess = {
                        player.sendMessage(locale.render("message.reload-ok", player))
                        open(player, view)
                    },
                    onFailure = { failure -> player.sendMessage(locale.render("message.reload-failed", player, mapOf("reason" to text(failure.message ?: "unknown")))) },
                )
            }
            22 -> if (player.hasPermission("arcecojobs.admin.booster")) open(player, JobsView.Presets(1, view))
            24 -> locale.lines("command.admin-help", player).forEach(player::sendMessage)
            36 -> open(player, view.back)
            44 -> player.closeInventory()
        }
    }

    private fun openPresets(player: Player, view: JobsView.Presets) {
        if (!player.hasPermission("arcecojobs.admin.booster")) return open(player, view.back)
        val presets = boosters().values()
        val pages = pageCount(presets.size, contentSlots.size)
        val currentView = view.copy(page = view.page.coerceIn(1, pages))
        val inventory = inventory(player, currentView, 54, "menu.admin.presets-title")
        presets.page(currentView.page, contentSlots.size).forEachIndexed { index, preset ->
            val preview = vouchers.create(preset, player)
            preview.editMeta { meta ->
                meta.lore(locale.lines("menu.admin.preset-lore", player, mapOf(
                    "type" to locale.type(preset.type, player),
                    "multiplier" to text(Multipliers.format(preset.multiplierBasisPoints)),
                    "duration" to text(DurationParser.format(preset.duration)),
                    "jobs" to jobsLabel(preset.jobs, player),
                )).map { it.decoration(TextDecoration.ITALIC, false) })
            }
            inventory.setItem(contentSlots[index], preview)
        }
        navigation(inventory, player, currentView.back, currentView.page, pages)
        player.openInventory(inventory)
    }

    private fun clickPresets(player: Player, view: JobsView.Presets, slot: Int) {
        val presets = boosters().values()
        val index = contentSlots.indexOf(slot)
        if (index >= 0) presets.getOrNull((view.page - 1) * contentSlots.size + index)?.let { preset ->
            val leftovers = player.inventory.addItem(vouchers.create(preset, player))
            if (leftovers.isEmpty()) {
                player.sendMessage(locale.render("message.booster-received", player, mapOf(
                    "amount" to text(1),
                    "item" to locale.render(preset.item.nameKey, player),
                )))
            } else player.sendMessage(locale.render("message.inventory-full", player))
            return
        }
        clickPaged(player, view, slot, pageCount(presets.size, contentSlots.size))
    }

    private fun clickPaged(player: Player, view: JobsView, slot: Int, pages: Int) {
        when (slot) {
            45 -> open(player, when (view) {
                is JobsView.Catalog -> view.back
                is JobsView.Levels -> view.back
                is JobsView.Leaderboard -> view.back
                is JobsView.Boosts -> view.back
                is JobsView.Presets -> view.back
                else -> JobsView.Main
            })
            47 -> when (view) {
                is JobsView.Catalog -> if (view.page > 1) open(player, view.copy(page = view.page - 1))
                is JobsView.Levels -> if (view.page > 1) open(player, view.copy(page = view.page - 1))
                is JobsView.Leaderboard -> if (view.page > 1) open(player, view.copy(page = view.page - 1))
                is JobsView.Boosts -> if (view.page > 1) open(player, view.copy(page = view.page - 1))
                is JobsView.Presets -> if (view.page > 1) open(player, view.copy(page = view.page - 1))
                else -> Unit
            }
            51 -> when (view) {
                is JobsView.Catalog -> if (view.page < pages) open(player, view.copy(page = view.page + 1))
                is JobsView.Levels -> if (view.page < pages) open(player, view.copy(page = view.page + 1))
                is JobsView.Leaderboard -> if (view.page < pages) open(player, view.copy(page = view.page + 1))
                is JobsView.Boosts -> if (view.page < pages) open(player, view.copy(page = view.page + 1))
                is JobsView.Presets -> if (view.page < pages) open(player, view.copy(page = view.page + 1))
                else -> Unit
            }
            53 -> player.closeInventory()
        }
    }

    private fun navigation(inventory: Inventory, player: Player, back: JobsView, page: Int, pages: Int) {
        inventory.setItem(45, backItem(player))
        if (page > 1) inventory.setItem(47, item(Material.ARROW, player, "common.previous-name", "common.previous-lore", mapOf(
            "page" to text(page), "pages" to text(pages),
        )))
        if (page < pages) inventory.setItem(51, item(Material.ARROW, player, "common.next-name", "common.next-lore", mapOf(
            "page" to text(page), "pages" to text(pages),
        )))
        inventory.setItem(53, closeItem(player))
    }

    private fun inventory(
        player: Player,
        view: JobsView,
        size: Int,
        titlePath: String,
        values: Map<String, Component> = emptyMap(),
    ): Inventory {
        val holder = Holder(view)
        val inventory = player.server.createInventory(holder, size, locale.render(titlePath, player, values))
        holder.backing = inventory
        val filler = ItemStack(settings().fillerMaterial).apply {
            editMeta { it.displayName(Component.empty()) }
        }
        repeat(size) { inventory.setItem(it, filler) }
        return inventory
    }

    private fun item(
        material: Material,
        player: Player,
        namePath: String,
        lorePath: String? = null,
        values: Map<String, Component> = emptyMap(),
    ): ItemStack = ItemStack(material).apply {
        editMeta { meta ->
            meta.displayName(locale.render(namePath, player, values).decoration(TextDecoration.ITALIC, false))
            if (lorePath != null) meta.lore(locale.lines(lorePath, player, values).map { it.decoration(TextDecoration.ITALIC, false) })
        }
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

    private fun boostItem(player: Player, boost: BoostInstance): ItemStack = item(
        Material.EXPERIENCE_BOTTLE,
        player,
        "menu.boosts.entry-name",
        "menu.boosts.entry-lore",
        mapOf(
            "multiplier" to text(Multipliers.format(boost.multiplierBasisPoints)),
            "type" to locale.type(boost.type, player),
            "jobs" to jobsLabel(boost.jobs, player),
            "duration" to text(DurationParser.format(Duration.between(Instant.now(), boost.expiresAt).coerceAtLeast(Duration.ZERO))),
            "instance" to text(boost.instanceId.toString().take(8)),
        ),
    )

    private fun applicableBoosts(player: Player, jobId: String?): List<BoostInstance> = boosts.active(player).filter { boost ->
        jobId == null || "all" in boost.jobs || jobId in boost.jobs
    }

    private fun jobsLabel(jobs: Set<String>, player: Player): Component =
        if ("all" in jobs) locale.allJobs(player) else text(jobs.sorted().joinToString(", "))

    private fun backItem(player: Player): ItemStack = item(Material.ARROW, player, "common.back-name", "common.back-lore")
    private fun closeItem(player: Player): ItemStack = item(Material.BARRIER, player, "common.close-name")
    private fun text(value: Any?): Component = locale.text(value)
    private fun formatMultiplier(multiplier: Double): String = Multipliers.format(Multipliers.toBasisPoints(multiplier))
    private fun rankLabel(rank: Int): Component = Component.text("#$rank", when (rank) {
        1 -> NamedTextColor.GOLD
        2 -> NamedTextColor.GRAY
        3 -> NamedTextColor.RED
        else -> NamedTextColor.DARK_GRAY
    })

    private fun levelPage(level: Int): Int = ((level.coerceAtLeast(1) - 1) / contentSlots.size) + 1
    private fun pageCount(size: Int, pageSize: Int): Int = ceil(size.coerceAtLeast(1) / pageSize.toDouble()).toInt().coerceAtLeast(1)
    private fun <T> List<T>.page(page: Int, pageSize: Int): List<T> = drop((page - 1) * pageSize).take(pageSize)
}
