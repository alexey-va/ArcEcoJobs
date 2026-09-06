package ru.ruscrafting.ecojobs.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.just
import io.mockk.Runs
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogClickContext
import ru.ruscrafting.ecojobs.earnings.EarningsService
import ru.ruscrafting.ecojobs.earnings.EarningsReport
import ru.ruscrafting.ecojobs.earnings.HourlyEarnings
import ru.ruscrafting.ecojobs.earnings.EarningsTotals
import java.time.ZoneId
import java.time.Instant
import java.math.BigDecimal
import java.util.concurrent.CompletableFuture
import org.bukkit.configuration.file.YamlConfiguration
import net.kyori.adventure.text.minimessage.MiniMessage
import ru.ruscrafting.ecojobs.config.JobsMenuPresentation
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
import org.bukkit.event.inventory.InventoryCloseEvent
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
                    harness.player.openInventory(Bukkit.createInventory(null, 9, Component.text("Other menu")))
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

                    val unsafeSwap = clickTop(
                        paper,
                        harness.player,
                        20,
                        ClickType.NUMBER_KEY,
                        InventoryAction.HOTBAR_SWAP,
                    )
                    unsafeSwap.isCancelled shouldBe true
                    paper.performTicks(1)
                    harness.player.openInventory.topInventory shouldBe root

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

    "native dialogs navigate, show details, invalidate stale buttons and keep inventory as an explicit fallback" {
        MockBukkitTestRuntime.open().use { paper ->
            val screens = mutableListOf<PaperDialogScreen>()
            menuHarness(paper, JobsMenuPresentation.DIALOG) { _, screen -> screens += screen }.use { h ->
                h.menu.openRoot(h.player)
                val root = screens.last()
                root.id shouldBe "ecojobs.main"
                plain(root.title) shouldBe "Работы"
                root.title.color()?.value() shouldBe 0xf4bd6a
                root.buttons.map { it.id.value } shouldBe listOf("catalog", "active", "leaderboard", "boosts", "help", "back")
                root.exitButton?.id?.value shouldBe "close"
                root.canCloseWithEscape shouldBe true
                (h.player.openInventory.topInventory?.type == InventoryType.CHEST) shouldBe false
                root.body.joinToString { plain(it.text) }.contains("MenuQA") shouldBe true
                fun click(screen: PaperDialogScreen, id: String) {
                    (screen.buttons + listOfNotNull(screen.exitButton)).single { it.id.value == id }
                        .onClick.handle(mockk<PaperDialogClickContext>(relaxed = true))
                    paper.performTicks(1)
                }
                click(root, "catalog")
                screens.last().id shouldBe "ecojobs.catalog"
                screens.last().body.joinToString { plain(it.text) }.contains("Нет активных") shouldBe true
                val count = screens.size
                click(root, "boosts")
                screens.size shouldBe count
                click(screens.last(), "back")
                click(screens.last(), "help")
                screens.last().id shouldBe "ecojobs.help"
                click(screens.last(), "info_jobs")
                screens.last().id shouldBe "ecojobs.help.detail"
                screens.last().body.isNotEmpty() shouldBe true
                click(screens.last(), "detail_back")
                screens.last().id shouldBe "ecojobs.help"
                val old = screens.last()
                click(old, "close")
                click(old, "back")
                screens.last() shouldBe old
                h.menu.openRoot(h.player, JobsMenuPresentation.INVENTORY)
                plain(h.player.openInventory.title()) shouldBe "Работы"
                clickTop(paper, h.player, h.layouts.slot(JobsView.Main, "catalog"))
                paper.performTicks(1)
                plain(h.player.openInventory.title()) shouldBe "Каталог профессий"
                h.menu.openRoot(h.player)
                screens.last().id shouldBe "ecojobs.main"
                (h.player.openInventory.topInventory?.type == InventoryType.CHEST) shouldBe false
                screens.forEach { screen ->
                    (listOf(screen.title) + screen.body.map { it.text } + screen.buttons.flatMap { listOf(it.label, it.tooltip) })
                        .forEach { it.assertNoExplicitItalicChildren() }
                }
            }
        }
    }

    "Escape defaults to Back and honors explicit Close with a native footer" {
        MockBukkitTestRuntime.open().use { paper ->
            val screens = mutableListOf<PaperDialogScreen>()
            // Missing metadata and every value except explicit "close" resolve to Back.
            var back = true
            menuHarness(paper, JobsMenuPresentation.DIALOG, escapeBack = { back }) { _, screen -> screens += screen }.use { h ->
                val job = mockJob(h)
                every { h.ecoJobs.jobs() } returns listOf(job)
                h.menu.openRoot(h.player)
                // Direct NPC/command entry has no synthetic parent: Escape closes it.
                screens.last().exitButton?.id?.value shouldBe "close"
                screens.last().buttons.any { it.id.value == "back" } shouldBe true
                fun click(id: String) {
                    val screen = screens.last()
                    (screen.buttons + listOfNotNull(screen.exitButton)).single { it.id.value == id }
                        .onClick.handle(mockk(relaxed = true))
                    paper.performTicks(1)
                }
                click("catalog")
                click("content_0")
                click("scale")
                click("next")
                click("info_content_0")
                screens.last().exitButton?.id?.value shouldBe "detail_back"
                click("detail_back")
                screens.last().buttons.any { it.id.value == "previous" } shouldBe true
                click("back")
                screens.last().id shouldBe "ecojobs.job"
                click("back")
                screens.last().id shouldBe "ecojobs.catalog"
                verify(exactly = 0) { h.player.closeInventory() }
                back = false
                click("back")
                screens.last().exitButton?.id?.value shouldBe "close"
                screens.last().buttons.any { it.id.value == "back" } shouldBe true
                screens.all { it.canCloseWithEscape && it.exitButton != null } shouldBe true
                screens.forEach { screen ->
                    screen.buttons.map { it.width }.distinct().size shouldBe 1
                    screen.exitButton!!.width shouldBe 200
                }
                val last = screens.last()
                click("close")
                last.exitButton!!.onClick.handle(mockk(relaxed = true))
                screens.last() shouldBe last
            }
        }
    }

    "inventory Escape closes direct shop entry and returns nested shop history including page" {
        MockBukkitTestRuntime.open().use { paper ->
            menuHarness(paper, JobsMenuPresentation.INVENTORY, escapeBack = { true }).use { h ->
                val project = Path.of(System.getProperty("arcecojobs.projectDir"))
                val registry = BoosterRegistry.load(
                    project.resolve("src/main/resources/boosters.yml").toFile(),
                    AddonSettings.load(project.resolve("src/main/resources/config.yml").toFile()),
                    setOf("miner"),
                )
                val preset = registry.values().first()
                val offers = List(29) { preset }
                every { h.boosters.values() } returns offers
                every { h.boosters.get(preset.id) } returns preset
                every { h.vouchers.create(any(), h.player, shopPreview = true) } returns ItemStack(Material.PAPER)

                h.menu.openShop(h.player)
                h.menu.open(h.player, JobsView.Shop(2, JobsView.Main))
                h.menu.open(h.player, JobsView.ShopConfirm(preset, JobsView.Shop(2, JobsView.Main)))
                paper.callEvent(InventoryCloseEvent(h.player.openInventory, InventoryCloseEvent.Reason.PLAYER))
                paper.performTicks(1)
                h.player.openInventory.topInventory.getItem(h.layouts.slot(JobsView.Shop(2, JobsView.Main), "previous")) shouldNotBe null
                plain(h.player.openInventory.title()) shouldBe "Магазин усилителей"
                paper.callEvent(InventoryCloseEvent(h.player.openInventory, InventoryCloseEvent.Reason.PLAYER))
                paper.performTicks(1)
                runCatching { plain(h.player.openInventory.title()) }.getOrNull() shouldNotBe "Работы"

                // A new screen opened before the deferred return runs owns the next tick.
                h.menu.open(h.player, JobsView.ShopConfirm(preset, JobsView.Shop(2, JobsView.Main)))
                paper.callEvent(InventoryCloseEvent(h.player.openInventory, InventoryCloseEvent.Reason.PLAYER))
                h.menu.open(h.player, JobsView.Main)
                paper.performTicks(1)
                plain(h.player.openInventory.title()) shouldBe "Работы"

                h.menu.open(h.player, JobsView.ShopConfirm(preset, JobsView.Shop(2, JobsView.Main)))
                paper.callEvent(InventoryCloseEvent(h.player.openInventory, InventoryCloseEvent.Reason.PLUGIN))
                paper.performTicks(1)
                runCatching { plain(h.player.openInventory.title()) }.getOrNull() shouldNotBe "Магазин усилителей"

                h.menu.open(h.player, JobsView.ShopConfirm(preset, JobsView.Shop(2, JobsView.Main)))
                paper.callEvent(InventoryCloseEvent(h.player.openInventory, InventoryCloseEvent.Reason.DISCONNECT))
                paper.performTicks(1)
                runCatching { plain(h.player.openInventory.title()) }.getOrNull() shouldNotBe "Магазин усилителей"
            }
        }
    }

    "dialog join is consumed once and leaving still requires confirmation with cancellation" {
        MockBukkitTestRuntime.open().use { paper ->
            val screens = mutableListOf<PaperDialogScreen>()
            menuHarness(paper, JobsMenuPresentation.DIALOG) { _, screen -> screens += screen }.use { h ->
                val job = mockJob(h)
                every { h.ecoJobs.jobs() } returns listOf(job)
                var active = false
                every { h.ecoJobs.active(h.player, job) } answers { active }
                every { h.ecoJobs.canJoin(h.player, job) } returns true
                every { h.ecoJobs.join(h.player, job) } answers { active = true; true }
                every { h.ecoJobs.leave(h.player, job) } answers { active = false; true }
                fun click(id: String, twice: Boolean = false) {
                    val button = (screens.last().buttons + listOfNotNull(screens.last().exitButton)).single { it.id.value == id }
                    button.onClick.handle(mockk(relaxed = true))
                    if (twice) button.onClick.handle(mockk(relaxed = true))
                    paper.performTicks(1)
                }
                h.menu.openRoot(h.player)
                click("catalog")
                click("content_0")
                screens.last().id shouldBe "ecojobs.job"
                click("action", twice = true)
                verify(exactly = 1) { h.ecoJobs.join(h.player, job) }
                click("action")
                screens.last().id shouldBe "ecojobs.leave"
                screens.last().body.joinToString { plain(it.text) }.contains("Шахтёр") shouldBe true
                click("cancel")
                verify(exactly = 0) { h.ecoJobs.leave(h.player, job) }
                click("action")
                click("confirm", twice = true)
                verify(exactly = 1) { h.ecoJobs.leave(h.player, job) }
                screens.last().id shouldBe "ecojobs.job"
                every { h.ecoJobs.canJoin(h.player, job) } returns false
                h.menu.open(h.player, JobsView.JobCard("miner", JobsView.Main))
                screens.last().buttons.none { it.id.value == "action" } shouldBe true
                click("scale")
                screens.last().id shouldBe "ecojobs.levels"
                click("info_content_0")
                screens.last().id shouldBe "ecojobs.levels.detail"
                click("detail_back")
                click("next")
                screens.last().buttons.any { it.id.value == "previous" } shouldBe true
            }
        }
    }

    "rankings and boosts retain details and preset issuance rechecks permission at click time" {
        MockBukkitTestRuntime.open().use { paper ->
            val screens = mutableListOf<PaperDialogScreen>()
            menuHarness(paper, JobsMenuPresentation.DIALOG) { _, screen -> screens += screen }.use { h ->
                fun click(id: String) {
                    (screens.last().buttons + listOfNotNull(screens.last().exitButton)).single { it.id.value == id }.onClick.handle(mockk(relaxed = true))
                    paper.performTicks(1)
                }
                val rankings = (1..10).map { index -> ru.ruscrafting.ecojobs.integration.RankingEntry(
                    java.util.UUID.randomUUID(), "Player$index", 101 - index, 100.0,
                ) }
                every { h.ecoJobs.rankings(null) } returns rankings
                h.menu.open(h.player, JobsView.Leaderboard(null, 1, JobsView.Main))
                screens.last().buttons.count { it.id.value.startsWith("info_content_") } shouldBe 8
                click("info_content_0")
                plain(screens.last().title).contains("Player1") shouldBe true
                click("detail_back")
                click("next")
                screens.last().buttons.count { it.id.value.startsWith("info_content_") } shouldBe 2
                val boost = ru.ruscrafting.ecojobs.domain.BoostInstance(java.util.UUID.randomUUID(),
                    BoostType.XP, 150, setOf("all"), Instant.now().plusSeconds(3600))
                every { h.boosts.active(h.player) } returns listOf(boost)
                h.menu.open(h.player, JobsView.Boosts(null, 1, JobsView.Main))
                click("info_content_0")
                screens.last().body.joinToString { plain(it.text) }.contains("Все профессии") shouldBe true
                val project = Path.of(System.getProperty("arcecojobs.projectDir"))
                val registry = BoosterRegistry.load(project.resolve("src/main/resources/boosters.yml").toFile(),
                    AddonSettings.load(project.resolve("src/main/resources/config.yml").toFile()), setOf("miner"))
                val preset = registry.values().first()
                every { h.boosters.values() } returns listOf(preset)
                every { h.vouchers.create(preset, h.player) } returns ItemStack(Material.PAPER).apply {
                    editMeta { it.displayName(Component.text("Талон")) }
                }
                h.player.isOp = true
                h.menu.open(h.player, JobsView.Presets(1, JobsView.Main))
                click("content_0")
                h.player.inventory.contents.filterNotNull().filter { it.type == Material.PAPER }.sumOf { it.amount } shouldBe 1
                h.player.isOp = false
                click("content_0")
                h.player.inventory.contents.filterNotNull().filter { it.type == Material.PAPER }.sumOf { it.amount } shouldBe 1
                screens.last().id shouldBe "ecojobs.main"
            }
        }
    }

    "earnings dialogs load days and hours but never revive closed or superseded visits" {
        MockBukkitTestRuntime.open().use { paper ->
            val screens = mutableListOf<PaperDialogScreen>()
            val requests = mutableListOf<CompletableFuture<EarningsReport>>()
            val service = mockk<EarningsService> {
                every { report(any(), any()) } answers {
                    CompletableFuture<EarningsReport>().also { requests += it }
                }
            }
            menuHarness(paper, JobsMenuPresentation.DIALOG, service) { _, screen -> screens += screen }.use { h ->
                mockJob(h)
                val view = JobsView.Earnings("miner", 1, JobsView.Main)
                val report = EarningsReport(listOf(HourlyEarnings(h.player.uniqueId, "miner",
                    Instant.now().epochSecond / 3600, EarningsTotals(BigDecimal("1234.5"), BigDecimal("250")))),
                    ZoneId.of("Europe/Moscow"))
                fun click(id: String) {
                    (screens.last().buttons + listOfNotNull(screens.last().exitButton)).single { it.id.value == id }.onClick.handle(mockk(relaxed = true))
                    paper.performTicks(1)
                }
                h.menu.open(h.player, view)
                screens.last().id shouldBe "ecojobs.earnings"
                click("close")
                val closedCount = screens.size
                requests.last().complete(report)
                paper.performTicks(1)
                screens.size shouldBe closedCount
                h.menu.open(h.player, view)
                val old = requests.last()
                h.menu.open(h.player, view)
                val current = requests.last()
                val reopenCount = screens.size
                old.complete(report)
                paper.performTicks(1)
                screens.size shouldBe reopenCount
                current.complete(report)
                paper.performTicks(1)
                screens.last().buttons.any { it.id.value == "content_0" } shouldBe true
                screens.last().body.joinToString { plain(it.text) }.contains("1,234.5") shouldBe true
                click("content_0")
                screens.last().id shouldBe "ecojobs.earnings-hours"
                requests.last().complete(report)
                paper.performTicks(1)
                screens.last().buttons.count { it.id.value.startsWith("info_content_") } shouldBe 24
                click("info_content_0")
                screens.last().id shouldBe "ecojobs.earnings-hours.detail"
                click("detail_back")
                click("back")
                requests.last().completeExceptionally(IllegalStateException("unavailable"))
                paper.performTicks(1)
                screens.last().body.joinToString { plain(it.text) }.contains("недоступ") shouldBe true
                click("back")
                screens.last().id shouldBe "ecojobs.main"
            }
        }
    }

    "failed dialog actions rearm navigation and revoked admin access cannot open presets" {
        MockBukkitTestRuntime.open().use { paper ->
            val screens = mutableListOf<PaperDialogScreen>()
            menuHarness(paper, JobsMenuPresentation.DIALOG) { _, screen -> screens += screen }.use { h ->
                val job = mockJob(h)
                every { h.ecoJobs.canJoin(h.player, job) } returns true
                every { h.ecoJobs.join(h.player, job) } returns false
                h.menu.open(h.player, JobsView.JobCard("miner", JobsView.Main))
                val before = screens.last()
                before.buttons.single { it.id.value == "action" }.onClick.handle(mockk(relaxed = true))
                paper.performTicks(1)
                (screens.last() === before) shouldBe false
                (screens.last().buttons + listOfNotNull(screens.last().exitButton)).single { it.id.value == "back" }.onClick.handle(mockk(relaxed = true))
                paper.performTicks(1)
                screens.last().id shouldBe "ecojobs.main"
                h.player.isOp = true
                h.menu.open(h.player, JobsView.Admin(JobsView.Main))
                val admin = screens.last()
                h.player.isOp = false
                admin.buttons.single { it.id.value == "presets" }.onClick.handle(mockk(relaxed = true))
                paper.performTicks(1)
                screens.last().id shouldBe "ecojobs.main"
            }
        }
    }
    "admin forms share command validation, require confirmation and consume mutations once" {
        MockBukkitTestRuntime.open().use { paper ->
            val screens = mutableListOf<PaperDialogScreen>()
            menuHarness(paper, JobsMenuPresentation.DIALOG, escapeBack = { true }) { _, screen -> screens += screen }.use { h ->
                listOf("boost", "booster", "reload", "diagnose").forEach { h.player.addAttachment(h.plugin, "arcecojobs.admin.$it", true) }
                val settings = AddonSettings.load(h.dataFolder.resolve("config.yml").toFile())
                val locale = JobsLocale(h.dataFolder.toFile()) { settings }
                var reloads = 0
                val command = JobsCommand({ settings }, locale, h.ecoJobs, h.boosts, { h.boosters }, h.vouchers, h.menu, { reloads++; Result.success(Unit) })
                h.menu.installAdminActions(command::executeAdmin)
                fun click(id: String, values: Map<String, String> = emptyMap()) {
                    val screen = screens.last()
                    val context = mockk<PaperDialogClickContext> {
                        every { text(any()) } answers { values[firstArg<String>()] }
                    }
                    (screen.buttons + listOfNotNull(screen.exitButton)).single { it.id.value == id }.onClick.handle(context)
                    paper.performTicks(1)
                }
                h.menu.openRoot(h.player, JobsMenuPresentation.DIALOG)
                click("admin")
                screens.last().id shouldBe "ecojobs.admin.root"
                command.onCommand(h.player, mockk(), "arcjobs", arrayOf("help")) shouldBe true
                screens.last().buttons.map { it.id.value }.containsAll(listOf("presets", "give", "hand", "list", "grant", "revoke", "diagnose", "reload")) shouldBe true
                screens.last().exitButton!!.id.value shouldBe "back"
                click("grant")
                screens.last().inputs.size shouldBe 5
                val values = mapOf("player" to h.player.name, "duration" to "2h", "multiplier" to "1.5", "type" to "ALL", "jobs" to "all")
                click("review", values)
                screens.last().id shouldBe "ecojobs.admin.confirm"
                verify(exactly = 0) { h.boosts.grantDetailed(any(), any(), any(), any(), any(), any()) }
                click("back")
                screens.last().inputs.associate { it.id.value to it.initial } shouldBe values
                click("review", values + ("duration" to "invalid"))
                click("confirm")
                screens.last().id shouldBe "ecojobs.admin.result"
                screens.last().body.joinToString { plain(it.text) }.contains("Некорректный срок") shouldBe true
                verify(exactly = 0) { h.boosts.grantDetailed(any(), any(), any(), any(), any(), any()) }
                click("back")
                click("review", values)
                every { h.boosts.grantDetailed(any(), any(), any(), any(), any(), any()) } answers {
                    lastArg<(ru.ruscrafting.ecojobs.boost.GrantOutcome) -> Unit>().invoke(
                        ru.ruscrafting.ecojobs.boost.GrantOutcome(ru.ruscrafting.ecojobs.boost.GrantResult.GRANTED, java.time.Duration.ofHours(2)))
                }
                val confirm = screens.last().buttons.single { it.id.value == "confirm" }
                confirm.onClick.handle(mockk(relaxed = true))
                confirm.onClick.handle(mockk(relaxed = true))
                val playerId = h.player.uniqueId
                verify(exactly = 1) { h.boosts.grantDetailed(playerId, BoostType.ALL, 150, java.time.Duration.ofHours(2), setOf("all"), any()) }
                screens.last().body.joinToString { plain(it.text) }.contains("выдано усиление") shouldBe true
                h.menu.openAdminCommand(h.player, listOf("reload"))
                reloads shouldBe 0
                click("confirm")
                reloads shouldBe 1
                screens.last().id shouldBe "ecojobs.admin.result"
                h.menu.openAdminCommand(h.player, listOf("admin"))
                click("give")
                click("continue", mapOf("player" to h.player.name, "preset" to "workday", "amount" to "2"))
                screens.last().id shouldBe "ecojobs.admin.give_options"
                click("continue", mapOf("duration" to "1h", "multiplier" to "2", "type" to "XP", "jobs" to "all"))
                screens.last().id shouldBe "ecojobs.admin.confirm"
                click("back")
                screens.last().inputs.single { it.id.value == "duration" }.initial shouldBe "1h"
                val registry = BoosterRegistry.load(Path.of(System.getProperty("arcecojobs.projectDir"), "src/main/resources/boosters.yml").toFile(), settings, setOf("miner"))
                val preset = registry.values().first()
                every { h.boosters.get(preset.id) } returns preset
                every { h.boosters.values() } returns listOf(preset)
                every { h.vouchers.create(preset, h.player, any()) } returns ItemStack(Material.PAPER)
                h.menu.openAdminCommand(h.player, listOf("booster", "give", h.player.name, preset.id, "2", "--invalid", "1h"))
                screens.last().body.joinToString { plain(it.text) }.contains("Неизвестная команда") shouldBe true
                verify(exactly = 0) { h.vouchers.create(any(), any(), any()) }
                h.menu.openAdminCommand(h.player, listOf("booster", "give", h.player.name, preset.id, "2", "--duration", "1h", "--type", "XP"))
                click("continue", mapOf("player" to h.player.name, "preset" to preset.id, "amount" to "2"))
                screens.last().inputs.single { it.id.value == "duration" }.initial shouldBe "1h"
                click("continue", mapOf("duration" to "1h", "type" to "XP"))
                val voucherConfirm = screens.last().buttons.single { it.id.value == "confirm" }
                voucherConfirm.onClick.handle(mockk(relaxed = true))
                voucherConfirm.onClick.handle(mockk(relaxed = true))
                h.player.inventory.contents.filterNotNull().filter { it.type == Material.PAPER }.sumOf { it.amount } shouldBe 2
                verify(exactly = 2) { h.vouchers.create(preset, any(), ru.ruscrafting.ecojobs.boost.VoucherOverrides(java.time.Duration.ofHours(1), null, BoostType.XP, null)) }
                h.menu.openAdminCommand(h.player, listOf("booster", "inspect", preset.id))
                screens.last().id shouldBe "ecojobs.admin.result"
                h.menu.openAdminCommand(h.player, listOf("boost", "revoke", h.player.name, "all"))
                click("review", mapOf("player" to h.player.name, "instance" to "all"))
                h.player.addAttachment(h.plugin, "arcecojobs.admin.boost", false)
                click("confirm")
                screens.last().id shouldBe "ecojobs.admin.denied"
                verify(exactly = 0) { h.boosts.revoke(any(), any(), any()) }
                screens.forEach { screen -> screen.buttons.map { it.width }.distinct().size shouldBe 1 }
                verify(exactly = 0) { h.player.sendMessage(any<Component>()) }
                verify(exactly = 0) { h.player.closeInventory() }
            }
        }
    }

    "admin async results cannot reopen after Escape or a player menu command" {
        MockBukkitTestRuntime.open().use { paper ->
            val screens = mutableListOf<PaperDialogScreen>()
            menuHarness(paper, JobsMenuPresentation.DIALOG) { _, screen -> screens += screen }.use { h ->
                h.player.addAttachment(h.plugin, "arcecojobs.admin.boost", true)
                val settings = AddonSettings.load(h.dataFolder.resolve("config.yml").toFile())
                val locale = JobsLocale(h.dataFolder.toFile()) { settings }
                val command = JobsCommand({ settings }, locale, h.ecoJobs, h.boosts, { h.boosters }, h.vouchers, h.menu, { Result.success(Unit) })
                val replies = mutableListOf<(List<ru.ruscrafting.ecojobs.domain.BoostInstance>?, Throwable?) -> Unit>()
                every { h.boosts.loadActive(any(), any()) } answers { replies += lastArg<(List<ru.ruscrafting.ecojobs.domain.BoostInstance>?, Throwable?) -> Unit>() }
                h.menu.installAdminActions(command::executeAdmin)
                fun list() {
                    h.menu.openAdminCommand(h.player, listOf("boost", "list"))
                    val context = mockk<PaperDialogClickContext> { every { text(any()) } returns h.player.name }
                    screens.last().buttons.single { it.id.value == "show" }.onClick.handle(context)
                    screens.last().id shouldBe "ecojobs.admin.loading"
                }
                list()
                screens.last().exitButton!!.id.value shouldBe "close"
                screens.last().exitButton!!.onClick.handle(mockk(relaxed = true))
                val closed = screens.size
                replies.last()(emptyList(), null)
                screens.size shouldBe closed
                list()
                h.menu.openRoot(h.player, JobsMenuPresentation.DIALOG)
                val replaced = screens.size
                replies.last()(emptyList(), null)
                screens.size shouldBe replaced
                screens.last().id shouldBe "ecojobs.main"
                h.player.addAttachment(h.plugin, "arcecojobs.admin.boost", false)
                h.menu.openAdminCommand(h.player, listOf("admin"))
                screens.last().id shouldBe "ecojobs.admin.denied"
            }
        }
    }

})

private fun mockJob(h: JobsMenuHarness): Job {
    val job = mockk<Job> {
        every { id } returns "miner"
        every { maxLevel } returns 100
        every { getIcon(h.player) } returns ItemStack(Material.IRON_PICKAXE)
        every { getExpForLevel(any()) } returns 100.0
    }
    every { h.ecoJobs.job("miner") } returns job
    every { h.ecoJobs.name(job) } returns Component.text("Шахтёр")
    every { h.ecoJobs.description(job) } returns Component.text("Добывает руду")
    every { h.ecoJobs.active(h.player, job) } returns false
    every { h.ecoJobs.has(h.player, job) } returns true
    every { h.ecoJobs.canJoin(h.player, job) } returns false
    every { h.ecoJobs.level(h.player, job) } returns 1
    every { h.ecoJobs.xp(h.player, job) } returns 0.0
    every { h.ecoJobs.requiredXp(h.player, job) } returns "100"
    every { h.ecoJobs.progress(h.player, job) } returns 0.0
    every { h.ecoJobs.rank(h.player, job) } returns null
    every { h.ecoJobs.onlineWorkers(job) } returns 0
    every { h.ecoJobs.rewards(h.player, job, any()) } returns listOf(Component.text("Награда за уровень"))
    return job
}

private class JobsMenuHarness(
    val plugin: JavaPlugin,
    val player: PlayerMock,
    val menu: JobsMenu,
    val ecoJobs: EcoJobsBridge,
    val layouts: JobsMenuLayouts,
    val boosts: BoostService,
    val boosters: BoosterRegistry,
    val vouchers: VoucherService,
    val dataFolder: Path,
) : AutoCloseable {
    override fun close() {
        menu.close()
        dataFolder.toFile().deleteRecursively()
    }
}

private fun menuHarness(
    paper: MockBukkitTestRuntime,
    presentation: JobsMenuPresentation = JobsMenuPresentation.INVENTORY,
    earnings: EarningsService? = null,
    escapeBack: () -> Boolean = { false },
    display: ((PlayerMock, PaperDialogScreen) -> Unit)? = null,
): JobsMenuHarness {
    val plugin = paper.createSimplePlugin("ArcEcoJobsMenuTest")
    val player = spyk(paper.addPlayer("MenuQA"))
    every { player.closeDialog() } just Runs
    player.addAttachment(plugin, "arcecojobs.use", true)
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
    val settings = AddonSettings.load(dataFolder.resolve("config.yml").toFile()).let { it.copy(menuPresentation = presentation, earnings = it.earnings.copy(enabled = earnings != null)) }
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
        earnings = { earnings },
        reload = { Result.success(Unit) },
        layouts = layouts,
        escapeGoesBack = { escapeBack() },
        dialogDisplay = display?.let { sink -> object : JobsDialogDisplay {
            override fun show(player: org.bukkit.entity.Player, screen: PaperDialogScreen) {
                captureDialog(screen)
                sink(player as PlayerMock, screen)
            }
            override fun close(player: org.bukkit.entity.Player) = Unit
            override fun close() = Unit
        } },
    )
    plugin.server.pluginManager.registerEvents(
        JobsListener({ settings }, locale, menu, boosts, vouchers),
        plugin,
    )
    return JobsMenuHarness(plugin, player, menu, ecoJobs, layouts, boosts, boosters, vouchers, dataFolder)
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

/** Export actual composed Paper screens for the canonical Minecraft surface renderer. */
private fun captureDialog(screen: PaperDialogScreen) {
    if (screen.body.isEmpty()) return
    val root = Path.of(System.getProperty("arcecojobs.projectDir"), "build/dialog-snapshots")
    Files.createDirectories(root)
    val yaml = YamlConfiguration()
    val mini = MiniMessage.miniMessage()
    yaml.set("name", mini.serialize(screen.title))
    yaml.set("external_title", mini.serialize(screen.externalTitle))
    yaml.set("columns", screen.columns)
    screen.body.forEachIndexed { i, body ->
        yaml.set("body.row_$i.text", mini.serialize(body.text))
        yaml.set("body.row_$i.width", body.width)
    }
    screen.inputs.forEach { field ->
        yaml.set("inputs.${field.id.value}.label", mini.serialize(field.label))
        yaml.set("inputs.${field.id.value}.initial", field.initial)
        yaml.set("inputs.${field.id.value}.width", field.width)
    }
    screen.buttons.forEach { button ->
        yaml.set("buttons.${button.id.value}.text", mini.serialize(button.label))
        yaml.set("buttons.${button.id.value}.tooltip", mini.serialize(button.tooltip))
        yaml.set("buttons.${button.id.value}.width", button.width)
    }
    screen.exitButton?.let { button ->
        yaml.set("exit.text", mini.serialize(button.label))
        yaml.set("exit.tooltip", mini.serialize(button.tooltip))
        yaml.set("exit.width", button.width)
    }
    val number = Files.list(root).use { it.count() }
    yaml.save(root.resolve("$number-${screen.id}.yml").toFile())
}
