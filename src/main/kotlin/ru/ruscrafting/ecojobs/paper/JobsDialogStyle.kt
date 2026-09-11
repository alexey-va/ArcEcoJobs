package ru.ruscrafting.ecojobs.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/** Native presentation only: inventory item colors keep their own contract. */
internal object JobsDialogStyle {
    enum class Role {
        DEFAULT,
        AVAILABLE,
        SELECTED,
        UNAVAILABLE,
        PERSONAL,
        PAGINATION,
        SAVE,
        DESTRUCTIVE,
        MUTED,
        ACTIVITY,
        PROGRESSION,
        TRADE,
    }

    fun text(component: Component): Component = component
        .color(component.color()?.let { color -> TextColor.color(colors[color.value()] ?: color.value()) })
        .decoration(TextDecoration.ITALIC, false)
        .children(component.children().map(::text))

    fun role(component: Component, role: Role): Component = when (role) {
        Role.DEFAULT -> text(component)
        else -> recolor(text(component), role.color)
    }

    fun state(label: Component, role: Role, unavailableMarker: Component, trailingDetail: Boolean): Component {
        val marker = when (role) {
            Role.SELECTED -> "✔ "
            Role.AVAILABLE -> "○ "
            Role.UNAVAILABLE -> ""
            else -> ""
        }
        val prefix = if (role == Role.UNAVAILABLE) unavailableMarker else Component.text(marker)
        val result = role(prefix.append(label), role)
        return if (trailingDetail) detail(result) else result
    }

    fun previous(label: Component): Component = navigation(label, "‹ ", prefix = true, Role.PAGINATION)

    fun next(label: Component): Component = navigation(label, " ›", prefix = false, Role.PAGINATION)

    fun back(label: Component): Component = navigation(label, "‹ ", prefix = true, Role.MUTED)

    fun close(label: Component): Component = role(label, Role.MUTED)

    fun title(component: Component, role: Role = Role.ACTIVITY): Component = recolor(component, role.color)

    fun forward(label: Component, role: Role): Component = navigation(label, " ›", prefix = false, role)

    private fun detail(label: Component): Component = label.append(Component.text(" ›", Role.PERSONAL.color))

    fun action(id: String, label: Component): Component = when {
        id.startsWith("preset_") -> forward(label, Role.PERSONAL)
        id == "reload" -> forward(label, Role.PERSONAL)
        else -> when (id) {
        "previous" -> previous(label)
        "next" -> next(label)
        "back", "cancel" -> back(label)
        "close" -> close(label)
        "confirm", "save", "save_changes" -> role(label, Role.SAVE)
        "delete", "remove", "revoke_confirm" -> role(label, Role.DESTRUCTIVE)
        "give", "grant", "revoke", "list", "show", "continue", "review", "presets" -> forward(label, Role.PERSONAL)
        else -> text(label)
        }
    }

    private fun navigation(label: Component, marker: String, prefix: Boolean, role: Role): Component {
        val plain = PlainTextComponentSerializer.plainText().serialize(label)
        val alreadyMarked = if (prefix) plain.trimStart().startsWith(marker.trim()) else plain.trimEnd().endsWith(marker.trim())
        val decorated = if (alreadyMarked) label else if (prefix) Component.text(marker).append(label) else label.append(Component.text(marker))
        return role(decorated, role)
    }

    private fun recolor(component: Component, color: TextColor, root: Boolean = true): Component {
        val source = component.color()
        val tinted = if (root || source?.value() in AUTHORED_NEUTRALS) component.color(color) else component
        return tinted.decoration(TextDecoration.ITALIC, false)
            .children(tinted.children().map { recolor(it, color, root = false) })
    }

    private val Role.color: TextColor
        get() = when (this) {
            Role.AVAILABLE -> TextColor.color(0xffffff)
            Role.SELECTED, Role.SAVE -> TextColor.color(0x9bd48d)
            Role.UNAVAILABLE, Role.MUTED -> TextColor.color(0xffffff)
            Role.PERSONAL -> TextColor.color(0xc4a7e7)
            Role.PAGINATION -> TextColor.color(0x92bed8)
            Role.DESTRUCTIVE -> TextColor.color(0xff6b61)
            Role.ACTIVITY -> TextColor.color(0xffb277)
            Role.PROGRESSION -> TextColor.color(0xc4abff)
            Role.TRADE -> TextColor.color(0xf4d87a)
            Role.DEFAULT -> TextColor.color(0xe8dfd2)
        }

    private val colors = mapOf(
        0xe6fff3 to 0xe8dfd2,
        0x8c8c8c to 0xe8dfd2, 0x969696 to 0xe8dfd2, 0xaaaaaa to 0xe8dfd2,
        0x20252b to 0xe8dfd2, 0x555555 to 0xe8dfd2, 0x666666 to 0xe8dfd2,
        0x707070 to 0xe8dfd2, 0x707a76 to 0xe8dfd2, 0x77736d to 0xe8dfd2,
        0x777777 to 0xe8dfd2, 0x9aa8b7 to 0xe8dfd2, 0xaaa49a to 0xe8dfd2,
        0xb8b8b8 to 0xe8dfd2, 0xb8c8c0 to 0xe8dfd2, 0xc9c3ba to 0xe8dfd2,
        0xd0d0d0 to 0xe8dfd2,
        0x79c99e to 0x9bd48d,
    )

    private val AUTHORED_NEUTRALS = setOf(
        0x20252b, 0x555555, 0x666666, 0x707070, 0x707a76, 0x77736d, 0x777777,
        0x8c8c8c, 0x969696, 0x9aa8b7, 0xaaaaaa, 0xaaa49a, 0xb8b8b8, 0xb8c8c0,
        0xc9c3ba, 0xd0d0d0,
    )
}
