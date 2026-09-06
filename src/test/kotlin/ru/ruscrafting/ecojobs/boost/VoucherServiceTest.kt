package ru.ruscrafting.ecojobs.boost

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import org.mockbukkit.mockbukkit.plugin.PluginMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ecojobs.config.AddonSettings
import ru.ruscrafting.ecojobs.config.BoosterItemDefinition
import ru.ruscrafting.ecojobs.config.BoosterPreset
import ru.ruscrafting.ecojobs.config.GuiItems
import ru.ruscrafting.ecojobs.config.JobsLocale
import ru.ruscrafting.ecojobs.config.ShopCurrency
import ru.ruscrafting.ecojobs.domain.BoostType
import ru.ruscrafting.ecojobs.domain.VoucherPayload
import ru.ruscrafting.ecojobs.domain.VoucherSigner
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Locale

class VoucherServiceTest : StringSpec({
    val project = java.nio.file.Path.of(System.getProperty("arcecojobs.projectDir"))
    val dataFolder = Files.createTempDirectory("arcecojobs-voucher-test-")
    lateinit var service: VoucherService
    lateinit var preset: BoosterPreset
    lateinit var paper: MockBukkitTestRuntime
    lateinit var plugin: PluginMock
    val signingKey = ByteArray(32) { it.toByte() }

    beforeTest {
        paper = MockBukkitTestRuntime.open()
        plugin = paper.createSimplePlugin("ArcEcoJobs")
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
            maximumStackedBoostDuration = Duration.ofDays(365),
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
            signingKey = signingKey,
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
        paper.addPlayer("VoucherQA").setLocale(Locale.forLanguageTag("ru-RU"))
    }

    afterTest {
        paper.close()
    }

    afterSpec {
        dataFolder.toFile().deleteRecursively()
    }

    "created voucher preserves configured item metadata and signed payload" {
        val player = paper.server.getPlayer("VoucherQA")!!
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
        valid.payload.signatureVersion shouldBe VoucherPayload.CURRENT_SIGNATURE_VERSION
        valid.payload.legacyRecipientId shouldBe null
        item.itemMeta.persistentDataContainer.has(
            NamespacedKey(plugin, "voucher_recipient"),
            PersistentDataType.STRING,
        ) shouldBe false
        valid.payload.multiplierBasisPoints shouldBe 150
        valid.payload.durationSeconds shouldBe 3_600
        valid.payload.jobs shouldBe setOf("miner")
    }

    "priced vouchers expose a separate currency tier badge" {
        val player = paper.server.getPlayer("VoucherQA")!!
        val moneyLore = service.create(preset.copy(price = ru.ruscrafting.ecojobs.config.BoosterPrice(ShopCurrency.MONEY, java.math.BigDecimal("1500"))), player)
            .itemMeta.lore.orEmpty()
        val tokenLore = service.create(preset.copy(price = ru.ruscrafting.ecojobs.config.BoosterPrice(ShopCurrency.TOKENS, java.math.BigDecimal("90"))), player)
            .itemMeta.lore.orEmpty()
        moneyLore.any { it.contains("ОБЫЧНЫЕ МОНЕТЫ") } shouldBe true
        tokenLore.any { it.contains("ПРЕМИУМ") && it.contains("жетоны") } shouldBe true
    }

    "changing a signed field makes a voucher invalid" {
        val player = paper.server.getPlayer("VoucherQA")!!
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

    "owner-bound v2 vouchers remain valid but changing their signed legacy recipient is rejected" {
        val player = paper.server.getPlayer("VoucherQA")!!
        val item = service.create(preset, player)
        val current = service.inspect(item).shouldBeInstanceOf<VoucherInspection.Valid>().payload
        val legacyRecipient = java.util.UUID.randomUUID()
        val v2 = current.copy(
            signatureVersion = VoucherPayload.OWNER_BOUND_SIGNATURE_VERSION,
            legacyRecipientId = legacyRecipient,
        )
        item.editMeta { meta ->
            val pdc = meta.persistentDataContainer
            pdc.set(NamespacedKey(plugin, "voucher_version"), PersistentDataType.STRING, v2.signatureVersion)
            pdc.set(NamespacedKey(plugin, "voucher_recipient"), PersistentDataType.STRING, legacyRecipient.toString())
            pdc.set(NamespacedKey(plugin, "voucher_signature"), PersistentDataType.BYTE_ARRAY, VoucherSigner(signingKey).sign(v2))
        }

        service.inspect(item).shouldBeInstanceOf<VoucherInspection.Valid>()
        item.editMeta { meta ->
            meta.persistentDataContainer.set(
                NamespacedKey(plugin, "voucher_recipient"),
                PersistentDataType.STRING,
                java.util.UUID.randomUUID().toString(),
            )
        }
        service.inspect(item).shouldBeInstanceOf<VoucherInspection.Invalid>()
    }

    "signed v1 bearer vouchers remain redeemable through the global ledger" {
        val player = paper.server.getPlayer("VoucherQA")!!
        val item = service.create(preset, player)
        val current = service.inspect(item).shouldBeInstanceOf<VoucherInspection.Valid>().payload
        val v1 = current.copy(signatureVersion = VoucherPayload.LEGACY_SIGNATURE_VERSION)
        item.editMeta { meta ->
            val pdc = meta.persistentDataContainer
            pdc.set(NamespacedKey(plugin, "voucher_version"), PersistentDataType.STRING, v1.signatureVersion)
            pdc.set(NamespacedKey(plugin, "voucher_signature"), PersistentDataType.BYTE_ARRAY, VoucherSigner(signingKey).sign(v1))
        }

        service.inspect(item).shouldBeInstanceOf<VoucherInspection.Valid>().payload.signatureVersion shouldBe
            VoucherPayload.LEGACY_SIGNATURE_VERSION
    }

    "each issued voucher has a unique replay identity" {
        val player = paper.server.getPlayer("VoucherQA")!!
        val first = service.inspect(service.create(preset, player)).shouldBeInstanceOf<VoucherInspection.Valid>()
        val second = service.inspect(service.create(preset, player)).shouldBeInstanceOf<VoucherInspection.Valid>()

        (first.payload.voucherId == second.payload.voucherId) shouldBe false
    }
})
