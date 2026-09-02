package ru.ruscrafting.ecojobs.paper

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import ru.arc.config.Config
import ru.arc.menu.MenuCatalog
import ru.arc.menu.MenuCatalogRepository
import ru.arc.menu.MenuContract
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.menu.MenuLayoutParser
import ru.arc.menu.MenuRegionId
import java.nio.file.Path

/** Validated semantic topology for every ArcEcoJobs inventory view. */
class JobsMenuLayouts(dataRoot: Path) {
    private val repository = MenuCatalogRepository(loadConfiguration(dataRoot))

    fun prepare(dataRoot: Path): MenuCatalog = loadConfiguration(dataRoot)

    fun replace(candidate: MenuCatalog) {
        repository.replace(candidate)
    }

    fun create(holder: InventoryHolder, view: JobsView, title: Component): Inventory =
        Bukkit.createInventory(holder, repository.current().require(menu(view)).rows * 9, title)

    fun slot(view: JobsView, element: String): Int =
        repository.current().require(menu(view)).slot(MenuElementId.of(element)).index

    fun region(view: JobsView, region: String): List<Int> =
        repository.current().require(menu(view)).region(MenuRegionId.of(region)).map { it.index }

    companion object {
        val MAIN = MenuId.of("main")
        val CATALOG = MenuId.of("catalog")
        val JOB = MenuId.of("job")
        val LEAVE = MenuId.of("leave")
        val LEVELS = MenuId.of("levels")
        val EARNINGS = MenuId.of("earnings")
        val EARNINGS_HOURS = MenuId.of("earnings-hours")
        val LEADERBOARD_SELECTOR = MenuId.of("leaderboard-selector")
        val LEADERBOARD = MenuId.of("leaderboard")
        val BOOSTS = MenuId.of("boosts")
        val HELP = MenuId.of("help")
        val ADMIN = MenuId.of("admin")
        val PRESETS = MenuId.of("presets")

        private fun elements(vararg values: String) = values.mapTo(linkedSetOf(), MenuElementId::of)
        private fun regions(vararg values: String) = values.mapTo(linkedSetOf(), MenuRegionId::of)

        val CONTRACTS_BY_MENU = linkedMapOf(
            MAIN to MenuContract(requiredElements = elements("profile", "catalog", "active", "leaderboard", "boosts", "help", "back", "admin")),
            CATALOG to pageable(elements("empty")),
            JOB to MenuContract(requiredElements = elements("overview", "earnings", "scale", "leaderboard", "boosts", "action", "back")),
            LEAVE to MenuContract(requiredElements = elements("confirm", "cancel")),
            LEVELS to pageable(),
            EARNINGS to pageable(elements("status", "summary")),
            EARNINGS_HOURS to MenuContract(requiredElements = elements("status", "summary", "back"), requiredRegions = regions("content")),
            LEADERBOARD_SELECTOR to pageable(elements("global")),
            LEADERBOARD to pageable(elements("status", "self")),
            BOOSTS to pageable(elements("summary", "empty")),
            HELP to MenuContract(requiredElements = elements("jobs", "levels", "boosts", "commands", "back")),
            ADMIN to MenuContract(requiredElements = elements("status", "reload", "presets", "help", "back")),
            PRESETS to pageable(),
        )

        fun menu(view: JobsView): MenuId = when (view) {
            JobsView.Main -> MAIN
            is JobsView.Catalog -> CATALOG
            is JobsView.JobCard -> JOB
            is JobsView.LeaveConfirm -> LEAVE
            is JobsView.Levels -> LEVELS
            is JobsView.Earnings -> EARNINGS
            is JobsView.EarningsHours -> EARNINGS_HOURS
            is JobsView.LeaderboardSelector -> LEADERBOARD_SELECTOR
            is JobsView.Leaderboard -> LEADERBOARD
            is JobsView.Boosts -> BOOSTS
            is JobsView.Help -> HELP
            is JobsView.Admin -> ADMIN
            is JobsView.Presets -> PRESETS
        }

        fun loadConfiguration(dataRoot: Path): MenuCatalog =
            MenuLayoutParser.require(Config(dataRoot, "config.yml"), "gui.layouts", CONTRACTS_BY_MENU).also { catalog ->
                mapOf(
                    CATALOG to 1,
                    LEVELS to 1,
                    EARNINGS to 1,
                    EARNINGS_HOURS to 24,
                    LEADERBOARD_SELECTOR to 1,
                    LEADERBOARD to 28,
                    BOOSTS to 1,
                    PRESETS to 1,
                ).forEach { (menu, minimum) ->
                    val actual = catalog.require(menu).region(MenuRegionId.of("content")).size
                    require(actual >= minimum) {
                        "ArcEcoJobs menu '$menu' region 'content' needs at least $minimum slots, got $actual"
                    }
                }
            }

        private fun pageable(extra: Set<MenuElementId> = emptySet()) = MenuContract(
            requiredElements = elements("back", "previous", "next") + extra,
            requiredRegions = regions("content"),
        )
    }
}
