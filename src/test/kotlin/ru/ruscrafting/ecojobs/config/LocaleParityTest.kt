package ru.ruscrafting.ecojobs.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

class LocaleParityTest : StringSpec({
    "Russian and English locales contain identical nonblank leaves" {
        val project = Path.of(System.getProperty("arcecojobs.projectDir"))
        val yaml = Yaml()
        fun load(language: String): Map<String, Any?> = Files.newBufferedReader(
            project.resolve("src/main/resources/lang/$language.yml"),
        ).use { yaml.load(it) }

        fun flatten(value: Any?, prefix: String = ""): Map<String, String> = when (value) {
            is Map<*, *> -> value.entries.flatMap { (key, child) ->
                flatten(child, if (prefix.isEmpty()) key.toString() else "$prefix.$key").entries
            }.associate { it.key to it.value }
            is List<*> -> value.mapIndexed { index, child -> "$prefix[$index]" to child.toString() }.toMap()
            else -> mapOf(prefix to value?.toString().orEmpty())
        }

        val russian = flatten(load("ru"))
        val english = flatten(load("en"))
        russian.keys shouldBe english.keys
        russian.filterKeys { '[' !in it }.values.all(String::isNotBlank) shouldBe true
        english.filterKeys { '[' !in it }.values.all(String::isNotBlank) shouldBe true
    }
})
