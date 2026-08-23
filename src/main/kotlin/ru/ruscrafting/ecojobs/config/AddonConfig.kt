package ru.ruscrafting.ecojobs.config

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemFlag
import ru.ruscrafting.ecojobs.domain.BoostType
import ru.ruscrafting.ecojobs.domain.DurationParser
import ru.ruscrafting.ecojobs.domain.Multipliers
import java.io.File
import java.time.Duration

internal fun loadYamlStrict(file: File): YamlConfiguration = YamlConfiguration().apply { load(file) }

data class AddonSettings(
    val defaultLocale: String,
    val useClientLocale: Boolean,
    val interceptEcoJobsRoot: Boolean,
    val leaderboardCache: Duration,
    val leaderboardEntriesPerPage: Int,
    val boostCacheMillis: Long,
    val minimumMultiplierBasisPoints: Int,
    val maximumMultiplierBasisPoints: Int,
    val maximumBoostDuration: Duration,
    val maximumStackedBoostDuration: Duration,
    val requireMoneyPlaceholder: Boolean,
    val exploration: ExplorationSettings = ExplorationSettings.disabled(),
    val guiItems: GuiItems,
    val redemptionStorage: RedemptionStorageSettings = RedemptionStorageSettings.disabled(),
) {
    companion object {
        fun load(file: File): AddonSettings {
            val yaml = loadYamlStrict(file)
            val defaultLocale = yaml.getString("locale.default", "ru")!!.lowercase()
            require(defaultLocale in setOf("ru", "en")) { "locale.default must be ru or en" }
            val min = Multipliers.toBasisPoints(yaml.getDouble("boosts.minimum-multiplier", 1.01))
            val max = Multipliers.toBasisPoints(yaml.getDouble("boosts.maximum-multiplier", 10.0))
            require(min in 101..100_000 && max in min..100_000) { "Invalid boost multiplier bounds" }
            require(yaml.getString("boosts.stacking", "MAX").equals("MAX", true)) {
                "Only MAX boost stacking is supported"
            }
            val leaderboardCacheSeconds = yaml.getLong("leaderboards.cache-seconds", 300)
            require(leaderboardCacheSeconds in 30..3_600) {
                "leaderboards.cache-seconds must be between 30 and 3600"
            }
            val leaderboardEntriesPerPage = yaml.getInt("leaderboards.entries-per-page", 10)
            require(leaderboardEntriesPerPage in 5..28) {
                "leaderboards.entries-per-page must be between 5 and 28"
            }
            val boostCacheMillis = yaml.getLong("boosts.cache-millis", 1000)
            require(boostCacheMillis in 100..10_000) {
                "boosts.cache-millis must be between 100 and 10000"
            }
            val maximumBoostDuration = DurationParser.parse(yaml.getString("boosts.maximum-duration", "30d")!!)
                ?: error("boosts.maximum-duration is invalid")
            val maximumStackedBoostDuration = DurationParser.parse(
                yaml.getString("boosts.maximum-stacked-duration", "365d")!!,
            ) ?: error("boosts.maximum-stacked-duration is invalid")
            require(maximumStackedBoostDuration >= maximumBoostDuration) {
                "boosts.maximum-stacked-duration must not be shorter than boosts.maximum-duration"
            }
            val redemptionStorage = RedemptionStorageSettings.load(yaml)
            val exploration = ExplorationSettings.load(yaml)
            require(!exploration.enabled || redemptionStorage.enabled) {
                "exploration requires redemptions.mysql.enabled because discoveries use the shared ArcEcoJobs database"
            }
            return AddonSettings(
                defaultLocale = defaultLocale,
                useClientLocale = yaml.getBoolean("locale.use-client-locale", true),
                interceptEcoJobsRoot = yaml.getBoolean("commands.intercept-ecojobs-root", true),
                leaderboardCache = Duration.ofSeconds(leaderboardCacheSeconds),
                leaderboardEntriesPerPage = leaderboardEntriesPerPage,
                boostCacheMillis = boostCacheMillis,
                minimumMultiplierBasisPoints = min,
                maximumMultiplierBasisPoints = max,
                maximumBoostDuration = maximumBoostDuration,
                maximumStackedBoostDuration = maximumStackedBoostDuration,
                requireMoneyPlaceholder = yaml.getBoolean("boosts.require-money-placeholder", true),
                exploration = exploration,
                guiItems = GuiItems.load(yaml),
                redemptionStorage = redemptionStorage,
            )
        }
    }
}

data class ExplorationSettings(
    val enabled: Boolean,
    val maximumInFlight: Int,
) {
    companion object {
        fun disabled(): ExplorationSettings = ExplorationSettings(
            enabled = false,
            maximumInFlight = 256,
        )

        fun load(yaml: YamlConfiguration): ExplorationSettings = ExplorationSettings(
            enabled = yaml.getBoolean("exploration.enabled", false),
            maximumInFlight = yaml.getInt("exploration.maximum-in-flight", 256).also {
                require(it in 16..4_096) { "exploration.maximum-in-flight must be between 16 and 4096" }
            },
        )
    }
}

data class RedemptionStorageSettings(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
    val sslMode: String,
    val minimumIdle: Int,
    val maximumPoolSize: Int,
    val connectionTimeoutMs: Long,
    val validationTimeoutMs: Long,
    val maxLifetimeMs: Long,
) {
    init {
        require(host.matches(Regex("[A-Za-z0-9._:\\[\\]-]{1,253}"))) { "redemptions.mysql.host is invalid" }
        require(port in 1..65_535) { "redemptions.mysql.port must be between 1 and 65535" }
        require(database.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "redemptions.mysql.database is invalid" }
        require(username.isNotBlank()) { "redemptions.mysql.username must not be blank" }
        require(sslMode in setOf("DISABLED", "REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY")) {
            "redemptions.mysql.ssl-mode is invalid"
        }
        require(minimumIdle in 0..maximumPoolSize) { "redemptions.mysql.pool.minimum-idle is invalid" }
        require(maximumPoolSize in 1..8) { "redemptions.mysql.pool.maximum-size must be between 1 and 8" }
        require(connectionTimeoutMs in 250..60_000) { "redemptions.mysql.pool.connection-timeout-ms is invalid" }
        require(validationTimeoutMs in 250..connectionTimeoutMs) { "redemptions.mysql.pool.validation-timeout-ms is invalid" }
        require(maxLifetimeMs == 0L || maxLifetimeMs >= 30_000) { "redemptions.mysql.pool.max-lifetime-ms is invalid" }
        if (enabled) require(password.isNotBlank()) { "redemptions.mysql.password must not be blank when enabled" }
    }

    companion object {
        fun disabled(): RedemptionStorageSettings = RedemptionStorageSettings(
            enabled = false,
            host = "127.0.0.1",
            port = 3306,
            database = "minecraft",
            username = "minecraft",
            password = "",
            sslMode = "REQUIRED",
            minimumIdle = 1,
            maximumPoolSize = 4,
            connectionTimeoutMs = 10_000,
            validationTimeoutMs = 5_000,
            maxLifetimeMs = 1_700_000,
        )

        fun load(yaml: YamlConfiguration): RedemptionStorageSettings {
            val path = "redemptions.mysql"
            return RedemptionStorageSettings(
                enabled = yaml.getBoolean("$path.enabled", false),
                host = yaml.getString("$path.host", "127.0.0.1")!!,
                port = yaml.getInt("$path.port", 3306),
                database = yaml.getString("$path.database", "minecraft")!!,
                username = yaml.getString("$path.username", "minecraft")!!,
                password = yaml.getString("$path.password", "")!!,
                sslMode = yaml.getString("$path.ssl-mode", "REQUIRED")!!.uppercase(),
                minimumIdle = yaml.getInt("$path.pool.minimum-idle", 1),
                maximumPoolSize = yaml.getInt("$path.pool.maximum-size", 4),
                connectionTimeoutMs = yaml.getLong("$path.pool.connection-timeout-ms", 10_000),
                validationTimeoutMs = yaml.getLong("$path.pool.validation-timeout-ms", 5_000),
                maxLifetimeMs = yaml.getLong("$path.pool.max-lifetime-ms", 1_700_000),
            )
        }
    }
}

data class GuiItemDefinition(
    val material: Material,
    val customModelData: Int?,
)

data class GuiItems(
    val background: GuiItemDefinition,
    val back: GuiItemDefinition,
    val previous: GuiItemDefinition,
    val next: GuiItemDefinition,
    val close: GuiItemDefinition,
    val confirm: GuiItemDefinition,
    val cancel: GuiItemDefinition,
    val refresh: GuiItemDefinition,
    val catalog: GuiItemDefinition,
) {
    companion object {
        fun vanilla(): GuiItems = GuiItems(
            background = GuiItemDefinition(Material.GRAY_STAINED_GLASS_PANE, null),
            back = GuiItemDefinition(Material.BLUE_STAINED_GLASS_PANE, null),
            previous = GuiItemDefinition(Material.BLUE_STAINED_GLASS_PANE, null),
            next = GuiItemDefinition(Material.BLUE_STAINED_GLASS_PANE, null),
            close = GuiItemDefinition(Material.RED_STAINED_GLASS_PANE, null),
            confirm = GuiItemDefinition(Material.GREEN_STAINED_GLASS_PANE, null),
            cancel = GuiItemDefinition(Material.RED_STAINED_GLASS_PANE, null),
            refresh = GuiItemDefinition(Material.REPEATER, null),
            catalog = GuiItemDefinition(Material.CRAFTING_TABLE, null),
        )

        fun load(yaml: YamlConfiguration): GuiItems {
            val fallback = vanilla()
            return GuiItems(
                background = loadItem(
                    yaml,
                    "gui.items.background",
                    fallback.background,
                    legacyMaterialPath = "gui.filler-material",
                ),
                back = loadItem(yaml, "gui.items.back", fallback.back),
                previous = loadItem(yaml, "gui.items.previous", fallback.previous),
                next = loadItem(yaml, "gui.items.next", fallback.next),
                close = loadItem(yaml, "gui.items.close", fallback.close),
                confirm = loadItem(yaml, "gui.items.confirm", fallback.confirm),
                cancel = loadItem(yaml, "gui.items.cancel", fallback.cancel),
                refresh = loadItem(yaml, "gui.items.refresh", fallback.refresh),
                catalog = loadItem(yaml, "gui.items.catalog", fallback.catalog),
            )
        }

        private fun loadItem(
            yaml: YamlConfiguration,
            path: String,
            fallback: GuiItemDefinition,
            legacyMaterialPath: String? = null,
        ): GuiItemDefinition {
            val rawMaterial = yaml.getString("$path.material")
                ?: legacyMaterialPath?.let(yaml::getString)
                ?: fallback.material.name
            val material = Material.matchMaterial(rawMaterial)
                ?: error("$path.material is invalid")
            require(material.isItem && !material.isAir) { "$path.material must be a usable item" }
            val rawModelData = yaml.get("$path.custom-model-data")
            val modelData = when (rawModelData) {
                null -> null
                is Number -> {
                    val numeric = rawModelData.toDouble()
                    val integral = rawModelData.toLong()
                    require(numeric.isFinite() && numeric == integral.toDouble() && integral in 0..Int.MAX_VALUE.toLong()) {
                        "$path.custom-model-data must be a non-negative integer"
                    }
                    integral.toInt().takeIf { it > 0 }
                }
                else -> error("$path.custom-model-data must be an integer")
            }
            return GuiItemDefinition(material, modelData)
        }
    }
}

data class BoosterItemDefinition(
    val material: Material,
    val customModelData: Int?,
    val itemModel: String?,
    val nameKey: String,
    val loreKey: String,
    val glint: Boolean,
    val unbreakable: Boolean,
    val flags: Set<ItemFlag>,
    val persistentData: Map<String, String>,
)

data class BoosterPreset(
    val id: String,
    val enabled: Boolean,
    val type: BoostType,
    val multiplierBasisPoints: Int,
    val duration: Duration,
    val jobs: Set<String>,
    val item: BoosterItemDefinition,
)

class BoosterRegistry private constructor(private val presets: Map<String, BoosterPreset>) {
    fun values(): List<BoosterPreset> = presets.values.sortedBy(BoosterPreset::id)
    fun get(id: String): BoosterPreset? = presets[id.lowercase()]

    companion object {
        private val idPattern = Regex("[a-z0-9_-]{1,48}")

        fun load(file: File, settings: AddonSettings, validJobIds: Set<String>): BoosterRegistry {
            val yaml = loadYamlStrict(file)
            val section = yaml.getConfigurationSection("boosters") ?: error("boosters.yml has no boosters section")
            val parsed = linkedMapOf<String, BoosterPreset>()
            for (rawId in section.getKeys(false)) {
                val id = rawId.lowercase()
                require(idPattern.matches(id)) { "Invalid booster id: $rawId" }
                require(id !in parsed) { "Duplicate booster id after normalization: $rawId" }
                val path = "boosters.$rawId"
                val type = BoostType.parse(yaml.getString("$path.type", "ALL")) ?: error("Invalid type for booster $id")
                val multiplier = Multipliers.toBasisPoints(yaml.getDouble("$path.multiplier"))
                require(multiplier in settings.minimumMultiplierBasisPoints..settings.maximumMultiplierBasisPoints) {
                    "Multiplier for booster $id is outside configured bounds"
                }
                val duration = DurationParser.parse(yaml.getString("$path.duration") ?: "")
                    ?: error("Invalid duration for booster $id")
                require(duration <= settings.maximumBoostDuration) { "Duration for booster $id exceeds the configured maximum" }
                val jobs = yaml.getStringList("$path.jobs").map(String::lowercase).toSet()
                require(jobs.isNotEmpty()) { "Booster $id has no jobs" }
                require("all" in jobs || jobs.all { it in validJobIds }) { "Booster $id references an unknown job" }
                require("all" !in jobs || jobs.size == 1) { "Booster $id cannot combine all with job IDs" }
                val material = Material.matchMaterial(yaml.getString("$path.item.material") ?: "")
                    ?: error("Invalid material for booster $id")
                val rawModelData = yaml.get("$path.item.custom-model-data")
                val modelData = when (rawModelData) {
                    null -> null
                    is Number -> {
                        val numeric = rawModelData.toDouble()
                        val integral = rawModelData.toLong()
                        require(numeric.isFinite() && numeric == integral.toDouble() && integral in 0..Int.MAX_VALUE.toLong()) {
                            "custom-model-data for booster $id must be a non-negative integer"
                        }
                        integral.toInt().takeIf { it > 0 }
                    }
                    else -> error("custom-model-data for booster $id must be an integer")
                }
                val itemModel = yaml.getString("$path.item.item-model")?.takeIf(String::isNotBlank)?.also { raw ->
                    require(NamespacedKey.fromString(raw) != null) { "Invalid item-model for booster $id: $raw" }
                }
                val flags = yaml.getStringList("$path.item.flags").map { rawFlag ->
                    runCatching { ItemFlag.valueOf(rawFlag.uppercase()) }
                        .getOrElse { error("Invalid item flag for booster $id: $rawFlag") }
                }.toSet()
                val pdcSection = yaml.getConfigurationSection("$path.item.persistent-data")
                val pdc = pdcSection?.getKeys(false)?.associateWith { pdcSection.getString(it).orEmpty() }.orEmpty()
                pdc.keys.forEach { rawKey ->
                    require(':' in rawKey && NamespacedKey.fromString(rawKey) != null) {
                        "Invalid persistent-data key for booster $id: $rawKey"
                    }
                    require(!rawKey.startsWith("arcecojobs:")) {
                        "Custom persistent-data cannot use the ArcEcoJobs namespace"
                    }
                }
                parsed[id] = BoosterPreset(
                    id = id,
                    enabled = yaml.getBoolean("$path.enabled", true),
                    type = type,
                    multiplierBasisPoints = multiplier,
                    duration = duration,
                    jobs = jobs,
                    item = BoosterItemDefinition(
                        material = material,
                        customModelData = modelData,
                        itemModel = itemModel,
                        nameKey = yaml.getString("$path.item.name-key") ?: error("Booster $id has no item.name-key"),
                        loreKey = yaml.getString("$path.item.lore-key") ?: error("Booster $id has no item.lore-key"),
                        glint = yaml.getBoolean("$path.item.glint", false),
                        unbreakable = yaml.getBoolean("$path.item.unbreakable", false),
                        flags = flags,
                        persistentData = pdc,
                    ),
                )
            }
            require(parsed.isNotEmpty()) { "No booster presets are configured" }
            return BoosterRegistry(parsed)
        }
    }
}
