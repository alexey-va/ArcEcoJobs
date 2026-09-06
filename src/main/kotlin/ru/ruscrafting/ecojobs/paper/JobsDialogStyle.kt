package ru.ruscrafting.ecojobs.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration

/** Native presentation only: inventory item colors keep their own contract. */
internal object JobsDialogStyle {
    fun text(component: Component): Component = component
        .color(component.color()?.let { color -> TextColor.color(colors[color.value()] ?: color.value()) })
        .decoration(TextDecoration.ITALIC, false)
        .children(component.children().map(::text))

    private val colors = mapOf(
        0x92bed8 to 0xd7b486, 0xe6fff3 to 0xe8dfd2,
        0x8c8c8c to 0xaaa49a, 0x969696 to 0xaaa49a, 0xaaaaaa to 0xaaa49a,
        0x79c99e to 0x9bd48d,
    )
}
