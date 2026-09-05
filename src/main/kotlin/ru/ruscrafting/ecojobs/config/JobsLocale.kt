package ru.ruscrafting.ecojobs.config

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import ru.ruscrafting.ecojobs.domain.DurationParser
import java.io.File
import java.time.Duration

class JobsLocale(
    dataFolder: File,
    private val settings: () -> AddonSettings,
) {
    private val mini = MiniMessage.miniMessage()
    private var russian = loadYamlStrict(dataFolder.resolve("lang/ru.yml"))
    private var english = loadYamlStrict(dataFolder.resolve("lang/en.yml"))
    private val bundled = listOf("ru", "en").associateWith { language ->
        requireNotNull(javaClass.getResourceAsStream("/lang/$language.yml")).reader(Charsets.UTF_8).use {
            YamlConfiguration().apply { load(it) }
        }
    }

    fun reload(dataFolder: File, presets: Collection<BoosterPreset> = emptyList()) {
        val candidateRussian = loadYamlStrict(dataFolder.resolve("lang/ru.yml"))
        val candidateEnglish = loadYamlStrict(dataFolder.resolve("lang/en.yml"))
        validate(candidateRussian, candidateEnglish)
        validateBoosterKeys(candidateRussian, candidateEnglish, presets)
        russian = candidateRussian
        english = candidateEnglish
    }

    fun render(
        path: String,
        audience: CommandSender? = null,
        values: Map<String, Component> = emptyMap(),
    ): Component = deserialize(raw(path, audience), audience, values)

    fun lines(
        path: String,
        audience: CommandSender? = null,
        values: Map<String, Component> = emptyMap(),
        blocks: Map<String, List<Component>> = emptyMap(),
    ): List<Component> {
        val selected = select(audience)
        val fallback = fallback()
        val strings = listValue(selected, path)
            ?: listValue(fallback, path)
            ?: listValue(bundled.getValue(if (usesRussian(audience)) "ru" else "en"), path)
            ?: error("Locale list is missing: $path")
        return strings.flatMap { raw ->
            blocks.entries.firstOrNull { (name) -> raw == "<$name>" }?.value
                ?: listOf(deserialize(raw, audience, values))
        }
    }

    fun text(value: Any?): Component = Component.text(value?.toString().orEmpty())

    fun type(type: ru.ruscrafting.ecojobs.domain.BoostType, audience: CommandSender?): Component = render(
        when (type) {
            ru.ruscrafting.ecojobs.domain.BoostType.ALL -> "common.type-all"
            ru.ruscrafting.ecojobs.domain.BoostType.XP -> "common.type-xp"
            ru.ruscrafting.ecojobs.domain.BoostType.MONEY -> "common.type-money"
        },
        audience,
    )

    fun allJobs(audience: CommandSender?): Component = render("common.all-jobs", audience)

    fun duration(duration: Duration, audience: CommandSender?): String = DurationParser.format(
        duration,
        russian = usesRussian(audience),
    )

    fun validate(presets: Collection<BoosterPreset> = emptyList()) {
        validate(russian, english)
        validateBoosterKeys(russian, english, presets)
    }

    private fun raw(path: String, audience: CommandSender?): String {
        val selected = select(audience)
        return selected.getString(path)?.takeIf(String::isNotBlank)
            ?: fallback().getString(path)?.takeIf(String::isNotBlank)
            ?: bundled.getValue(if (usesRussian(audience)) "ru" else "en").getString(path)?.takeIf(String::isNotBlank)
            ?: error("Locale entry is missing: $path")
    }

    private fun deserialize(raw: String, audience: CommandSender?, values: Map<String, Component>): Component {
        val prefixRaw = select(audience).getString("prefix") ?: fallback().getString("prefix") ?: ""
        val builder = TagResolver.builder().resolver(Placeholder.component("prefix", mini.deserialize(prefixRaw)))
        values.forEach { (name, value) -> builder.resolver(Placeholder.component(name, value)) }
        return mini.deserialize(raw, builder.build())
    }

    private fun select(audience: CommandSender?): YamlConfiguration {
        return if (usesRussian(audience)) russian else english
    }

    private fun usesRussian(audience: CommandSender?): Boolean {
        if (!settings().useClientLocale || audience !is Player) return settings().defaultLocale == "ru"
        return audience.locale().language.equals("ru", true)
    }

    private fun fallback(): YamlConfiguration = if (settings().defaultLocale == "en") english else russian

    private fun listValue(config: YamlConfiguration, path: String): List<String>? = when (val value = config.get(path)) {
        is String -> listOf(value)
        is List<*> -> value.map { requireNotNull(it as? String) { "Locale list contains a non-string value: $path" } }
        else -> null
    }

    private fun validate(ru: YamlConfiguration, en: YamlConfiguration) {
        val ruLeaves = leaves(ru)
        val enLeaves = leaves(en)
        require(ruLeaves == enLeaves) {
            "Locale key mismatch: only-ru=${ruLeaves - enLeaves}, only-en=${enLeaves - ruLeaves}"
        }
        ruLeaves.forEach { path ->
            val ruValue = ru.get(path)
            val enValue = en.get(path)
            when {
                ruValue is String && enValue is String -> {
                    require(ruValue.isNotBlank()) { "Blank Russian locale entry: $path" }
                    require(enValue.isNotBlank()) { "Blank English locale entry: $path" }
                    validateRow(path, ruValue, enValue)
                }
                ruValue is List<*> && enValue is List<*> -> {
                    require(ruValue.size == enValue.size) { "Locale list size mismatch at $path" }
                    require(ruValue.all { it is String }) { "Russian locale list contains a non-string value: $path" }
                    require(enValue.all { it is String }) { "English locale list contains a non-string value: $path" }
                    ruValue.zip(enValue).forEachIndexed { index, (ruRow, enRow) ->
                        validateRow("$path[$index]", ruRow as String, enRow as String)
                    }
                }
                else -> error("Locale value type mismatch at $path")
            }
        }
    }

    private fun validateRow(path: String, ru: String, en: String) {
        if (ru.isNotBlank()) mini.deserialize(ru)
        if (en.isNotBlank()) mini.deserialize(en)
        val ruTags = tags(ru)
        val enTags = tags(en)
        require(ruTags == enTags) { "Locale placeholder mismatch at $path: ru=$ruTags, en=$enTags" }
    }

    private fun tags(value: String): Map<String, Int> = TAG.findAll(value)
        .map { it.groupValues[1].lowercase() }
        .groupingBy { it }
        .eachCount()

    private fun validateBoosterKeys(
        ru: YamlConfiguration,
        en: YamlConfiguration,
        presets: Collection<BoosterPreset>,
    ) {
        presets.forEach { preset ->
            for ((language, config) in listOf("ru" to ru, "en" to en)) {
                require(config.getString(preset.item.nameKey)?.isNotBlank() == true) {
                    "$language locale is missing booster name key ${preset.item.nameKey}"
                }
                val lore = config.getList(preset.item.loreKey)
                require(lore != null && lore.all { it is String }) {
                    "$language locale is missing booster lore list ${preset.item.loreKey}"
                }
            }
        }
    }

    private fun leaves(section: ConfigurationSection): Set<String> = section.getKeys(true)
        .filter { section.get(it) !is ConfigurationSection }
        .toSet()

    companion object {
        private val TAG = Regex("<([a-zA-Z][a-zA-Z0-9_-]*)")
    }
}
