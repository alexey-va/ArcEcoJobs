package ru.ruscrafting.ecojobs.config

import io.kotest.core.spec.style.StringSpec
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
})
