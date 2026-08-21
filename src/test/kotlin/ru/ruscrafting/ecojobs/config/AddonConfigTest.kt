package ru.ruscrafting.ecojobs.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.ruscrafting.ecojobs.domain.BoostType
import java.nio.file.Files
import java.time.Duration

class AddonConfigTest : StringSpec({
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
        fillerMaterial = Material.BLACK_STAINED_GLASS_PANE,
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
