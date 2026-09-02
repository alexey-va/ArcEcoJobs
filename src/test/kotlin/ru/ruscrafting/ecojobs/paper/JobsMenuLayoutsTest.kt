package ru.ruscrafting.ecojobs.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files

class JobsMenuLayoutsTest : StringSpec({
    "fixed actions and content regions follow one validated config generation" {
        val root = Files.createTempDirectory("arcecojobs-layout-")
        val source = requireNotNull(JobsMenuLayoutsTest::class.java.classLoader.getResourceAsStream("config.yml"))
        val data = source.use { Yaml().load<MutableMap<String, Any?>>(it) }
        @Suppress("UNCHECKED_CAST")
        val layouts = ((data.getValue("gui") as MutableMap<String, Any?>).getValue("layouts") as MutableMap<String, Any?>)
        @Suppress("UNCHECKED_CAST")
        val main = layouts.getValue("main") as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        ((main.getValue("elements") as MutableMap<String, Any?>).getValue("back") as MutableMap<String, Any?>)["slot"] = 46
        Files.writeString(root.resolve("config.yml"), Yaml().dump(data))

        val catalog = JobsMenuLayouts.loadConfiguration(root)
        catalog.require(JobsMenuLayouts.MAIN).slot("back").index shouldBe 46
        catalog.require(JobsMenuLayouts.EARNINGS_HOURS).region("content").size shouldBe 28
    }

    "an overlap rejects the complete candidate" {
        val root = Files.createTempDirectory("arcecojobs-overlap-")
        val source = requireNotNull(JobsMenuLayoutsTest::class.java.classLoader.getResourceAsStream("config.yml"))
        val yaml = source.bufferedReader().use { it.readText() }
            .replace("admin: {slot: 49}", "admin: {slot: 45}")
        Files.writeString(root.resolve("config.yml"), yaml)

        runCatching { JobsMenuLayouts.loadConfiguration(root) }.exceptionOrNull()?.message.orEmpty() shouldContain "already occupied"
    }

    "hourly earnings reject an undersized content region" {
        val root = Files.createTempDirectory("arcecojobs-capacity-")
        val source = requireNotNull(JobsMenuLayoutsTest::class.java.classLoader.getResourceAsStream("config.yml"))
        val yaml = source.bufferedReader().use { it.readText() }.replace(
            "regions: {content: {slots: ['10-16', '18-21', '23-25', '28-34', '37-43']}}",
            "regions: {content: {slots: [10, 11, 12]}}",
        )
        Files.writeString(root.resolve("config.yml"), yaml)

        runCatching { JobsMenuLayouts.loadConfiguration(root) }.exceptionOrNull()?.message.orEmpty() shouldContain
            "needs at least 24 slots, got 3"
    }
})
