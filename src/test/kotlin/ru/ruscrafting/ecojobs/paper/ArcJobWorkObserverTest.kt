package ru.ruscrafting.ecojobs.paper

import com.willfp.ecojobs.api.event.PlayerJobExpGainEvent
import com.willfp.ecojobs.jobs.Job
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.Player
import java.util.UUID

class ArcJobWorkObserverTest : StringSpec({
    "records only positive accepted XP events with the native job id" {
        val playerId = UUID.randomUUID()
        val player = mockk<Player> {
            every { uniqueId } returns playerId
            every { hasMetadata("NPC") } returns false
        }
        val job = mockk<Job> {
            every { id } returns "LumberJack"
        }
        val records = mutableListOf<Pair<UUID, String>>()
        val observer = ArcJobWorkObserver(record = { id, jobId -> records += id to jobId }, afk = { false })

        observer.onExperience(PlayerJobExpGainEvent(player, job, 1.0, false))

        records shouldContainExactly listOf(playerId to "lumberjack")
    }

    "ignores cancelled, zero, negative, and non-finite XP events" {
        val player = mockk<Player> {
            every { uniqueId } returns UUID.randomUUID()
            every { hasMetadata("NPC") } returns false
        }
        val job = mockk<Job> {
            every { id } returns "miner"
        }
        var calls = 0
        val observer = ArcJobWorkObserver(record = { _, _ -> calls++ }, afk = { false })
        val cancelled = PlayerJobExpGainEvent(player, job, 1.0, false).apply { isCancelled = true }

        observer.onExperience(cancelled)
        observer.onExperience(PlayerJobExpGainEvent(player, job, 0.0, false))
        observer.onExperience(PlayerJobExpGainEvent(player, job, -1.0, false))
        observer.onExperience(PlayerJobExpGainEvent(player, job, Double.NaN, false))
        observer.onExperience(PlayerJobExpGainEvent(player, job, Double.POSITIVE_INFINITY, false))

        calls shouldBe 0
    }

    "an unavailable AFK provider breaks continuity instead of recording activity" {
        val id = UUID.randomUUID()
        val player = mockk<Player> {
            every { uniqueId } returns id
            every { hasMetadata("NPC") } returns false
        }
        val job = mockk<Job> { every { this@mockk.id } returns "miner" }
        val breaks = mutableListOf<UUID>()
        var records = 0
        val observer = ArcJobWorkObserver(record = { _, _ -> records++ }, breakPlayer = { breaks += it },
            afk = { error("provider unavailable") })
        observer.onExperience(PlayerJobExpGainEvent(player, job, 1.0, false))
        records shouldBe 0
        breaks shouldContainExactly listOf(id)
    }

    "breaks continuity while AFK" {
        val playerId = UUID.randomUUID()
        val player = mockk<Player> {
            every { uniqueId } returns playerId
            every { hasMetadata("NPC") } returns false
        }
        val job = mockk<Job> {
            every { id } returns "miner"
        }
        val breaks = mutableListOf<UUID>()
        var records = 0
        val observer = ArcJobWorkObserver(
            record = { _, _ -> records++ },
            breakPlayer = { breaks += it },
            afk = { true },
        )

        observer.onExperience(PlayerJobExpGainEvent(player, job, 1.0, false))
        records shouldBe 0
        breaks shouldContainExactly listOf(playerId)
    }
})
