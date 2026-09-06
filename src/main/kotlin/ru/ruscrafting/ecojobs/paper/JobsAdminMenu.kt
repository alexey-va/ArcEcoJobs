package ru.ruscrafting.ecojobs.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.paper.menu.*
import ru.ruscrafting.ecojobs.config.BoosterRegistry
import ru.ruscrafting.ecojobs.config.JobsLocale
import java.util.UUID

/** Administrative forms use the command's operations and the player's existing dialog runtime. */
internal class JobsAdminMenu(
    private val locale: JobsLocale,
    private val boosters: () -> BoosterRegistry,
    private val execute: (Player, List<String>, (List<Component>) -> Unit) -> Unit,
    private val display: JobsDialogDisplay,
    private val closeDialog: (Player) -> Unit,
    private val returnToJobs: (Player) -> Unit,
    private val escapeBack: (Player) -> Boolean,
) {
    private val visits = mutableMapOf<UUID, Any>()

    fun invalidate(player: Player) { visits.remove(player.uniqueId) }
    fun clear() { visits.clear() }

    fun route(player: Player, args: List<String>) {
        val action = when (args.firstOrNull()?.lowercase()) {
            "boosters" -> "presets"
            "diagnose", "reload" -> args[0].lowercase()
            "booster" -> when (args.getOrNull(1)?.lowercase()) {
                "give" -> "give"
                "inspect" -> if (args.getOrNull(2).equals("hand", true)) "hand" else if (args.size > 2) "inspect" else "presets"
                else -> null
            }
            "boost" -> args.getOrNull(1)?.lowercase()?.takeIf { it in setOf("list", "grant", "revoke") }
            else -> null
        }
        if (!canOpen(player)) return denied(player)
        when (action) {
            "presets" -> presets(player)
            "inspect" -> run(player, "presets", listOf("booster", "inspect", args[2])) { presets(player) }
            "hand", "diagnose" -> run(player, action, if (action == "hand") listOf("booster", "inspect", "hand") else listOf("diagnose")) { open(player) }
            "reload" -> confirm(player, action, emptyMap(), listOf("reload")) { open(player) }
            "give" -> {
                val amount = args.getOrNull(4)?.takeUnless { it.startsWith("--") }
                val pairs = args.drop(if (amount == null) 4 else 5).chunked(2)
                val invalid = pairs.any { it.size != 2 || it[0] !in setOf("--duration", "--multiplier", "--type", "--jobs") }
                val overrides = pairs.mapNotNull { pair ->
                    val key = pair[0].removePrefix("--")
                    if (key in setOf("duration", "multiplier", "type", "jobs") && pair.size == 2) key to pair[1] else null
                }.toMap()
                form(player, action, overrides + mapOf("player" to args.getOrNull(2).orEmpty(), "preset" to args.getOrNull(3).orEmpty(), "amount" to (amount ?: "1")),
                    error = if (invalid) locale.render("message.unknown-subcommand", player) else null)
            }
            "grant" -> form(player, action, listOf("player", "duration", "multiplier", "type", "jobs").mapIndexedNotNull { i, key -> args.getOrNull(i + 2)?.let { key to it } }.toMap())
            "revoke" -> form(player, action, mapOf("player" to args.getOrNull(2).orEmpty(), "instance" to (args.getOrNull(3) ?: "all")))
            "list" -> form(player, action, mapOf("player" to (args.getOrNull(2) ?: player.name)))
            else -> open(player)
        }
    }

    fun open(player: Player) {
        if (!canOpen(player)) return denied(player)
        val buttons = buildList {
            if (allowed(player, "presets")) {
                add(button(player, "presets") { presets(player) })
                add(button(player, "give") { form(player, "give") })
                add(button(player, "hand") { run(player, "hand", listOf("booster", "inspect", "hand")) { open(player) } })
            }
            if (allowed(player, "list")) {
                add(button(player, "list") { form(player, "list") })
                add(button(player, "grant") { form(player, "grant") })
                add(button(player, "revoke") { form(player, "revoke") })
            }
            if (allowed(player, "diagnose")) add(button(player, "diagnose") { run(player, "diagnose", listOf("diagnose")) { open(player) } })
            if (allowed(player, "reload")) add(button(player, "reload") { confirm(player, "reload", emptyMap(), listOf("reload")) { open(player) } })
        }
        show(player, "root", text(player, "title"), listOf(text(player, "description")), buttons, reopen = { open(player) }, back = { returnToJobs(player) })
    }

    private fun presets(player: Player, page: Int = 0) {
        if (!allowed(player, "presets")) return denied(player)
        val entries = boosters().values().toList()
        val current = page.coerceIn(0, ((entries.size - 1) / 6).coerceAtLeast(0))
        val buttons = entries.drop(current * 6).take(6).mapIndexed { i, preset ->
            PaperDialogButton(PaperDialogActionId.of("preset_$i"), locale.render(preset.item.nameKey, player),
                tooltip = Component.text(preset.id), width = 230, onClick = {
                    run(player, "presets", listOf("booster", "inspect", preset.id),
                        extra = if (preset.enabled) listOf(button(player, "give") { form(player, "give", mapOf("preset" to preset.id)) }) else emptyList(),
                    ) { presets(player, current) }
                })
        }.toMutableList()
        if (current > 0) buttons += navigation(player, "previous") { presets(player, current - 1) }
        if ((current + 1) * 6 < entries.size) buttons += navigation(player, "next") { presets(player, current + 1) }
        show(player, "presets", text(player, "action.presets"), listOf(text(player, "presets-description")), buttons, reopen = { presets(player, current) }, back = { open(player) })
    }

    private data class Field(val id: String, val default: String = "", val max: Int = 64)
    private fun fields(action: String, player: Player) = when (action) {
        "give" -> listOf(Field("player", player.name, 16), Field("preset"), Field("amount", "1", 2))
        "give_options" -> listOf(Field("duration", max = 32), Field("multiplier", max = 16), Field("type", max = 8), Field("jobs", max = 256))
        "grant" -> listOf(Field("player", player.name, 16), Field("duration", "1h", 32), Field("multiplier", "2", 16), Field("type", "ALL", 8), Field("jobs", "all", 256))
        "revoke" -> listOf(Field("player", player.name, 16), Field("instance", "all", 36))
        else -> listOf(Field("player", player.name, 16))
    }

    private fun form(player: Player, action: String, values: Map<String, String> = emptyMap(), error: Component? = null) {
        if (!allowed(player, action)) return denied(player)
        val fields = fields(action, player)
        val inputs = fields.map { field -> PaperDialogTextInput(
            PaperDialogInputId.of(field.id), text(player, "field.${field.id}"),
            initial = (values[field.id] ?: field.default).take(field.max), width = 468, maxLength = field.max,
        ) }
        val submit = button(player, if (action in setOf("give", "give_options")) "continue" else if (action == "list") "show" else "review") { context ->
            val submitted = values + fields.associate { field -> field.id to context.text(PaperDialogInputId.of(field.id)).orEmpty().trim() }
            val missing = fields.firstOrNull { submitted[it.id].isNullOrBlank() && action != "give_options" }
            if (missing != null) {
                form(player, action, submitted, text(player, "required", mapOf("field" to text(player, "field.${missing.id}"))))
            } else if (action == "give") {
                form(player, "give_options", submitted)
            } else {
                val args = arguments(action, submitted)
                val back = { form(player, action, submitted) }
                if (action == "list") run(player, action, args, back = back)
                else confirm(player, action, submitted, args, back)
            }
        }
        show(player, action, text(player, "action.$action"), listOfNotNull(text(player, "form.$action"), error), listOf(submit), inputs,
            back = { if (action == "give_options") form(player, "give", values) else open(player) })
    }

    private fun arguments(action: String, values: Map<String, String>): List<String> = when (action) {
        "give_options" -> listOf("booster", "give", values.getValue("player"), values.getValue("preset"), values.getValue("amount")) +
            listOf("duration", "multiplier", "type", "jobs").flatMap { key -> values[key]?.takeIf(String::isNotBlank)?.let { listOf("--$key", it) }.orEmpty() }
        "grant" -> listOf("boost", "grant") + listOf("player", "duration", "multiplier", "type", "jobs").map(values::getValue)
        "revoke" -> listOf("boost", "revoke", values.getValue("player"), values.getValue("instance"))
        else -> listOf("boost", "list", values.getValue("player"))
    }

    private fun confirm(player: Player, action: String, values: Map<String, String>, args: List<String>, back: () -> Unit) {
        if (!allowed(player, action)) return denied(player)
        val summary = values.entries.map { (key, value) -> text(player, "value", mapOf(
            "field" to text(player, "field.$key"), "value" to (if (value.isBlank()) text(player, "preset-default") else Component.text(value)),
        )) }
        show(player, "confirm", text(player, "action.$action"), listOf(text(player, "confirm"), Component.join(JoinConfiguration.newlines(), summary)),
            listOf(button(player, "confirm") { run(player, action, args, back = back) }), reopen = { confirm(player, action, values, args, back) }, back = back)
    }

    private fun run(player: Player, action: String, args: List<String>, extra: List<PaperDialogButton> = emptyList(), back: () -> Unit) {
        if (!allowed(player, action)) return denied(player)
        val visit = show(player, "result.$action", text(player, "action.$action"), listOf(text(player, "loading")), emptyList(), back = back)
        execute(player, args) { messages ->
            if (visits[player.uniqueId] !== visit || !player.isOnline) return@execute
            if (!allowed(player, action)) return@execute denied(player)
            result(player, action, messages, 0, extra, back)
        }
    }

    private fun result(player: Player, action: String, messages: List<Component>, page: Int, extra: List<PaperDialogButton>, back: () -> Unit) {
        if (!allowed(player, action)) return denied(player)
        val buttons = extra.toMutableList()
        if (page > 0) buttons += navigation(player, "previous") { result(player, action, messages, page - 1, extra, back) }
        if ((page + 1) * 5 < messages.size) buttons += navigation(player, "next") { result(player, action, messages, page + 1, extra, back) }
        show(player, "result.$action", text(player, "action.$action"), listOf(text(player, "result")) + messages.drop(page * 5).take(5), buttons, reopen = { result(player, action, messages, page, extra, back) }, back = back)
    }

    private fun denied(player: Player) {
        show(player, "denied", text(player, "title"), listOf(locale.render("message.no-permission", player)), emptyList(), back = { returnToJobs(player) })
    }

    private fun show(player: Player, id: String, title: Component, body: List<Component>, actions: List<PaperDialogButton>, inputs: List<PaperDialogTextInput> = emptyList(), reopen: (() -> Unit)? = null, back: () -> Unit): Any {
        val visit = Any()
        visits[player.uniqueId] = visit
        val backButton = navigation(player, "back") { back() }
        val closeButton = navigation(player, "close") { invalidate(player); closeDialog(player) }
        val goesBack = escapeBack(player)
        fun guard(button: PaperDialogButton) = button.copy(onClick = { context ->
            if (player.isOnline && (inputs.isNotEmpty() || visits[player.uniqueId] === visit)) {
                visits.remove(player.uniqueId)
                button.onClick.handle(context)
            }
        })
        display.show(player, PaperDialogScreen(
            id = "ecojobs.admin.$id", title = title.color(net.kyori.adventure.text.format.TextColor.color(0xf4bd6a)).decoration(TextDecoration.ITALIC, false),
            body = body.map { PaperDialogBody(JobsDialogStyle.text(it), 468) }, inputs = inputs,
            buttons = actions.map { guard(it.copy(label = JobsDialogStyle.text(it.label), tooltip = JobsDialogStyle.text(it.tooltip))) },
            exitButton = guard((if (goesBack) backButton else closeButton).copy(width = 200)), columns = 2,
        ), reopen = reopen, onDismiss = { if (visits[player.uniqueId] === visit) invalidate(player) }, closeOnEscape = !goesBack)
        return visit
    }

    private fun button(player: Player, action: String, handler: (PaperDialogClickContext) -> Unit) = PaperDialogButton(
        PaperDialogActionId.of(action), text(player, "action.$action"), width = 230, onClick = { handler(it) },
    )
    private fun navigation(player: Player, action: String, handler: () -> Unit) = PaperDialogButton(
        PaperDialogActionId.of(action), locale.render("common.$action-name", player).decoration(TextDecoration.ITALIC, false), width = 230, onClick = { handler() },
    )
    private fun text(player: Player, key: String, values: Map<String, Component> = emptyMap()) =
        JobsDialogStyle.text(locale.render("admin-dialog.$key", player, values))

    private fun allowed(player: Player, action: String) = player.hasPermission("arcecojobs.admin.${when (action) {
        "presets", "hand", "give", "give_options" -> "booster"
        "list", "grant", "revoke" -> "boost"
        else -> action
    }}")

    companion object {
        fun canOpen(sender: CommandSender) = listOf("reload", "booster", "boost", "diagnose").any { sender.hasPermission("arcecojobs.admin.$it") }
    }
}
