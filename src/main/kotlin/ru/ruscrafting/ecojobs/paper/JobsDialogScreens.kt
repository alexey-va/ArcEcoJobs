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
        escapeGoesBack: Boolean,
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
                id = PaperDialogActionId.of(id.replace('-', '_')), label = JobsDialogStyle.text(label),
                tooltip = JobsDialogStyle.text(tooltip), width = if (view is JobsView.EarningsHours && detailSlot == null) 102 else 230, onClick = { action() },
            )
        val closeButton = button("close", locale.render("common.close-name", player),
            locale.render("dialog.close-tooltip", player), close)
        fun screen(title: Component, body: List<PaperDialogBody>, actions: List<PaperDialogButton>, back: PaperDialogButton, suffix: String = "") =
            PaperDialogScreen(
                id = "ecojobs.${JobsMenuLayouts.menu(view).value}$suffix",
                title = recolor(title, TextColor.color(0xf4bd6a)),
                body = body.map { it.copy(text = JobsDialogStyle.text(it.text)) },
                buttons = actions,
                // Core substitutes this footer with the actual shared history action.
                exitButton = if (escapeGoesBack) back.copy(width = 200) else closeButton.copy(width = 200),
                columns = if (view is JobsView.EarningsHours && suffix.isEmpty()) 4 else if (actions.isEmpty()) 1 else 2,
            )
        val selected = rows.firstOrNull { it.slot == detailSlot }
        if (selected != null) return screen(
            name(selected.item),
            listOf(PaperDialogBody(join(lore(selected.item)), 468)),
            emptyList(),
            button("detail_back", locale.render("common.back-name", player), locale.render("common.back-lore", player)) { detail(null) },
            ".detail",
        )

        val description = when (view) {
            is JobsView.Shop -> "shop"
            is JobsView.ShopConfirm -> "shop-confirm"
            is JobsView.Catalog -> if (view.activeOnly) "active" else "catalog"
            else -> JobsMenuLayouts.menu(view).value
        }
        val body = mutableListOf(PaperDialogBody(locale.render("dialog.$description.description", player), 468))
        val buttons = mutableListOf<PaperDialogButton>()
        val pagination = mutableListOf<PaperDialogButton>()
        var back: PaperDialogButton? = null
        rows.forEach { row ->
            val title = name(row.item)
            val lines = lore(row.item)
            val tooltip = join(lines)
            val inline = row.id in setOf("profile", "overview", "summary", "status", "self", "empty", "confirm") ||
                view is JobsView.JobCard && row.id == "action" && !row.actionable
            if (inline) body += PaperDialogBody(join(listOf(title) + lines), 468)
            val control = when {
                row.actionable -> button(row.id, title, tooltip) { click(row.slot) }
                inline -> null
                else -> button("info_${row.id}", title, tooltip) { detail(row.slot) }
            }
            if (control != null) when (row.id) {
                "back", "cancel" -> back = control.copy(label = JobsDialogStyle.text(locale.render("common.back-name", player)))
                "previous", "next" -> pagination += control
                else -> buttons += control
            }
        }
        buttons += pagination
        return screen(frame.title, body, buttons, requireNotNull(back) { "Jobs dialog requires a parent action" })
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
