package ru.ruscrafting.ecojobs.integration

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

class PluginDescriptorTest : StringSpec({
    "orders the Vault broker and RedisEconomy provider before ArcEcoJobs" {
        val project = Path.of(System.getProperty("arcecojobs.projectDir"))
        val descriptor = Yaml().load<Map<String, Any>>(
            Files.readString(project.resolve("src/main/resources/plugin.yml")),
        )

        descriptor["depend"] shouldBe listOf("EcoJobs", "LuckPerms", "PlaceholderAPI", "Vault")
        descriptor["softdepend"] shouldBe listOf("RedisEconomy")
    }
})
