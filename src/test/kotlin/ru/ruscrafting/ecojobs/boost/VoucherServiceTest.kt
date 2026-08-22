package ru.ruscrafting.ecojobs.boost

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import org.mockbukkit.mockbukkit.MockBukkit
import ru.ruscrafting.ecojobs.config.AddonSettings
import ru.ruscrafting.ecojobs.config.BoosterItemDefinition
import ru.ruscrafting.ecojobs.config.BoosterPreset
import ru.ruscrafting.ecojobs.config.GuiItems
import ru.ruscrafting.ecojobs.config.JobsLocale
import ru.ruscrafting.ecojobs.domain.BoostType
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Locale

class VoucherServiceTest : StringSpec({
    val project = java.nio.file.Path.of(System.getProperty("arcecojobs.projectDir"))
    val dataFolder = Files.createTempDirectory("arcecojobs-voucher-test-")
    lateinit var service: VoucherService
    lateinit var preset: BoosterPreset
    lateinit var plugin: org.mockbukkit.mockbukkit.plugin.PluginMock

    beforeSpec {
        val server = MockBukkit.mock()
        plugin = MockBukkit.createMockPlugin("ArcEcoJobs")
        Files.createDirectories(dataFolder.resolve("lang"))
        for (language in listOf("ru", "en")) {
            Files.copy(
                project.resolve("src/main/resources/lang/$language.yml"),
                dataFolder.resolve("lang/$language.yml"),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        val settings = AddonSettings(
            defaultLocale = "ru",
            useClientLocale = true,
            interceptEcoJobsRoot = true,
            leaderboardCache = Duration.ofSeconds(30),
            leaderboardEntriesPerPage = 10,
            boostCacheMillis = 1_000,
            minimumMultiplierBasisPoints = 101,
            maximumMultiplierBasisPoints = 1_000,
            maximumBoostDuration = Duration.ofDays(30),
            requireMoneyPlaceholder = true,
            guiItems = GuiItems.vanilla(),
        )
        val locale = JobsLocale(dataFolder.toFile()) { settings }.also(JobsLocale::validate)
        service = VoucherService(
            plugin = plugin,
            locale = locale,
            settings = { settings },
            validJobIds = { setOf("miner") },
            jobNames = { net.kyori.adventure.text.Component.text("Шахтёр") },
            signingKey = ByteArray(32) { it.toByte() },
        )
        preset = BoosterPreset(
            id = "workday",
            enabled = true,
            type = BoostType.ALL,
            multiplierBasisPoints = 150,
            duration = Duration.ofHours(1),
            jobs = setOf("miner"),
            item = BoosterItemDefinition(
                material = Material.PAPER,
                customModelData = 730,
                itemModel = null,
                nameKey = "booster.workday.name",
                loreKey = "booster.common.lore",
                glint = true,
                unbreakable = false,
                flags = emptySet(),
                persistentData = mapOf("ruscrafting:source" to "arcjobs-test"),
            ),
        )
        server.addPlayer("VoucherQA").setLocale(Locale.forLanguageTag("ru-RU"))
    }

    afterSpec {
        MockBukkit.unmock()
        dataFolder.toFile().deleteRecursively()
    }

    "created voucher preserves configured item metadata and signed payload" {
        val player = MockBukkit.getMock()!!.getPlayer("VoucherQA")!!
        val item = service.create(preset, player)
        val valid = service.inspect(item).shouldBeInstanceOf<VoucherInspection.Valid>()

        item.type shouldBe Material.PAPER
        item.itemMeta.customModelData shouldBe 730
        item.itemMeta.maxStackSize shouldBe 1
        item.itemMeta.persistentDataContainer.get(
            NamespacedKey.fromString("ruscrafting:source")!!,
            PersistentDataType.STRING,
        ) shouldBe "arcjobs-test"
        valid.payload.type shouldBe BoostType.ALL
        valid.payload.recipientId shouldBe player.uniqueId
        valid.payload.multiplierBasisPoints shouldBe 150
        valid.payload.durationSeconds shouldBe 3_600
        valid.payload.jobs shouldBe setOf("miner")
    }

    "changing a signed field makes a voucher invalid" {
        val player = MockBukkit.getMock()!!.getPlayer("VoucherQA")!!
        val item = service.create(preset, player)
        item.editMeta { meta ->
            meta.persistentDataContainer.set(
                NamespacedKey(plugin, "voucher_duration_seconds"),
                PersistentDataType.LONG,
                7_200,
            )
        }

        service.inspect(item).shouldBeInstanceOf<VoucherInspection.Invalid>()
    }

    "changing the signed recipient makes a voucher invalid" {
        val player = MockBukkit.getMock()!!.getPlayer("VoucherQA")!!
        val item = service.create(preset, player)
        item.editMeta { meta ->
            meta.persistentDataContainer.set(
                NamespacedKey(plugin, "voucher_recipient"),
                PersistentDataType.STRING,
                java.util.UUID.randomUUID().toString(),
            )
        }

        service.inspect(item).shouldBeInstanceOf<VoucherInspection.Invalid>()
    }

    "legacy unbound voucher format fails closed" {
        val player = MockBukkit.getMock()!!.getPlayer("VoucherQA")!!
        val item = service.create(preset, player)
        item.editMeta { meta ->
            meta.persistentDataContainer.set(
                NamespacedKey(plugin, "voucher_version"),
                PersistentDataType.STRING,
                "1",
            )
        }

        service.inspect(item) shouldBe VoucherInspection.Legacy
    }

    "each issued voucher has a unique replay identity" {
        val player = MockBukkit.getMock()!!.getPlayer("VoucherQA")!!
        val first = service.inspect(service.create(preset, player)).shouldBeInstanceOf<VoucherInspection.Valid>()
        val second = service.inspect(service.create(preset, player)).shouldBeInstanceOf<VoucherInspection.Valid>()

        (first.payload.voucherId == second.payload.voucherId) shouldBe false
    }
})
