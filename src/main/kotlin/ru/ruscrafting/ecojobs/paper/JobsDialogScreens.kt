package ru.ruscrafting.ecojobs.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperMenuFrame
import ru.ruscrafting.ecojobs.config.JobsLocale

/** Presents the same localized menu frame and actions as native, two-column Paper dialogs. */
internal object JobsDialogScreens {
    fun screen(
        player: Player,
        view: JobsView,
        frame: PaperMenuFrame,
        layouts: JobsMenuLayouts,
        locale: JobsLocale,
        detailSlot: Int?,
        actionable: (String, Int?) -> Boolean,
        click: (Int) -> Unit,
        detail: (Int?) -> Unit,
        close: () -> Unit,
    ): PaperDialogScreen {
        val content = frame.content { _, _ -> }
        val layout = layouts.current().catalog.require(JobsMenuLayouts.menu(view))
        data class Row(val id: String, val slot: Int, val item: ItemStack, val actionable: Boolean)
        val rows = buildList {
            content.elements.forEach { (id, entry) ->
                add(Row(id.value, layout.slot(id).index, entry.item, entry.enabled && actionable(id.value, null)))
            }
            content.regions.forEach { (id, entries) ->
                val slots = layout.region(id)
                entries.forEachIndexed { index, entry ->
                    if (entry.enabled) add(Row("${id.value}_$index", slots[index].index, entry.item, actionable(id.value, index)))
                }
            }
        }
        fun button(id: String, label: Component, tooltip: Component = Component.empty(), action: () -> Unit) =
            PaperDialogButton(
                id = PaperDialogActionId.of(id.replace('-', '_')), label = label.decoration(TextDecoration.ITALIC, false),
                tooltip = tooltip, width = if (view is JobsView.EarningsHours && detailSlot == null) 102 else 210, onClick = { action() },
            )
        val closeButton = button("close", locale.render("common.close-name", player), action = close)
        val selected = rows.firstOrNull { it.slot == detailSlot }
        if (selected != null) return PaperDialogScreen(
            id = "ecojobs.${JobsMenuLayouts.menu(view).value}.detail",
            title = name(selected.item),
            body = listOf(PaperDialogBody(join(lore(selected.item)), 440)),
            buttons = listOf(button("detail_back", locale.render("common.back-name", player)) { detail(null) }, closeButton),
            // Paper has no Escape-close event. Explicit close invalidates pending async updates.
            canCloseWithEscape = false,
        )

        val body = mutableListOf<PaperDialogBody>()
        val buttons = mutableListOf<PaperDialogButton>()
        val navigation = mutableListOf<PaperDialogButton>()
        rows.forEach { row ->
            val title = name(row.item)
            val lines = lore(row.item)
            val tooltip = join(lines)
            val inline = row.id in setOf("profile", "overview", "summary", "status", "self", "empty", "confirm") ||
                view is JobsView.JobCard && row.id == "action" && !row.actionable
            if (inline) body += PaperDialogBody(join(listOf(title) + lines), 440)
            val control = when {
                row.actionable -> button(row.id, title, tooltip) { click(row.slot) }
                inline -> null
                else -> button("info_${row.id}", title, tooltip) { detail(row.slot) }
            }
            if (control != null) {
                if (row.id in setOf("back", "cancel", "previous", "next")) navigation += control else buttons += control
            }
        }
        buttons += navigation
        buttons += closeButton
        return PaperDialogScreen(
            id = "ecojobs.${JobsMenuLayouts.menu(view).value}",
            title = recolor(frame.title, TextColor.color(0xf4bd6a)),
            body = body, buttons = buttons,
            columns = if (view is JobsView.EarningsHours) 4 else if (buttons.size == 1) 1 else 2,
            canCloseWithEscape = false,
        )
    }

    private fun name(item: ItemStack): Component =
        (item.itemMeta.displayName() ?: Component.empty()).decoration(TextDecoration.ITALIC, false)

    private fun lore(item: ItemStack): List<Component> = item.itemMeta.lore().orEmpty()
        .dropLastWhile { plain.serialize(it).isBlank() }
        .map { it.decoration(TextDecoration.ITALIC, false) }

    private fun join(lines: List<Component>) = Component.join(JoinConfiguration.newlines(), lines)
        .decoration(TextDecoration.ITALIC, false)

    private fun recolor(value: Component, color: TextColor): Component = value.color(color)
        .decoration(TextDecoration.ITALIC, false).children(value.children().map { recolor(it, color) })

    private val plain = PlainTextComponentSerializer.plainText()
}
