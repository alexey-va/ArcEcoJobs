package ru.ruscrafting.ecojobs.antifarm

import com.willfp.ecojobs.api.event.PlayerJobExpGainEvent
import com.willfp.ecojobs.jobs.Job
import com.willfp.libreforge.triggers.DispatchedTrigger
import com.willfp.libreforge.triggers.Trigger
import com.willfp.libreforge.triggers.TriggerData
import com.willfp.libreforge.triggers.event.TriggerDispatchEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ecojobs.config.AddonSettings
import ru.ruscrafting.ecojobs.config.JobsLocale
import java.util.UUID

class JobRewardGuardTest : StringSpec({
    "real Bukkit dispatch captures the kill before money and XP and leaves other effects untouched" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("JobGuardTest")
            val player = paper.addPlayer("Hunter")
            val config = mockk<AddonSettings> {
                every { blockAfkRewards } returns true
                every { huntGuard } returns HuntGuardSettings()
            }
            val locale = mockk<JobsLocale> { every { render(any(), any(), any()) } returns Component.text("reward paused") }
            var now = 0L
            var afk = false
            val guard = JobRewardGuard({ config }, locale, { true }, afk = { afk }, clock = { now })
            paper.server.pluginManager.registerEvents(guard, plugin)
            val trigger = mockk<Trigger> { every { id } returns "kill" }
            fun kill(): TriggerDispatchEvent {
                val victim = mockk<LivingEntity> {
                    every { uniqueId } returns UUID.randomUUID()
                    every { location } returns player.location
                }
                val data = mockk<TriggerData> {
                    every { this@mockk.player } returns player
                    every { this@mockk.victim } returns victim
                }
                val dispatched = mockk<DispatchedTrigger> {
                    every { this@mockk.trigger } returns trigger
                    every { this@mockk.data } returns data
                }
                return TriggerDispatchEvent(mockk(), dispatched)
            }
            afk = true
            repeat(120) { paper.callEvent(kill()) }
            afk = false
            repeat(120) { i ->
                now = i * 2000L
                val event = kill()
                paper.callEvent(event)
                event.isCancelled shouldBe false
                guard.allows(player, "slayer") shouldBe (i < 119)
                guard.allows(player, "miner") shouldBe true
            }
            afk = true
            guard.allows(player, "miner") shouldBe false
            val job = mockk<Job> { every { id } returns "miner" }
            val xp = PlayerJobExpGainEvent(player, job, 12.5, false)
            paper.callEvent(xp)
            xp.isCancelled shouldBe true
            afk = false
            guard.allows(player, "miner") shouldBe true
        }
    }
})
