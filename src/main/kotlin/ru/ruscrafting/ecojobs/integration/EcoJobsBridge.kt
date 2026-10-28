package ru.ruscrafting.ecojobs.integration

import com.willfp.eco.core.Eco
import com.willfp.ecojobs.api.activeJobs
import com.willfp.ecojobs.api.canJoinJob
import com.willfp.ecojobs.api.getJobLevel
import com.willfp.ecojobs.api.getJobProgress
import com.willfp.ecojobs.api.getJobXP
import com.willfp.ecojobs.api.getJobXPRequired
import com.willfp.ecojobs.api.hasJob
import com.willfp.ecojobs.api.hasJobActive
import com.willfp.ecojobs.api.jobLimit
import com.willfp.ecojobs.api.joinJob
import com.willfp.ecojobs.api.leaveJob
import com.willfp.ecojobs.jobs.Job
import com.willfp.ecojobs.jobs.Jobs
import com.willfp.eco.core.price.ConfiguredPrice
import com.willfp.eco.core.price.impl.PriceEconomy
import com.willfp.eco.core.price.impl.PriceFree
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.paper.player.PaperOfflinePlayerData
import ru.ruscrafting.ecojobs.config.AddonSettings
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

data class RankingEntry(val uuid: UUID, val name: String, val level: Int, val xp: Double)

enum class JobJoinBlock { LOCKED, FULL, PRICE, UNAVAILABLE }

data class JobCost(
    val value: Double,
    val identifier: String,
    val coins: Boolean,
    val free: Boolean,
    val display: Component,
    val affordable: Boolean,
) {
    fun sameTerms(other: JobCost): Boolean = copy(affordable = other.affordable) == other
}

data class JobMembership(
    val active: Boolean,
    val usedSlots: Int,
    val limit: Int,
    val joinCost: JobCost,
    val leaveCost: JobCost,
    val resetsProgress: Boolean,
    val joinBlock: JobJoinBlock?,
) {
    val canJoin: Boolean get() = !active && joinBlock == null
}

class EcoJobsBridge(
    private val plugin: JavaPlugin,
    private val nativeWork: BudgetedTaskQueue,
    private val worker: BoundedAsyncExecutor,
    private val offlineData: PaperOfflinePlayerData,
    private val settings: () -> AddonSettings,
) {
    private data class CachedRankings(
        val validUntil: Instant,
        val rankings: Map<String, List<RankingEntry>>,
    )

    internal data class PlayerRef(val uuid: UUID, val name: String)

    private val legacyAmpersand = LegacyComponentSerializer.legacyAmpersand()
    private val legacySection = LegacyComponentSerializer.legacySection()
    @Volatile
    private var leaderboardCache: CachedRankings? = null
    private var huntFilterJob: Job? = null
    private var huntFilters = emptyList<com.willfp.libreforge.filters.FilterList>()
    private var leaderboardBuildInFlight = false
    private var leaderboardCallbacks = mutableListOf<(Result<Unit>) -> Unit>()
    private val closed = AtomicBoolean()

    fun jobs(): List<Job> = Jobs.values().sortedBy(Job::id)
    fun job(id: String?): Job? = Jobs.getByID(id?.lowercase())
    fun activeJobs(player: OfflinePlayer): Collection<Job> = player.activeJobs
    fun level(player: OfflinePlayer, job: Job): Int = player.getJobLevel(job)
    fun xp(player: OfflinePlayer, job: Job): Double = player.getJobXP(job)
    fun requiredXp(player: OfflinePlayer, job: Job): String = player.getJobXPRequired(job)
    fun progress(player: OfflinePlayer, job: Job): Double = player.getJobProgress(job)
    fun limit(player: Player): Int = player.jobLimit
    fun has(player: OfflinePlayer, job: Job): Boolean = player.hasJob(job)
    fun active(player: OfflinePlayer, job: Job): Boolean = player.hasJobActive(job)
    fun canJoin(player: Player, job: Job): Boolean = player.canJoinJob(job)

    fun membership(player: Player, job: Job): JobMembership {
        val active = active(player, job)
        val used = activeJobs(player).size
        val limit = limit(player)
        val joinCost = cost(player, job.joinPrice)
        return JobMembership(active, used, limit, joinCost, cost(player, job.leavePrice), job.resetsOnQuit, when {
            active -> null
            !has(player, job) -> JobJoinBlock.LOCKED
            used >= limit -> JobJoinBlock.FULL
            !joinCost.affordable -> JobJoinBlock.PRICE
            !canJoin(player, job) -> JobJoinBlock.UNAVAILABLE
            else -> null
        })
    }

    internal fun cost(player: Player, price: ConfiguredPrice): JobCost {
        val value = price.getValue(player)
        val coins = price.price is PriceEconomy
        val free = price.price is PriceFree || coins && value == 0.0
        return JobCost(value, price.identifier, coins, free,
            if (coins || free) Component.empty() else legacy(price.getDisplay(player)), price.canAfford(player))
    }
    fun totalLevel(player: OfflinePlayer): Int = jobs().sumOf { job ->
        if (participates(player, job)) level(player, job) else 0
    }

    fun join(player: Player, job: Job): Boolean {
        if (!canJoin(player, job)) return false
        player.joinJob(job)
        return active(player, job)
    }

    fun leave(player: Player, job: Job): Boolean {
        if (!active(player, job)) return false
        player.leaveJob(job)
        return !active(player, job)
    }

    fun onlineWorkers(job: Job): Int = Bukkit.getOnlinePlayers().count { active(it, job) }

    fun name(job: Job): Component = legacy(job.name)
    fun description(job: Job): Component = legacy(job.description)
    fun names(jobIds: Set<String>): Component = Component.join(
        JoinConfiguration.separator(Component.text(", ")),
        jobIds.sorted().map { id -> job(id)?.let(::name) ?: Component.text(id) },
    )
    fun legacy(raw: String): Component = if ('§' in raw) legacySection.deserialize(raw) else legacyAmpersand.deserialize(raw)

    fun rewards(player: Player, job: Job, level: Int): List<Component> =
        job.injectPlaceholdersInto(listOf("%rewards%"), player, level)
            .filter(String::isNotBlank)
            .map(::legacy)

    fun rankings(job: Job?): List<RankingEntry>? {
        val cache = validRankings() ?: return null
        return cache[rankingKey(job)].orEmpty()
    }

    fun rank(player: OfflinePlayer, job: Job?): Int? {
        val index = rankings(job)?.indexOfFirst { it.uuid == player.uniqueId } ?: return null
        return if (index < 0) null else index + 1
    }

    fun prepareLeaderboards(callback: (Result<Unit>) -> Unit = {}) {
        check(Bukkit.isPrimaryThread()) { "Leaderboard preparation must be requested from the server thread" }
        if (validRankings() != null) {
            notifyCallback(callback, Result.success(Unit))
            return
        }
        leaderboardCallbacks += callback
        if (leaderboardBuildInFlight) return

        leaderboardBuildInFlight = true
        val jobs = jobs()
        val startedAt = System.nanoTime()
        captureLeaderboardPlayers().thenCompose { players -> worker.submit {
            players.size to buildRankings(jobs, players)
        } }.whenComplete { built, failure ->
            nativeWork.call({ !closed.get() }) {
                leaderboardBuildInFlight = false
                val completion = if (failure != null) Result.failure(failure) else runCatching {
                    val (playerCount, rankings) = requireNotNull(built)
                    leaderboardCache = CachedRankings(Instant.now().plus(settings().leaderboardCache), rankings)
                    val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
                    plugin.logger.info(
                        "Leaderboard cache refreshed asynchronously for $playerCount profiles and ${jobs.size} jobs in ${elapsedMillis}ms",
                    )
                    Unit
                }
                if (completion.isFailure) {
                    plugin.logger.log(Level.WARNING, "Could not refresh the ArcEcoJobs leaderboard cache", completion.exceptionOrNull())
                }
                val callbacks = leaderboardCallbacks.iterator()
                leaderboardCallbacks = mutableListOf()
                nativeWork.submit({ !closed.get() }) {
                    if (!callbacks.hasNext()) true else { notifyCallback(callbacks.next(), completion); false }
                }
            }.thenCompose { it }.whenComplete { _, publicationFailure ->
                if (publicationFailure != null && !closed.get()) {
                    plugin.logger.log(Level.WARNING, "Could not finish the ArcEcoJobs leaderboard refresh", publicationFailure)
                }
            }
        }
    }

    internal fun captureLeaderboardPlayers(): CompletableFuture<List<PlayerRef>> {
        val online = linkedMapOf<UUID, String>()
        val players = Bukkit.getOnlinePlayers().iterator()
        return nativeWork.submit({ !closed.get() }) {
            if (!players.hasNext()) true else {
                val player = players.next()
                online[player.uniqueId] = player.name
                false
            }
        }.thenCompose { worker.submit { offlineData.listPlayerIds() + online.keys } }
            .thenCompose { ids -> nativeWork.call({ !closed.get() }) {
                val pending = ids.iterator()
                val cachedNames = linkedMapOf<UUID, String>()
                nativeWork.submit({ !closed.get() }) {
                    if (!pending.hasNext()) true else {
                        val id = pending.next()
                        @Suppress("DEPRECATION")
                        val name = Bukkit.getOfflinePlayer(id).playerProfile.name
                        name?.takeIf(String::isNotEmpty)?.let { cachedNames[id] = it }
                        false
                    }
                }.thenCompose { worker.submit {
                    val persisted = offlineData.names(ids.filter { it !in cachedNames && it !in online })
                    if (persisted.failures.isNotEmpty()) {
                        plugin.logger.warning("Could not read ${persisted.failures.size} offline player names; UUIDs remain in the leaderboard population")
                    }
                    ids.map { id -> PlayerRef(id, online[id] ?: cachedNames[id] ?: persisted.names[id] ?: id.toString().take(8)) }
                } }
            }.thenCompose { it } }
    }

    fun moneyIntegrationProblems(requireEarningsMarker: Boolean = settings().earnings.enabled): List<String> = jobs().mapNotNull { job ->
        val multiplier = "%arcecojobs_boost_${job.id}_money_multiplier%"
        val marker = "%arcecojobs_earnings_${job.id}_money_marker%"
        val effects = job.config.getSubsections("effects").filter { it.getString("id") == "give_money" }
        val integrated = effects.isNotEmpty() && effects.all { effect ->
            val amount = effect.getString("args.amount")
            multiplier in amount && (!requireEarningsMarker || marker in amount)
        }
        if (integrated) null else job.id
    }

    /** Reuse native spawner/entity/custom-entity filters; never count targets the job rejects. */
    fun payableHunt(data: com.willfp.libreforge.triggers.TriggerData): Boolean {
        val slayer = job("slayer") ?: return false
        ensureHuntFilters(slayer)
        return huntFilters.any { it.isMet(data) }
    }

    /** Compile the existing hunt filters while the delayed EcoJobs bootstrap is already on the server thread. */
    fun prepareHuntFilters() {
        job("slayer")?.let(::ensureHuntFilters)
    }

    fun rewardGuardIntegrationProblems(): List<String> = jobs().mapNotNull { job ->
        val guard = "%arcecojobs_work_${job.id}_allowed%"
        val actions = job.config.getSubsections("xp-gain-methods") +
            job.config.getSubsections("effects").filter { it.getString("id") == "give_money" }
        job.id.takeUnless { actions.isNotEmpty() && actions.all { guard == it.getString("filters.is_expression_true") } }
    }

    fun invalidateLeaderboards() {
        leaderboardCache = null
    }

    fun shutdown() {
        closed.set(true)
        leaderboardBuildInFlight = false
        leaderboardCallbacks.clear()
        leaderboardCache = null
    }

    private fun validRankings(): Map<String, List<RankingEntry>>? = leaderboardCache
        ?.takeIf { it.validUntil.isAfter(Instant.now()) }
        ?.rankings

    private fun buildRankings(jobs: List<Job>, players: List<PlayerRef>): Map<String, List<RankingEntry>> {
        val byJob = jobs.associate { it.id to mutableListOf<RankingEntry>() }.toMutableMap()
        val global = mutableListOf<RankingEntry>()
        players.forEach { player ->
            val profile = Eco.get().loadPlayerProfile(player.uuid)
            var totalLevel = 0
            var totalXp = 0.0
            var progressed = false
            jobs.forEach { job ->
                val level = profile.read(job.levelKey)
                val xp = profile.read(job.xpKey)
                if (level > 1 || xp > 0.0) {
                    progressed = true
                    byJob.getValue(job.id) += RankingEntry(player.uuid, player.name, level, xp)
                    totalLevel += level
                    totalXp += xp
                }
            }
            if (progressed) global += RankingEntry(player.uuid, player.name, totalLevel, totalXp)
        }
        val result = linkedMapOf<String, List<RankingEntry>>()
        jobs.forEach { job -> result[job.id] = byJob.getValue(job.id).sortedWith(RANKING_ORDER) }
        result[GLOBAL] = global.sortedWith(RANKING_ORDER)
        return result
    }

    private fun rankingKey(job: Job?): String = job?.id ?: GLOBAL
    private fun participates(player: OfflinePlayer, job: Job): Boolean =
        active(player, job) || level(player, job) > 1 || xp(player, job) > 0.0

    private fun ensureHuntFilters(slayer: Job) {
        if (huntFilterJob === slayer) return
        val context = com.willfp.libreforge.ViolationContext(
            Bukkit.getPluginManager().getPlugin("EcoJobs") as com.willfp.eco.core.EcoPlugin,
            "ArcEcoJobs hunting eligibility",
        )
        val actions = slayer.config.getSubsections("xp-gain-methods") +
            slayer.config.getSubsections("effects").filter { it.getString("id") == "give_money" }
        val compiled = actions.map { action ->
            com.willfp.libreforge.filters.FilterList(
                com.willfp.libreforge.filters.Filters.compile(action.getSubsection("filters"), context)
                    .filterNot { it.filter.id == "is_expression_true" },
            )
        }
        huntFilters = compiled
        huntFilterJob = slayer
    }

    private fun notifyCallback(callback: (Result<Unit>) -> Unit, result: Result<Unit>) {
        runCatching { callback(result) }.onFailure { failure ->
            plugin.logger.log(Level.WARNING, "An ArcEcoJobs leaderboard callback failed", failure)
        }
    }

    companion object {
        private const val GLOBAL = "__global__"
        private val RANKING_ORDER = compareByDescending<RankingEntry> { it.level }
            .thenByDescending { it.xp }
            .thenBy { it.name.lowercase() }
    }
}
