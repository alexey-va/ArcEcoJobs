package ru.ruscrafting.ecojobs.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.opentest4j.TestAbortedException
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ecojobs.domain.BoostType
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class AddonConfigTest : StringSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeSpec { paper = MockBukkitTestRuntime.open() }
    afterSpec { paper.close() }

    val project = Path.of(System.getProperty("arcecojobs.projectDir"))
    fun opsRoot(): Path = System.getProperty("ruscrafting.opsRoot")?.let(Path::of)
        ?: throw TestAbortedException("RusCrafting ops checkout is not configured")
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
        maximumStackedBoostDuration = Duration.ofDays(365),
        requireMoneyPlaceholder = true,
        guiItems = GuiItems.vanilla(),
    )

    fun booster(extra: String = ""): String = """
        boosters:
          sample:
            enabled: true
            multiplier: 1.5
            duration: 1h
            jobs: [all]
            item:
              material: PAPER
              name-key: booster.sample.name
              lore-key: booster.sample.lore
        $extra
    """.trimIndent()

    fun yaml(contents: String) = Files.createTempFile("arcecojobs-config-", ".yml").also { path ->
        Files.writeString(path, contents)
        path.toFile().deleteOnExit()
    }.toFile()

    "native dialogs are default for old configs and inventory remains selectable" {
        AddonSettings.load(yaml("{}")).menuPresentation shouldBe JobsMenuPresentation.DIALOG
        AddonSettings.load(yaml("gui:\n  presentation: inventory")).menuPresentation shouldBe JobsMenuPresentation.INVENTORY
        shouldThrow<IllegalStateException> { AddonSettings.load(yaml("gui:\n  presentation: typo")) }
    }

    "ALL is the default booster type" {
        BoosterRegistry.load(yaml(booster()), settings, setOf("miner")).get("sample")!!.type shouldBe BoostType.ALL
    }

    "settings reject a multiplier maximum that the LuckPerms node codec cannot encode" {
        val config = yaml("""
            boosts:
              minimum-multiplier: 1.01
              maximum-multiplier: 1001
        """.trimIndent())

        shouldThrow<IllegalArgumentException> { AddonSettings.load(config) }
    }

    "settings reject unsafe cache and page bounds instead of silently clamping" {
        val invalidConfigs = listOf(
            "leaderboards:\n  cache-seconds: 29",
            "leaderboards:\n  entries-per-page: 29",
            "boosts:\n  cache-millis: 99",
        )

        invalidConfigs.forEach { contents ->
            shouldThrow<IllegalArgumentException> { AddonSettings.load(yaml(contents)) }
        }
    }

    "stacked duration cannot be shorter than one voucher duration" {
        val config = yaml("""
            boosts:
              maximum-duration: 30d
              maximum-stacked-duration: 29d
        """.trimIndent())

        shouldThrow<IllegalArgumentException> { AddonSettings.load(config) }
    }

    "enabled voucher redemption requires bounded MySQL settings and a password" {
        val missingPassword = yaml("""
            redemptions:
              mysql:
                enabled: true
        """.trimIndent())
        shouldThrow<IllegalArgumentException> { AddonSettings.load(missingPassword) }

        val oversizedPool = yaml("""
            redemptions:
              mysql:
                enabled: true
                password: test-password
                pool:
                  maximum-size: 9
        """.trimIndent())
        shouldThrow<IllegalArgumentException> { AddonSettings.load(oversizedPool) }

        val enabled = AddonSettings.load(yaml("""
            redemptions:
              mysql:
                enabled: true
                password: test-password
        """.trimIndent())).redemptionStorage
        enabled.enabled shouldBe true
        enabled.maximumPoolSize shouldBe 4
    }

    "exploration requires shared MySQL and a bounded queue" {
        shouldThrow<IllegalArgumentException> {
            AddonSettings.load(yaml("exploration:\n  enabled: true"))
        }
        shouldThrow<IllegalArgumentException> {
            AddonSettings.load(yaml("exploration:\n  maximum-in-flight: 15"))
        }

        val enabled = AddonSettings.load(yaml("""
            exploration:
              enabled: true
              maximum-in-flight: 512
            redemptions:
              mysql:
                enabled: true
                password: test-password
        """.trimIndent())).exploration
        enabled.enabled shouldBe true
        enabled.maximumInFlight shouldBe 512
    }

    "earnings require shared MySQL and enforce bounded retention and buffering" {
        shouldThrow<IllegalArgumentException> {
            AddonSettings.load(yaml("earnings:\n  enabled: true"))
        }
        listOf(
            "earnings:\n  retention-days: 6",
            "earnings:\n  flush-seconds: 4",
            "earnings:\n  cleanup-hours: 25",
            "earnings:\n  maximum-pending-buckets: 255",
            "earnings:\n  time-zone: Not/AZone",
        ).forEach { contents ->
            shouldThrow<IllegalArgumentException> { AddonSettings.load(yaml(contents)) }
        }

        val enabled = AddonSettings.load(yaml("""
            earnings:
              enabled: true
              retention-days: 30
              maximum-pending-buckets: 4096
              time-zone: Europe/Moscow
            redemptions:
              mysql:
                enabled: true
                password: test-password
        """.trimIndent())).earnings
        enabled.enabled shouldBe true
        enabled.retentionDays shouldBe 30
        enabled.maximumPendingBuckets shouldBe 4_096
        enabled.zoneId.id shouldBe "Europe/Moscow"
    }

    "malformed YAML fails closed instead of loading defaults" {
        val malformed = yaml("boosts: [")

        shouldThrow<org.bukkit.configuration.InvalidConfigurationException> { AddonSettings.load(malformed) }
        shouldThrow<org.bukkit.configuration.InvalidConfigurationException> {
            BoosterRegistry.load(malformed, settings, setOf("miner"))
        }
    }

    "item model is validated while presets load" {
        val config = booster().replace("name-key:", "item-model: 'not a valid key'\n      name-key:")
        shouldThrow<IllegalArgumentException> {
            BoosterRegistry.load(yaml(config), settings, setOf("miner"))
        }
    }

    "custom model data must be an exact non-negative integer" {
        val config = booster().replace("name-key:", "custom-model-data: 1.5\n      name-key:")
        shouldThrow<IllegalArgumentException> {
            BoosterRegistry.load(yaml(config), settings, setOf("miner"))
        }
    }

    "GUI custom model data must also be an exact non-negative integer" {
        val config = yaml("""
            gui:
              items:
                close:
                  material: RED_STAINED_GLASS_PANE
                  custom-model-data: 1.5
        """.trimIndent())
        shouldThrow<IllegalArgumentException> { AddonSettings.load(config) }
    }

    "GUI and booster custom model data share the same acceptance boundary" {
        data class Case(val name: String, val yamlValue: String?, val accepted: Boolean, val expected: Int? = null)
        val cases = listOf(
            Case("null", null, true),
            Case("negative", "-1", false),
            Case("zero", "0", true),
            Case("fractional", "1.5", false),
            Case("maximum", Int.MAX_VALUE.toString(), true, Int.MAX_VALUE),
            Case("overflow", (Int.MAX_VALUE.toLong() + 1).toString(), false),
            Case("nan", ".NaN", false),
            Case("positive infinity", ".inf", false),
            Case("negative infinity", "-.inf", false),
            Case("string", "'123'", false),
        )

        cases.forEach { case ->
            val field = case.yamlValue?.let { "custom-model-data: $it" }.orEmpty()
            val gui = yaml("""
                gui:
                  items:
                    close:
                      material: RED_STAINED_GLASS_PANE
                      $field
            """.trimIndent())
            if (case.name in setOf("nan", "positive infinity", "negative infinity")) {
                (YamlConfiguration.loadConfiguration(gui).get("gui.items.close.custom-model-data") is Number) shouldBe true
            }
            val boosterConfig = case.yamlValue?.let {
                booster().replace("name-key:", "$field\n      name-key:")
            } ?: booster()
            val guiResult = runCatching { AddonSettings.load(gui).guiItems.close.customModelData }
            val boosterResult = runCatching {
                BoosterRegistry.load(yaml(boosterConfig), settings, setOf("miner"))
                    .get("sample")!!.item.customModelData
            }
            if (case.accepted) {
                guiResult.getOrElse { throw AssertionError("GUI case ${case.name} failed", it) } shouldBe case.expected
                boosterResult.getOrElse { throw AssertionError("booster case ${case.name} failed", it) } shouldBe case.expected
            } else {
                guiResult.isFailure shouldBe true
                boosterResult.isFailure shouldBe true
            }
        }
    }

    "every named menu icon can be changed without recompiling" {
        val config = yaml("""
            gui:
              items:
                leaderboard-loading:
                  material: AMETHYST_SHARD
                  custom-model-data: 731
        """.trimIndent())

        AddonSettings.load(config).guiItems["leaderboard-loading"] shouldBe
            GuiItemDefinition(Material.AMETHYST_SHARD, 731)
    }

    "bundled GUI config remains resource-pack independent" {
        val gui = AddonSettings.load(project.resolve("src/main/resources/config.yml").toFile()).guiItems
        listOf(
            gui.background,
            gui.back,
            gui.previous,
            gui.next,
            gui.close,
            gui.confirm,
            gui.cancel,
            gui.refresh,
            gui.catalog,
        ).map(GuiItemDefinition::customModelData) shouldBe List(9) { null }
        gui.named.values.map(GuiItemDefinition::customModelData).all { it == null } shouldBe true
        gui.background.material shouldBe Material.GRAY_STAINED_GLASS_PANE
        gui.back.material shouldBe Material.BLUE_STAINED_GLASS_PANE
        gui.refresh.material shouldBe Material.REPEATER
        gui.catalog.material shouldBe Material.CRAFTING_TABLE
    }

    "bundled catalog keeps nine money and nine token offers" {
        val registry = BoosterRegistry.load(
            project.resolve("src/main/resources/boosters.yml").toFile(),
            settings,
            setOf("miner"),
        )
        registry.values().size shouldBe 18
        registry.values().count { it.shopVisible } shouldBe 18
        registry.values().count { it.shopVisible && it.price?.currency == ShopCurrency.MONEY } shouldBe 9
        registry.values().count { it.shopVisible && it.price?.currency == ShopCurrency.TOKENS } shouldBe 9
        registry.values().map { it.item.material }.none {
            it in setOf(Material.EXPERIENCE_BOTTLE, Material.HONEY_BOTTLE, Material.POTION, Material.SPLASH_POTION)
        } shouldBe true
    }

    "production and lab voucher catalogs match the bundled defaults" {
        val expected = Files.readString(project.resolve("src/main/resources/boosters.yml"))
        listOf(
            opsRoot().resolve("classic/plugins/ArcEcoJobs/boosters.yml"),
            opsRoot().resolve("classic_survival/plugins/ArcEcoJobs/boosters.yml"),
            opsRoot().resolve("scripts/lab/plugin-configs/ArcEcoJobs/boosters.yml"),
        ).forEach { path ->
            Files.readString(path) shouldBe expected
        }
    }

    "production GUI overlays use the reviewed RusCrafting model data on both nodes" {
        val roots = listOf("classic", "classic_survival")
        val overlays = roots.map { root ->
            AddonSettings.load(opsRoot().resolve("$root/plugins/ArcEcoJobs/config.yml").toFile()).guiItems
        }
        overlays[0] shouldBe overlays[1]
        val gui = overlays[0]
        mapOf(
            "background" to gui.background.customModelData,
            "back" to gui.back.customModelData,
            "previous" to gui.previous.customModelData,
            "next" to gui.next.customModelData,
            "close" to gui.close.customModelData,
            "confirm" to gui.confirm.customModelData,
            "cancel" to gui.cancel.customModelData,
            "refresh" to gui.refresh.customModelData,
            "catalog" to gui.catalog.customModelData,
        ) shouldBe mapOf(
            "background" to 11000,
            "back" to 11013,
            "previous" to 11009,
            "next" to 11008,
            "close" to 91002,
            "confirm" to 91007,
            "cancel" to 91002,
            "refresh" to null,
            "catalog" to null,
        )
        gui.refresh.material shouldBe Material.REPEATER
        gui.catalog.material shouldBe Material.CRAFTING_TABLE
    }

    "production nodes share one reviewed voucher ledger profile" {
        val roots = listOf("classic", "classic_survival")
        val stores = roots.map { root ->
            AddonSettings.load(opsRoot().resolve("$root/plugins/ArcEcoJobs/config.yml").toFile())
                .redemptionStorage
        }
        stores[0] shouldBe stores[1]
        stores[0].database shouldBe "common"
        stores[0].username shouldBe "arcecojobs"
        stores[0].maximumPoolSize shouldBe 4
    }

    "production and lab enable the same bounded exploration implementation" {
        listOf(
            opsRoot().resolve("classic/plugins/ArcEcoJobs/config.yml"),
            opsRoot().resolve("classic_survival/plugins/ArcEcoJobs/config.yml"),
            opsRoot().resolve("scripts/lab/plugin-configs/ArcEcoJobs/config.yml"),
        ).map { AddonSettings.load(it.toFile()).exploration } shouldBe
            List(3) { ExplorationSettings(enabled = true, maximumInFlight = 256) }
    }

    "production and lab enable the same bounded hourly earnings profile" {
        val profiles = listOf(
            opsRoot().resolve("classic/plugins/ArcEcoJobs/config.yml"),
            opsRoot().resolve("classic_survival/plugins/ArcEcoJobs/config.yml"),
            opsRoot().resolve("scripts/lab/plugin-configs/ArcEcoJobs/config.yml"),
        ).map { AddonSettings.load(it.toFile()).earnings }

        profiles.toSet().size shouldBe 1
        profiles.first().enabled shouldBe true
        profiles.first().retentionDays shouldBe 30
        profiles.first().flushInterval shouldBe Duration.ofSeconds(10)
        profiles.first().maximumPendingBuckets shouldBe 4_096
    }

    "production and lab explorer jobs preserve one reviewed discovery contract" {
        val paths = listOf(
            opsRoot().resolve("classic/plugins/EcoJobs/jobs/explorer.yml"),
            opsRoot().resolve("classic_survival/plugins/EcoJobs/jobs/explorer.yml"),
            opsRoot().resolve("scripts/lab/plugin-configs/EcoJobs/jobs/explorer.yml"),
        )
        // Mapping order is presentation; compare all values, including nested reward filters.
        paths.map { path ->
            YamlConfiguration.loadConfiguration(path.toFile()).getValues(true)
                .filterValues { it !is ConfigurationSection }
        }.toSet().size shouldBe 1
        val explorer = YamlConfiguration.loadConfiguration(
            paths.single { it.startsWith(opsRoot().resolve("scripts/lab")) }.toFile(),
        )
        explorer.getString("name") shouldBe "&#57B8C2Исследователь"
        explorer.getInt("max-level") shouldBe 50
        explorer.getString("icon") shouldBe "player_head texture:eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDcwNmIwNWQyZWVlNjU0NDAxNWM4YTY0M2NhNWQzNTcyZjNkOTAwM2M5ZDA4NDJhYjUxNzMyNTgyYmQ4ZDhlOSJ9fX0="
        explorer.getMapList("xp-gain-methods").single()["trigger"] shouldBe
            "custom_arcecojobs_discover_chunk"
        val money = explorer.getMapList("effects").single()
        money["id"] shouldBe "give_money"
        money["triggers"] shouldBe listOf("custom_arcecojobs_discover_chunk")
        @Suppress("UNCHECKED_CAST")
        val amount = (money["args"] as Map<String, String>).getValue("amount")
        amount shouldBe
            "(1.5 * (1 + (%level% - 1) * 0.03) * %alt_value%) * %arcecojobs_boost_explorer_money_multiplier% * %arcecojobs_earnings_explorer_money_marker%"
    }

    "case variants cannot overwrite another preset" {
        val duplicate = """
            boosters:
              sample:
                multiplier: 1.5
                duration: 1h
                jobs: [all]
                item:
                  material: PAPER
                  name-key: booster.sample.name
                  lore-key: booster.sample.lore
              Sample:
                multiplier: 2
                duration: 2h
                jobs: [all]
                item:
                  material: PAPER
                  name-key: booster.sample.name
                  lore-key: booster.sample.lore
        """.trimIndent()
        shouldThrow<IllegalArgumentException> {
            BoosterRegistry.load(yaml(duplicate), settings, setOf("miner"))
        }
    }
})
