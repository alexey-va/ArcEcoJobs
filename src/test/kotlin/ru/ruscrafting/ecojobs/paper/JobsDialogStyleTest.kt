package ru.ruscrafting.ecojobs.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

class JobsDialogStyleTest : StringSpec({
    "maps authored gray to warm body while preserving white glyphs and semantic colors" {
        val source = Component.text("details", NamedTextColor.GRAY)
            .append(Component.text(" ").color(NamedTextColor.WHITE))
            .append(Component.text("ready", NamedTextColor.GREEN))
        val result = JobsDialogStyle.text(source)

        result.color()?.value() shouldBe 0xe8dfd2
        result.children()[0].color()?.value() shouldBe 0xffffff
        result.children()[1].color()?.value() shouldBe 0x55ff55
    }

    "uses white for neutral controls without flattening semantic labels" {
        val result = JobsDialogStyle.role(
            Component.text("Save").append(Component.text(" ✓", NamedTextColor.GREEN)),
            JobsDialogStyle.Role.MUTED,
        )
        result.color()?.value() shouldBe 0xffffff
        result.children()[0].color()?.value() shouldBe 0x55ff55
    }
})
