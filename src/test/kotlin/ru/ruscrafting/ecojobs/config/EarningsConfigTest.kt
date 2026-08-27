package ru.ruscrafting.ecojobs.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

private fun Path.isActiveJobDefinition(): Boolean =
    fileName.toString().let { name -> name.endsWith(".yml") && !name.startsWith("_") }

class EarningsConfigTest : StringSpec({
    val project = Path.of(System.getProperty("arcecojobs.projectDir"))

    fun yaml(contents: String): YamlConfiguration = YamlConfiguration().apply { loadFromString(contents) }

    "earnings settings reject unbounded or invalid profiles" {
        listOf(
            "earnings:\n  retention-days: 6",
            "earnings:\n  flush-seconds: 4",
            "earnings:\n  cleanup-hours: 25",
            "earnings:\n  maximum-pending-buckets: 255",
            "earnings:\n  time-zone: Not/AZone",
        ).forEach { contents ->
            shouldThrow<IllegalArgumentException> { EarningsSettings.load(yaml(contents)) }
        }
    }

    "production and lab share one bounded enabled profile" {
        val profiles = listOf(
            project.parent.resolve("classic/plugins/ArcEcoJobs/config.yml"),
            project.parent.resolve("classic_survival/plugins/ArcEcoJobs/config.yml"),
            project.parent.resolve("scripts/lab/plugin-configs/ArcEcoJobs/config.yml"),
        ).map { path -> EarningsSettings.load(YamlConfiguration.loadConfiguration(path.toFile())) }

        profiles.toSet().size shouldBe 1
        profiles.first().enabled shouldBe true
        profiles.first().retentionDays shouldBe 30
        profiles.first().flushInterval shouldBe Duration.ofSeconds(10)
        profiles.first().maximumPendingBuckets shouldBe 4_096
    }

    "every active payout has matching boost and earnings placeholders" {
        listOf(
            project.parent.resolve("classic/plugins/EcoJobs/jobs"),
            project.parent.resolve("classic_survival/plugins/EcoJobs/jobs"),
            project.parent.resolve("scripts/lab/plugin-configs/EcoJobs/jobs"),
        ).forEach { root ->
            Files.list(root).use { paths ->
                paths.filter(Path::isActiveJobDefinition).forEach { path ->
                    val jobId = path.fileName.toString().removeSuffix(".yml")
                    val job = YamlConfiguration.loadConfiguration(path.toFile())
                    val payouts = job.getMapList("effects").filter { it["id"] == "give_money" }
                    payouts.isEmpty() shouldBe false
                    payouts.forEach { effect ->
                        @Suppress("UNCHECKED_CAST")
                        val amount = (effect["args"] as Map<String, String>).getValue("amount")
                        ("%arcecojobs_boost_${jobId}_money_multiplier%" in amount) shouldBe true
                        ("%arcecojobs_earnings_${jobId}_money_marker%" in amount) shouldBe true
                    }
                }
            }
        }
    }

    "underscore-prefixed templates are not active EcoJobs definitions" {
        Path.of("_example.yml").isActiveJobDefinition() shouldBe false
        Path.of("miner.yml").isActiveJobDefinition() shouldBe true
    }
})
