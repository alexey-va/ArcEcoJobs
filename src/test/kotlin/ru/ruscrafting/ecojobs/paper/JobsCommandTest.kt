package ru.ruscrafting.ecojobs.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import ru.ruscrafting.ecojobs.boost.BoostService
import ru.ruscrafting.ecojobs.boost.VoucherService
import ru.ruscrafting.ecojobs.config.AddonSettings
import ru.ruscrafting.ecojobs.config.BoosterRegistry
import ru.ruscrafting.ecojobs.config.JobsLocale
import ru.ruscrafting.ecojobs.integration.EcoJobsBridge

class JobsCommandTest : StringSpec({
    val ecoJobs = mockk<EcoJobsBridge> {
        every { jobs() } returns emptyList()
    }
    val handler = JobsCommand(
        settings = { mockk<AddonSettings>() },
        locale = mockk<JobsLocale>(),
        ecoJobs = ecoJobs,
        boosts = mockk<BoostService>(),
        boosters = { mockk<BoosterRegistry>() },
        vouchers = mockk<VoucherService>(),
        menu = mockk<JobsMenu>(),
        reload = { Result.success(Unit) },
    )
    val command = mockk<Command>()

    "ordinary players are offered only the read-only boost command" {
        val sender = mockk<CommandSender> {
            every { hasPermission(any<String>()) } returns false
        }

        handler.onTabComplete(sender, command, "arcjobs", arrayOf("boost", "")) shouldBe listOf("list")
    }

    "boost administrators receive contextual grant values" {
        val sender = mockk<CommandSender> {
            every { hasPermission("arcecojobs.admin.booster") } returns false
            every { hasPermission("arcecojobs.admin.boost") } returns true
        }

        handler.onTabComplete(sender, command, "arcjobs", arrayOf("boost", "grant", "Player", "")) shouldBe
            listOf("30m", "1h", "1d")
        handler.onTabComplete(sender, command, "arcjobs", arrayOf("boost", "grant", "Player", "1h", "")) shouldBe
            listOf("1.25", "1.5", "2")
        handler.onTabComplete(sender, command, "arcjobs", arrayOf("boost", "grant", "Player", "1h", "2", "")) shouldBe
            listOf("ALL", "XP", "MONEY")
        handler.onTabComplete(sender, command, "arcjobs", arrayOf("boost", "grant", "Player", "1h", "2", "ALL", "")) shouldBe
            listOf("all")
    }

    "booster override completion does not suggest an option twice" {
        val sender = mockk<CommandSender> {
            every { hasPermission(any<String>()) } returns false
            every { hasPermission("arcecojobs.admin.booster") } returns true
        }

        handler.onTabComplete(sender, command, "arcjobs", arrayOf("")) shouldBe
            listOf("help", "boost", "boosters", "booster")

        handler.onTabComplete(
            sender,
            command,
            "arcjobs",
            arrayOf("booster", "give", "Player", "workday", "1", "--duration", "1h", ""),
        ) shouldBe listOf("--multiplier", "--type", "--jobs")
    }
})
