package ru.ruscrafting.ecojobs.integration

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
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import ru.ruscrafting.ecojobs.config.AddonSettings
import java.time.Instant
import java.util.UUID

data class RankingEntry(val uuid: UUID, val name: String, val level: Int, val xp: Double)

class EcoJobsBridge(private val settings: () -> AddonSettings) {
    private data class CachedRankings(
        val validUntil: Instant,
        val rankings: Map<String, List<RankingEntry>>,
    )

    private val legacyAmpersand = LegacyComponentSerializer.legacyAmpersand()
    private val legacySection = LegacyComponentSerializer.legacySection()
    private var leaderboardCache: CachedRankings? = null

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
    fun totalLevel(player: OfflinePlayer): Int = jobs().sumOf { job ->
        if (participates(player, job)) level(player, job) else 0
    }

    fun join(player: Player, job: Job): Boolean {
        if (!canJoin(player, job)) return false
        player.joinJob(job)
        invalidateLeaderboards()
        return active(player, job)
    }

    fun leave(player: Player, job: Job): Boolean {
        if (!active(player, job)) return false
        player.leaveJob(job)
        invalidateLeaderboards()
        return !active(player, job)
    }

    fun workers(job: Job): Int = Bukkit.getOfflinePlayers().count { it.hasPlayedBefore() && active(it, job) }

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

    fun rankings(job: Job?): List<RankingEntry> {
        val cache = rankings()
        return cache[rankingKey(job)].orEmpty()
    }

    fun rank(player: OfflinePlayer, job: Job?): Int? {
        val index = rankings(job).indexOfFirst { it.uuid == player.uniqueId }
        return if (index < 0) null else index + 1
    }

    fun moneyIntegrationProblems(): List<String> = jobs().mapNotNull { job ->
        val expected = "%arcecojobs_boost_${job.id}_money_multiplier%"
        val effects = job.config.getSubsections("effects").filter { it.getString("id") == "give_money" }
        if (effects.isEmpty() || effects.all { expected in it.getString("args.amount") }) null else job.id
    }

    fun invalidateLeaderboards() {
        leaderboardCache = null
    }

    private fun rankings(): Map<String, List<RankingEntry>> {
        val now = Instant.now()
        leaderboardCache?.takeIf { it.validUntil.isAfter(now) }?.let { return it.rankings }
        val jobs = jobs()
        val players = Bukkit.getOfflinePlayers().filter(OfflinePlayer::hasPlayedBefore)
        val result = linkedMapOf<String, List<RankingEntry>>()
        jobs.forEach { job ->
            result[job.id] = players.filter { participates(it, job) }.map { player ->
                RankingEntry(player.uniqueId, player.name ?: player.uniqueId.toString().take(8), level(player, job), xp(player, job))
            }.sortedWith(compareByDescending<RankingEntry> { it.level }.thenByDescending { it.xp }.thenBy { it.name.lowercase() })
        }
        result[GLOBAL] = players.mapNotNull { player ->
            val participated = jobs.filter { participates(player, it) }
            if (participated.isEmpty()) null else RankingEntry(
                player.uniqueId,
                player.name ?: player.uniqueId.toString().take(8),
                participated.sumOf { level(player, it) },
                participated.sumOf { xp(player, it) },
            )
        }.sortedWith(compareByDescending<RankingEntry> { it.level }.thenByDescending { it.xp }.thenBy { it.name.lowercase() })
        return result.also { leaderboardCache = CachedRankings(now.plus(settings().leaderboardCache), it) }
    }

    private fun rankingKey(job: Job?): String = job?.id ?: GLOBAL
    private fun participates(player: OfflinePlayer, job: Job): Boolean =
        active(player, job) || level(player, job) > 1 || xp(player, job) > 0.0

    companion object {
        private const val GLOBAL = "__global__"
    }
}
