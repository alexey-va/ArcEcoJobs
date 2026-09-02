package ru.ruscrafting.ecojobs.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import com.willfp.ecojobs.jobs.Job
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ecojobs.boost.BoostService
import ru.ruscrafting.ecojobs.boost.VoucherService
import ru.ruscrafting.ecojobs.config.AddonSettings
import ru.ruscrafting.ecojobs.config.BoosterRegistry
import ru.ruscrafting.ecojobs.config.JobsLocale
import ru.ruscrafting.ecojobs.domain.BoostType
import ru.ruscrafting.ecojobs.integration.EcoJobsBridge
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

class JobsMenuMockBukkitTest : StringSpec({
    "main menu renders a complete localized inventory and navigates through real Paper events" {
        MockBukkitTestRuntime.open().use { paper ->
            requireSupportedMockBukkit {
                menuHarness(paper).use { harness ->
                    harness.menu.open(harness.player)

                    val rootView = harness.player.openInventory
                    val root = rootView.topInventory
                    plain(rootView.title()) shouldBe "Работы"
                    rootView.title().color()?.value() shouldBe 0x20252b
                    root.size shouldBe 54
                    root.contents.all { it != null } shouldBe true
                    root.getItem(4)?.type shouldBe Material.PLAYER_HEAD
                    root.getItem(20)?.type shouldBe Material.CRAFTING_TABLE
                    root.getItem(22)?.type shouldBe Material.WRITABLE_BOOK
                    root.getItem(24)?.type shouldBe Material.GOLDEN_HELMET
                    root.getItem(30)?.type shouldBe Material.EXPERIENCE_BOTTLE
                    root.getItem(32)?.type shouldBe Material.KNOWLEDGE_BOOK
                    root.getItem(harness.layouts.slot(JobsView.Main, "back"))?.type shouldBe Material.BLUE_STAINED_GLASS_PANE
                    root.getItem(49)?.type shouldBe Material.GRAY_STAINED_GLASS_PANE
                    root.assertNamedSurfacesAreNonItalic()

                    val catalogClick = clickTop(paper, harness.player, 20)
                    catalogClick.isCancelled shouldBe true
                    harness.player.openInventory.topInventory shouldBe root

                    paper.performTicks(1)

                    val catalogView = harness.player.openInventory
                    plain(catalogView.title()) shouldBe "Каталог профессий"
                    catalogView.topInventory.getItem(22)?.type shouldBe Material.GRAY_DYE
                    catalogView.topInventory.getItem(45)?.type shouldBe Material.BLUE_STAINED_GLASS_PANE
                    catalogView.topInventory.assertNamedSurfacesAreNonItalic()

                    clickTop(paper, harness.player, 45).isCancelled shouldBe true
                    paper.performTicks(1)
                    plain(harness.player.openInventory.title()) shouldBe "Работы"
                }
            }
        }
    }

    "menu owns top, bottom-shift and drag interactions without cancelling unrelated inventories" {
        MockBukkitTestRuntime.open().use { paper ->
            requireSupportedMockBukkit {
                menuHarness(paper).use { harness ->
                    harness.menu.open(harness.player)

                    clickTop(paper, harness.player, 0).isCancelled shouldBe true
                    val shiftClick = clickTop(
                        paper,
                        harness.player,
                        harness.player.openInventory.topInventory.size,
                        ClickType.SHIFT_LEFT,
                        InventoryAction.MOVE_TO_OTHER_INVENTORY,
                    )
                    shiftClick.isShiftClick shouldBe true
                    shiftClick.isCancelled shouldBe true
                    paper.callEvent(
                        InventoryDragEvent(
                            harness.player.openInventory,
                            ItemStack(Material.DIAMOND),
                            ItemStack(Material.AIR),
                            true,
                            mapOf(0 to ItemStack(Material.DIAMOND)),
                        ),
                    ).isCancelled shouldBe true

                    val unrelated = Bukkit.createInventory(null, 9, Component.text("Unrelated"))
                    requireNotNull(harness.player.openInventory(unrelated))
                    clickTop(paper, harness.player, 0).isCancelled shouldBe false
                    paper.callEvent(
                        InventoryDragEvent(
                            harness.player.openInventory,
                            ItemStack(Material.DIAMOND),
                            ItemStack(Material.AIR),
                            true,
                            mapOf(0 to ItemStack(Material.DIAMOND)),
                        ),
                    ).isCancelled shouldBe false
                }
            }
        }
    }

    "admin entry is rendered and routed only while the player holds the permission" {
        MockBukkitTestRuntime.open().use { paper ->
            requireSupportedMockBukkit {
                menuHarness(paper).use { harness ->
                    harness.menu.open(harness.player)
                    harness.player.openInventory.topInventory.getItem(49)?.type shouldBe Material.GRAY_STAINED_GLASS_PANE

                    harness.player.isOp = true
                    harness.menu.open(harness.player)
                    harness.player.openInventory.topInventory.getItem(49)?.type shouldBe Material.COMMAND_BLOCK

                    clickTop(paper, harness.player, 49).isCancelled shouldBe true
                    paper.performTicks(1)

                    plain(harness.player.openInventory.title()) shouldBe "Управление работами"
                    harness.player.openInventory.topInventory.getItem(36)?.type shouldBe Material.BLUE_STAINED_GLASS_PANE
                    harness.player.openInventory.topInventory.assertNamedSurfacesAreNonItalic()
                }
            }
        }
    }

    "unavailable job control is inert and never calls the join API" {
        MockBukkitTestRuntime.open().use { paper ->
            requireSupportedMockBukkit {
                menuHarness(paper).use { harness ->
                    val job = mockk<Job> {
                        every { id } returns "miner"
                        every { maxLevel } returns 100
                        every { getIcon(harness.player) } returns ItemStack(Material.IRON_PICKAXE)
                    }
                    every { harness.ecoJobs.job("miner") } returns job
                    every { harness.ecoJobs.name(job) } returns Component.text("Шахтёр")
                    every { harness.ecoJobs.description(job) } returns Component.text("Добывает руду")
                    every { harness.ecoJobs.active(harness.player, job) } returns false
                    every { harness.ecoJobs.has(harness.player, job) } returns false
                    every { harness.ecoJobs.canJoin(harness.player, job) } returns false
                    every { harness.ecoJobs.level(harness.player, job) } returns 1
                    every { harness.ecoJobs.xp(harness.player, job) } returns 0.0
                    every { harness.ecoJobs.requiredXp(harness.player, job) } returns "100"
                    every { harness.ecoJobs.progress(harness.player, job) } returns 0.0
                    every { harness.ecoJobs.rank(harness.player, job) } returns null
                    every { harness.ecoJobs.onlineWorkers(job) } returns 0

                    harness.menu.open(harness.player, JobsView.JobCard("miner", JobsView.Main))
                    harness.player.openInventory.topInventory.getItem(40)?.type shouldBe Material.GRAY_DYE

                    clickTop(paper, harness.player, 40).isCancelled shouldBe true
                    paper.performTicks(1)

                    verify(exactly = 0) { harness.ecoJobs.join(harness.player, job) }
                    harness.player.openInventory.topInventory.getItem(40)?.type shouldBe Material.GRAY_DYE
                }
            }
        }
    }
})

private class JobsMenuHarness(
    val plugin: JavaPlugin,
    val player: PlayerMock,
    val menu: JobsMenu,
    val ecoJobs: EcoJobsBridge,
    val layouts: JobsMenuLayouts,
    private val dataFolder: Path,
) : AutoCloseable {
    override fun close() {
        dataFolder.toFile().deleteRecursively()
    }
}

private fun menuHarness(paper: MockBukkitTestRuntime): JobsMenuHarness {
    val plugin = paper.createSimplePlugin("ArcEcoJobsMenuTest")
    val player = paper.addPlayer("MenuQA")
    player.setLocale(Locale.forLanguageTag("ru-RU"))
    val project = Path.of(System.getProperty("arcecojobs.projectDir"))
    val dataFolder = Files.createTempDirectory("arcecojobs-menu-")
    val langFolder = Files.createDirectories(dataFolder.resolve("lang"))
    listOf("ru", "en").forEach { language ->
        Files.copy(
            project.resolve("src/main/resources/lang/$language.yml"),
            langFolder.resolve("$language.yml"),
        )
    }
    Files.copy(project.resolve("src/main/resources/config.yml"), dataFolder.resolve("config.yml"))
    val settings = AddonSettings.load(dataFolder.resolve("config.yml").toFile())
    val layouts = JobsMenuLayouts(dataFolder)
    val locale = JobsLocale(dataFolder.toFile()) { settings }.also(JobsLocale::validate)
    val ecoJobs = spyk(EcoJobsBridge(plugin) { settings }) {
        every { activeJobs(player) } returns emptyList()
        every { limit(player) } returns 3
        every { totalLevel(player) } returns 0
        every { jobs() } returns emptyList()
        every { moneyIntegrationProblems(false) } returns emptyList()
    }
    val boosts = mockk<BoostService> {
        every { active(player) } returns emptyList()
        every { multiplier(player, any(), any<BoostType>()) } returns 1.0
    }
    val boosters = mockk<BoosterRegistry> {
        every { values() } returns emptyList()
    }
    val vouchers = mockk<VoucherService>(relaxed = true)
    val menu = JobsMenu(
        plugin = plugin,
        settings = { settings },
        locale = locale,
        ecoJobs = ecoJobs,
        boosts = boosts,
        boosters = { boosters },
        vouchers = vouchers,
        earnings = { null },
        reload = { Result.success(Unit) },
        layouts = layouts,
    )
    plugin.server.pluginManager.registerEvents(
        JobsListener({ settings }, locale, menu, boosts, vouchers),
        plugin,
    )
    return JobsMenuHarness(plugin, player, menu, ecoJobs, layouts, dataFolder)
}

private fun clickTop(
    paper: MockBukkitTestRuntime,
    player: PlayerMock,
    rawSlot: Int,
    click: ClickType = ClickType.LEFT,
    action: InventoryAction = if (rawSlot < player.openInventory.topInventory.size) {
        InventoryAction.PICKUP_ALL
    } else {
        InventoryAction.MOVE_TO_OTHER_INVENTORY
    },
): InventoryClickEvent = paper.callEvent(
    InventoryClickEvent(
        player.openInventory,
        InventoryType.SlotType.CONTAINER,
        rawSlot,
        click,
        action,
    ),
)

private fun Inventory.assertNamedSurfacesAreNonItalic() {
    contents.filterNotNull().forEach { item ->
        val meta = item.itemMeta
        val name = meta.displayName()
        if (name != null && plain(name).isNotEmpty()) {
            name.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            name.assertNoExplicitItalicChildren()
        }
        meta.lore().orEmpty().forEach { line ->
            line.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            line.assertNoExplicitItalicChildren()
        }
    }
}

private fun Component.assertNoExplicitItalicChildren() {
    children().forEach { child ->
        child.decoration(TextDecoration.ITALIC) shouldNotBe TextDecoration.State.TRUE
        child.assertNoExplicitItalicChildren()
    }
}

private fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)
