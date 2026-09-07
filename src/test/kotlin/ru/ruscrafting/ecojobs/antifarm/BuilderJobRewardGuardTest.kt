package ru.ruscrafting.ecojobs.antifarm

import com.willfp.libreforge.triggers.DispatchedTrigger
import com.willfp.libreforge.triggers.Trigger
import com.willfp.libreforge.triggers.TriggerData
import com.willfp.libreforge.triggers.event.TriggerDispatchEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.block.Block
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ecojobs.config.AddonSettings
import ru.ruscrafting.ecojobs.config.JobsLocale

class BuilderJobRewardGuardTest : StringSpec({
    "one placement decision is stable for XP and money filters and other jobs remain payable" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("Builder")
            val config = mockk<AddonSettings> {
                every { blockAfkRewards } returns true
                every { builderPlacement } returns BuilderPlacementSettings(cooldownMillis = 600_000)
                every { huntGuard } returns HuntGuardSettings()
            }
            val locale = mockk<JobsLocale> {
                every { render(any(), any(), any()) } returns Component.text("reward paused")
            }
            var now = 0L
            var afk = false
            val guard = JobRewardGuard(
                { config },
                locale,
                isSlayerActive = { false },
                isBuilderActive = { true },
                afk = { afk },
                clock = { now },
            )
            val trigger = mockk<Trigger> { every { id } returns "place_block" }
            fun placement(x: Int): TriggerDispatchEvent {
                val block = mockk<Block> {
                    every { world } returns player.world
                    every { this@mockk.x } returns x
                    every { this@mockk.y } returns 64
                    every { this@mockk.z } returns 1
                }
                val data = mockk<TriggerData> {
                    every { this@mockk.player } returns player
                    every { this@mockk.block } returns block
                }
                val dispatched = mockk<DispatchedTrigger> {
                    every { this@mockk.trigger } returns trigger
                    every { this@mockk.data } returns data
                }
                return TriggerDispatchEvent(mockk(), dispatched)
            }

            guard.onTrigger(placement(1))
            guard.allows(player, "builder") shouldBe true
            now = 1L
            guard.onTrigger(placement(1))
            guard.allows(player, "builder") shouldBe false
            guard.allows(player, "builder") shouldBe false
            guard.allows(player, "miner") shouldBe true
            now = 2L
            guard.onTrigger(placement(2))
            guard.allows(player, "builder") shouldBe true
            afk = true
            now = 3L
            guard.onTrigger(placement(3))
            afk = false
            now = 4L
            guard.onTrigger(placement(3))
            guard.allows(player, "builder") shouldBe true
        }
    }
})
