package ru.ruscrafting.ecojobs.config

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File

class JobsLocale(
    dataFolder: File,
    private val settings: () -> AddonSettings,
) {
    private val mini = MiniMessage.miniMessage()
    private var russian = YamlConfiguration.loadConfiguration(dataFolder.resolve("lang/ru.yml"))
    private var english = YamlConfiguration.loadConfiguration(dataFolder.resolve("lang/en.yml"))

    fun reload(dataFolder: File, presets: Collection<BoosterPreset> = emptyList()) {
        val candidateRussian = YamlConfiguration.loadConfiguration(dataFolder.resolve("lang/ru.yml"))
        val candidateEnglish = YamlConfiguration.loadConfiguration(dataFolder.resolve("lang/en.yml"))
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
    ): List<Component> {
        val selected = select(audience)
        val fallback = fallback()
        val strings = listValue(selected, path)
            ?: listValue(fallback, path)
            ?: error("Locale list is missing: $path")
        return strings.map { deserialize(it, audience, values) }
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

    fun validate(presets: Collection<BoosterPreset> = emptyList()) {
        validate(russian, english)
        validateBoosterKeys(russian, english, presets)
    }

    private fun raw(path: String, audience: CommandSender?): String {
        val selected = select(audience)
        return selected.getString(path)?.takeIf(String::isNotBlank)
            ?: fallback().getString(path)?.takeIf(String::isNotBlank)
            ?: error("Locale entry is missing: $path")
    }

    private fun deserialize(raw: String, audience: CommandSender?, values: Map<String, Component>): Component {
        val prefixRaw = select(audience).getString("prefix") ?: fallback().getString("prefix") ?: ""
        val builder = TagResolver.builder().resolver(Placeholder.component("prefix", mini.deserialize(prefixRaw)))
        values.forEach { (name, value) -> builder.resolver(Placeholder.component(name, value)) }
        return mini.deserialize(raw, builder.build())
    }

    private fun select(audience: CommandSender?): YamlConfiguration {
        if (!settings().useClientLocale || audience !is Player) return fallback()
        return if (audience.locale().language.equals("ru", true)) russian else english
    }

    private fun fallback(): YamlConfiguration = if (settings().defaultLocale == "en") english else russian

    private fun listValue(config: YamlConfiguration, path: String): List<String>? = when (val value = config.get(path)) {
        is String -> listOf(value)
        is List<*> -> value.map { it?.toString().orEmpty() }
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
            if (ruValue is String) require(ruValue.isNotBlank()) { "Blank Russian locale entry: $path" }
            if (enValue is String) require(enValue.isNotBlank()) { "Blank English locale entry: $path" }
            if (ruValue is String) mini.deserialize(ruValue)
            if (enValue is String) mini.deserialize(enValue)
            if (ruValue is List<*>) ruValue.filterIsInstance<String>().filter(String::isNotBlank).forEach(mini::deserialize)
            if (enValue is List<*>) enValue.filterIsInstance<String>().filter(String::isNotBlank).forEach(mini::deserialize)
        }
    }

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
}
