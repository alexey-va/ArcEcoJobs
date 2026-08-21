package ru.ruscrafting.ecojobs.paper

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ru.ruscrafting.ecojobs.boost.BoostService
import ru.ruscrafting.ecojobs.boost.GrantResult
import ru.ruscrafting.ecojobs.boost.VoucherInspection
import ru.ruscrafting.ecojobs.boost.VoucherOverrides
import ru.ruscrafting.ecojobs.boost.VoucherService
import ru.ruscrafting.ecojobs.config.AddonSettings
import ru.ruscrafting.ecojobs.config.BoosterPreset
import ru.ruscrafting.ecojobs.config.BoosterRegistry
import ru.ruscrafting.ecojobs.config.JobsLocale
import ru.ruscrafting.ecojobs.domain.BoostInstance
import ru.ruscrafting.ecojobs.domain.BoostType
import ru.ruscrafting.ecojobs.domain.DurationParser
import ru.ruscrafting.ecojobs.domain.Multipliers
import ru.ruscrafting.ecojobs.integration.EcoJobsBridge
import java.time.Duration

class JobsCommand(
    private val settings: () -> AddonSettings,
    private val locale: JobsLocale,
    private val ecoJobs: EcoJobsBridge,
    private val boosts: BoostService,
    private val boosters: () -> BoosterRegistry,
    private val vouchers: VoucherService,
    private val menu: JobsMenu,
    private val reload: () -> Result<Unit>,
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) return open(sender)
        return when (args[0].lowercase()) {
            "help" -> help(sender)
            "reload" -> reload(sender)
            "boosters" -> boosterList(sender)
            "booster" -> booster(sender, args.drop(1))
            "boost" -> boost(sender, args.drop(1))
            "diagnose" -> diagnose(sender)
            else -> message(sender, "message.unknown-subcommand")
        }
    }

    private fun open(sender: CommandSender): Boolean {
        if (sender !is Player) return message(sender, "message.player-only")
        if (!sender.hasPermission("arcecojobs.use")) return message(sender, "message.no-permission")
        menu.open(sender)
        return true
    }

    private fun help(sender: CommandSender): Boolean {
        locale.lines("command.help", sender).forEach(sender::sendMessage)
        if (sender.hasPermission("arcecojobs.admin")) locale.lines("command.admin-help", sender).forEach(sender::sendMessage)
        return true
    }

    private fun reload(sender: CommandSender): Boolean {
        if (!sender.hasPermission("arcecojobs.admin.reload")) return message(sender, "message.no-permission")
        reload().fold(
            onSuccess = { message(sender, "message.reload-ok") },
            onFailure = { message(sender, "message.reload-failed", mapOf("reason" to text(it.message ?: "unknown"))) },
        )
        return true
    }

    private fun boosterList(sender: CommandSender): Boolean {
        if (!sender.hasPermission("arcecojobs.admin.booster")) return message(sender, "message.no-permission")
        message(sender, "message.boosters-header")
        boosters().values().forEach { preset ->
            message(sender, "message.boosters-entry", presetValues(preset, sender))
        }
        return true
    }

    private fun booster(sender: CommandSender, args: List<String>): Boolean {
        if (!sender.hasPermission("arcecojobs.admin.booster")) return message(sender, "message.no-permission")
        return when (args.firstOrNull()?.lowercase()) {
            "inspect" -> inspectBooster(sender, args.drop(1))
            "give" -> giveBooster(sender, args.drop(1))
            else -> message(sender, "message.unknown-subcommand")
        }
    }

    private fun inspectBooster(sender: CommandSender, args: List<String>): Boolean {
        val id = args.firstOrNull() ?: return message(sender, "message.unknown-subcommand")
        if (id.equals("hand", true)) {
            if (sender !is Player) return message(sender, "message.player-only")
            return when (val inspection = vouchers.inspect(sender.inventory.itemInMainHand)) {
                is VoucherInspection.Valid -> message(sender, "message.inspect-header", mapOf(
                    "id" to text(inspection.payload.presetId),
                    "type" to locale.type(inspection.payload.type, sender),
                    "multiplier" to text(Multipliers.format(inspection.payload.multiplierBasisPoints)),
                    "duration" to text(DurationParser.format(Duration.ofSeconds(inspection.payload.durationSeconds))),
                    "jobs" to jobsLabel(inspection.payload.jobs, sender),
                ))
                else -> message(sender, "message.booster-invalid")
            }
        }
        val preset = boosters().get(id) ?: return message(sender, "message.booster-unknown", mapOf("id" to text(id)))
        return message(sender, "message.inspect-header", mapOf("id" to text(preset.id)) + presetValues(preset, sender))
    }

    private fun giveBooster(sender: CommandSender, args: List<String>): Boolean {
        if (args.size < 2) return message(sender, "message.unknown-subcommand")
        val target = Bukkit.getPlayerExact(args[0]) ?: return message(sender, "message.player-not-found", mapOf("player" to text(args[0])))
        val preset = boosters().get(args[1]) ?: return message(sender, "message.booster-unknown", mapOf("id" to text(args[1])))
        if (!preset.enabled) return message(sender, "message.booster-disabled", mapOf("id" to text(preset.id)))
        var cursor = 2
        val amount = args.getOrNull(cursor)?.takeIf { !it.startsWith("--") }?.toIntOrNull()?.also { cursor++ } ?: 1
        if (amount !in 1..64) return message(sender, "message.invalid-number", mapOf("value" to text(amount)))
        val parsed = parseOverrides(sender, args.drop(cursor)) ?: return true
        if (target.inventory.storageContents.count { it == null || it.type.isAir } < amount) {
            return message(sender, "message.inventory-full")
        }
        repeat(amount) { target.inventory.addItem(vouchers.create(preset, target, parsed)) }
        message(sender, "message.booster-given", mapOf(
            "amount" to text(amount), "id" to text(preset.id), "player" to text(target.name),
        ))
        message(target, "message.booster-received", mapOf(
            "amount" to text(amount), "item" to locale.render(preset.item.nameKey, target),
        ))
        return true
    }

    private fun boost(sender: CommandSender, args: List<String>): Boolean = when (args.firstOrNull()?.lowercase()) {
        "list" -> listBoosts(sender, args.drop(1))
        "grant" -> grantBoost(sender, args.drop(1))
        "revoke" -> revokeBoost(sender, args.drop(1))
        else -> message(sender, "message.unknown-subcommand")
    }

    private fun listBoosts(sender: CommandSender, args: List<String>): Boolean {
        val target: OfflinePlayer = if (args.isEmpty()) {
            sender as? Player ?: return message(sender, "message.player-only")
        } else {
            if (!sender.hasPermission("arcecojobs.admin.boost")) return message(sender, "message.no-permission")
            offline(args[0]) ?: return message(sender, "message.player-not-found", mapOf("player" to text(args[0])))
        }
        boosts.loadActive(target.uniqueId) { active, failure ->
            if (failure != null || active == null) {
                message(sender, "message.booster-save-failed")
            } else if (active.isEmpty()) {
                message(sender, "message.boost-empty", mapOf("player" to text(target.name ?: target.uniqueId.toString().take(8))))
            } else {
                message(sender, "message.boost-list-header", mapOf("player" to text(target.name ?: target.uniqueId.toString().take(8))))
                active.forEach { entry -> message(sender, "message.boost-list-entry", boostValues(entry, sender)) }
            }
        }
        return true
    }

    private fun grantBoost(sender: CommandSender, args: List<String>): Boolean {
        if (!sender.hasPermission("arcecojobs.admin.boost")) return message(sender, "message.no-permission")
        if (args.size < 3) return message(sender, "message.unknown-subcommand")
        val target = offline(args[0]) ?: return message(sender, "message.player-not-found", mapOf("player" to text(args[0])))
        val duration = validatedDuration(sender, args[1]) ?: return true
        val multiplier = validatedMultiplier(sender, args[2]) ?: return true
        val type = args.getOrNull(3)?.let(BoostType::parse) ?: BoostType.ALL
        if (args.getOrNull(3) != null && BoostType.parse(args[3]) == null) return message(sender, "message.invalid-type")
        val jobs = parseJobs(sender, args.getOrNull(4) ?: "all") ?: return true
        boosts.grant(target.uniqueId, type, multiplier, duration, jobs) { result ->
            if (result == GrantResult.GRANTED) message(sender, "message.boost-granted", mapOf(
                "player" to text(target.name ?: args[0]),
                "multiplier" to text(Multipliers.format(multiplier)),
                "duration" to text(DurationParser.format(duration)),
            )) else message(sender, "message.booster-save-failed")
        }
        return true
    }

    private fun revokeBoost(sender: CommandSender, args: List<String>): Boolean {
        if (!sender.hasPermission("arcecojobs.admin.boost")) return message(sender, "message.no-permission")
        if (args.size < 2) return message(sender, "message.unknown-subcommand")
        val target = offline(args[0]) ?: return message(sender, "message.player-not-found", mapOf("player" to text(args[0])))
        boosts.revoke(target.uniqueId, args[1]) { count, failure ->
            if (failure != null || count == null) message(sender, "message.booster-save-failed")
            else message(sender, "message.boost-revoked", mapOf("count" to text(count)))
        }
        return true
    }

    private fun diagnose(sender: CommandSender): Boolean {
        if (!sender.hasPermission("arcecojobs.admin.diagnose")) return message(sender, "message.no-permission")
        val problems = ecoJobs.moneyIntegrationProblems()
        return if (problems.isEmpty()) message(sender, "message.diagnose-ok", mapOf(
            "jobs" to text(ecoJobs.jobs().size), "money" to text("OK"),
        )) else message(sender, "message.diagnose-problems", mapOf("problems" to text(problems.joinToString(", "))))
    }

    private fun parseOverrides(sender: CommandSender, args: List<String>): VoucherOverrides? {
        var duration: Duration? = null
        var multiplier: Int? = null
        var type: BoostType? = null
        var jobs: Set<String>? = null
        var index = 0
        while (index < args.size) {
            val key = args[index]
            val value = args.getOrNull(index + 1) ?: run {
                message(sender, "message.unknown-subcommand")
                return null
            }
            when (key.lowercase()) {
                "--duration" -> duration = validatedDuration(sender, value) ?: return null
                "--multiplier" -> multiplier = validatedMultiplier(sender, value) ?: return null
                "--type" -> type = BoostType.parse(value) ?: run {
                    message(sender, "message.invalid-type")
                    return null
                }
                "--jobs" -> jobs = parseJobs(sender, value) ?: return null
                else -> {
                    message(sender, "message.unknown-subcommand")
                    return null
                }
            }
            index += 2
        }
        return VoucherOverrides(duration, multiplier, type, jobs)
    }

    private fun validatedDuration(sender: CommandSender, raw: String): Duration? {
        val duration = DurationParser.parse(raw)
        if (duration == null || duration > settings().maximumBoostDuration) {
            message(sender, "message.invalid-duration", mapOf("value" to text(raw)))
            return null
        }
        return duration
    }

    private fun validatedMultiplier(sender: CommandSender, raw: String): Int? {
        val parsed = raw.toDoubleOrNull()?.takeIf(Double::isFinite)?.let(Multipliers::toBasisPoints)
        if (parsed == null || parsed !in settings().minimumMultiplierBasisPoints..settings().maximumMultiplierBasisPoints) {
            message(sender, "message.invalid-number", mapOf("value" to text(raw)))
            return null
        }
        return parsed
    }

    private fun parseJobs(sender: CommandSender, raw: String): Set<String>? {
        val jobs = raw.split(',').map { it.lowercase() }.toSet()
        val valid = ecoJobs.jobs().map { it.id }.toSet()
        if (jobs.isEmpty() || ("all" !in jobs && !valid.containsAll(jobs)) || ("all" in jobs && jobs.size > 1)) {
            message(sender, "message.invalid-jobs", mapOf("jobs" to text(jobs.filter { it !in valid && it != "all" }.joinToString(", "))))
            return null
        }
        return jobs
    }

    private fun presetValues(preset: BoosterPreset, sender: CommandSender): Map<String, Component> = mapOf(
        "id" to text(preset.id),
        "type" to locale.type(preset.type, sender),
        "multiplier" to text(Multipliers.format(preset.multiplierBasisPoints)),
        "duration" to text(DurationParser.format(preset.duration)),
        "jobs" to jobsLabel(preset.jobs, sender),
    )

    private fun boostValues(boost: BoostInstance, sender: CommandSender): Map<String, Component> = mapOf(
        "instance" to text(boost.instanceId.toString().take(8)),
        "type" to locale.type(boost.type, sender),
        "multiplier" to text(Multipliers.format(boost.multiplierBasisPoints)),
        "duration" to text(DurationParser.format(Duration.between(java.time.Instant.now(), boost.expiresAt).coerceAtLeast(Duration.ZERO))),
        "jobs" to jobsLabel(boost.jobs, sender),
    )

    private fun jobsLabel(jobs: Set<String>, sender: CommandSender): Component =
        if ("all" in jobs) locale.allJobs(sender) else text(jobs.sorted().joinToString(", "))

    private fun offline(name: String): OfflinePlayer? = Bukkit.getOfflinePlayerIfCached(name)
        ?: Bukkit.getPlayerExact(name)

    private fun message(sender: CommandSender, path: String, values: Map<String, Component> = emptyMap()): Boolean {
        sender.sendMessage(locale.render(path, sender, values))
        return true
    }

    private fun text(value: Any?): Component = locale.text(value)

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val options = when (args.size) {
            1 -> listOf("help", "boost") + if (sender.hasPermission("arcecojobs.admin")) listOf("reload", "boosters", "booster", "diagnose") else emptyList()
            2 -> when (args[0].lowercase()) {
                "boosters" -> listOf("list")
                "booster" -> listOf("inspect", "give")
                "boost" -> listOf("list", "grant", "revoke")
                else -> emptyList()
            }
            3 -> when (args[0].lowercase() to args[1].lowercase()) {
                "booster" to "inspect" -> boosters().values().map { it.id } + "hand"
                "booster" to "give", "boost" to "grant", "boost" to "revoke" -> Bukkit.getOnlinePlayers().map(Player::getName)
                else -> emptyList()
            }
            4 -> when (args[0].lowercase() to args[1].lowercase()) {
                "booster" to "give" -> boosters().values().map { it.id }
                else -> emptyList()
            }
            else -> if (args[0].equals("booster", true) && args[1].equals("give", true)) {
                listOf("--duration", "--multiplier", "--type", "--jobs")
            } else emptyList()
        }
        val prefix = args.lastOrNull().orEmpty()
        return options.filter { it.startsWith(prefix, true) }
    }
}
