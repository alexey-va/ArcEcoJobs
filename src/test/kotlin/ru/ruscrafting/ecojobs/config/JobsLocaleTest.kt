package ru.ruscrafting.ecojobs.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.nio.file.Files
import java.nio.file.Path

class JobsLocaleTest : StringSpec({
    val project = Path.of(System.getProperty("arcecojobs.projectDir"))

    "standalone lore block placeholders expand to separate rows" {
        val dataFolder = Files.createTempDirectory("arcecojobs-locale-")
        val langFolder = Files.createDirectories(dataFolder.resolve("lang"))
        for (language in listOf("ru", "en")) {
            Files.copy(
                project.resolve("src/main/resources/lang/$language.yml"),
                langFolder.resolve("$language.yml"),
            )
        }
        val settings = AddonSettings.load(project.resolve("src/main/resources/config.yml").toFile())
        val locale = JobsLocale(dataFolder.toFile()) { settings }
        val lines = locale.lines(
            path = "menu.levels.lore",
            values = mapOf(
                "required" to Component.text("83.33"),
                "status" to Component.text("Текущий уровень"),
            ),
            blocks = mapOf(
                "rewards" to listOf(Component.text("Первая награда"), Component.text("Вторая награда")),
            ),
        )
        val plain = PlainTextComponentSerializer.plainText()

        lines.map(plain::serialize) shouldBe listOf(
            "Нужно опыта: 83.33",
            "Награды уровня:",
            "Первая награда",
            "Вторая награда",
            "",
            "Текущий уровень",
        )
        lines.map(plain::serialize).all { line -> '\n' !in line && '\r' !in line } shouldBe true
    }

    "locale validation rejects placeholder drift between languages" {
        val dataFolder = Files.createTempDirectory("arcecojobs-locale-placeholder-")
        val langFolder = Files.createDirectories(dataFolder.resolve("lang"))
        try {
            for (language in listOf("ru", "en")) {
                Files.copy(project.resolve("src/main/resources/lang/$language.yml"), langFolder.resolve("$language.yml"))
            }
            val english = langFolder.resolve("en.yml")
            Files.writeString(english, Files.readString(english).replace("<player> has no active boosts", "The player has no active boosts"))
            val settings = AddonSettings.load(project.resolve("src/main/resources/config.yml").toFile())

            shouldThrow<IllegalArgumentException> { JobsLocale(dataFolder.toFile()) { settings }.validate() }
        } finally {
            dataFolder.toFile().deleteRecursively()
        }
    }

    "locale validation rejects non-string lore rows" {
        val dataFolder = Files.createTempDirectory("arcecojobs-locale-type-")
        val langFolder = Files.createDirectories(dataFolder.resolve("lang"))
        try {
            for (language in listOf("ru", "en")) {
                val source = Files.readString(project.resolve("src/main/resources/lang/$language.yml"))
                    .replace("    - '<#666666>• <#e6fff3>/arcjobs reload'", "    - 42")
                Files.writeString(langFolder.resolve("$language.yml"), source)
            }
            val settings = AddonSettings.load(project.resolve("src/main/resources/config.yml").toFile())

            shouldThrow<IllegalArgumentException> { JobsLocale(dataFolder.toFile()) { settings }.validate() }
        } finally {
            dataFolder.toFile().deleteRecursively()
        }
    }

    "locale construction fails closed on malformed YAML" {
        val dataFolder = Files.createTempDirectory("arcecojobs-locale-malformed-")
        val langFolder = Files.createDirectories(dataFolder.resolve("lang"))
        try {
            Files.copy(project.resolve("src/main/resources/lang/ru.yml"), langFolder.resolve("ru.yml"))
            Files.writeString(langFolder.resolve("en.yml"), "message: [")
            val settings = AddonSettings.load(project.resolve("src/main/resources/config.yml").toFile())

            shouldThrow<org.bukkit.configuration.InvalidConfigurationException> {
                JobsLocale(dataFolder.toFile()) { settings }
            }
        } finally {
            dataFolder.toFile().deleteRecursively()
        }
    }
})
