package ru.ruscrafting.ecojobs.paper

import io.kotest.core.spec.style.StringSpec
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.ecojobs.boost.BoostService
import ru.ruscrafting.ecojobs.boost.VoucherService
import ru.ruscrafting.ecojobs.config.AddonSettings
import ru.ruscrafting.ecojobs.config.BoosterRegistry
import ru.ruscrafting.ecojobs.config.JobsLocale
import ru.ruscrafting.ecojobs.earnings.EarningsService
import ru.ruscrafting.ecojobs.integration.EcoJobsBridge

class JobsMenuTest : StringSpec({
    "main jobs menu returns to the public server menu" {
        val player = mockk<Player>(relaxed = true)
        val menu = JobsMenu(
            plugin = mockk<JavaPlugin>(),
            settings = { mockk<AddonSettings>() },
            locale = mockk<JobsLocale>(),
            ecoJobs = mockk<EcoJobsBridge>(),
            boosts = mockk<BoostService>(),
            boosters = { mockk<BoosterRegistry>() },
            vouchers = mockk<VoucherService>(),
            earnings = { mockk<EarningsService>() },
            reload = { Result.success(Unit) },
        )

        menu.clickMain(player, JobsMenu.MAIN_MENU_BACK_SLOT)

        verify(exactly = 1) { player.performCommand(JobsMenu.MAIN_MENU_COMMAND) }
    }
})
