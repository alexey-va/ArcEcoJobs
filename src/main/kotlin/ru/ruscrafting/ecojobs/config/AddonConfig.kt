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
    val requireMoneyPlaceholder: Boolean,
    val fillerMaterial: Material,
) {
    companion object {
        fun load(file: File): AddonSettings {
            val yaml = YamlConfiguration.loadConfiguration(file)
            val defaultLocale = yaml.getString("locale.default", "ru")!!.lowercase()
            require(defaultLocale in setOf("ru", "en")) { "locale.default must be ru or en" }
            val min = Multipliers.toBasisPoints(yaml.getDouble("boosts.minimum-multiplier", 1.01))
            val max = Multipliers.toBasisPoints(yaml.getDouble("boosts.maximum-multiplier", 10.0))
            require(min in 101..100_000 && max in min..100_000) { "Invalid boost multiplier bounds" }
            require(yaml.getString("boosts.stacking", "MAX").equals("MAX", true)) {
                "Only MAX boost stacking is supported"
            }
            return AddonSettings(
                defaultLocale = defaultLocale,
                useClientLocale = yaml.getBoolean("locale.use-client-locale", true),
                interceptEcoJobsRoot = yaml.getBoolean("commands.intercept-ecojobs-root", true),
                leaderboardCache = Duration.ofSeconds(yaml.getLong("leaderboards.cache-seconds", 30).coerceIn(5, 3600)),
                leaderboardEntriesPerPage = yaml.getInt("leaderboards.entries-per-page", 10).coerceIn(5, 28),
                boostCacheMillis = yaml.getLong("boosts.cache-millis", 1000).coerceIn(100, 10_000),
                minimumMultiplierBasisPoints = min,
                maximumMultiplierBasisPoints = max,
                maximumBoostDuration = DurationParser.parse(yaml.getString("boosts.maximum-duration", "30d")!!)
                    ?: error("boosts.maximum-duration is invalid"),
                requireMoneyPlaceholder = yaml.getBoolean("boosts.require-money-placeholder", true),
                fillerMaterial = Material.matchMaterial(yaml.getString("gui.filler-material", "BLACK_STAINED_GLASS_PANE")!!)
                    ?: error("gui.filler-material is invalid"),
            )
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
            val yaml = YamlConfiguration.loadConfiguration(file)
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
