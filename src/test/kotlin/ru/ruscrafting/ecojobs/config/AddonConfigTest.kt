package ru.ruscrafting.ecojobs.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.ruscrafting.ecojobs.domain.BoostType
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class AddonConfigTest : StringSpec({
    val project = Path.of(System.getProperty("arcecojobs.projectDir"))
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
        gui.background.material shouldBe Material.GRAY_STAINED_GLASS_PANE
        gui.back.material shouldBe Material.BLUE_STAINED_GLASS_PANE
        gui.refresh.material shouldBe Material.REPEATER
        gui.catalog.material shouldBe Material.CRAFTING_TABLE
    }

    "production GUI overlays use the reviewed RusCrafting model data on both nodes" {
        val roots = listOf("classic", "classic_survival")
        val overlays = roots.map { root ->
            AddonSettings.load(project.parent.resolve("$root/plugins/ArcEcoJobs/config.yml").toFile()).guiItems
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
