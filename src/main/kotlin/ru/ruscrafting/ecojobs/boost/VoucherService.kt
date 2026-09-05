package ru.ruscrafting.ecojobs.boost

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.ecojobs.config.AddonSettings
import ru.ruscrafting.ecojobs.config.BoosterPreset
import ru.ruscrafting.ecojobs.config.JobsLocale
import ru.ruscrafting.ecojobs.domain.BoostType
import ru.ruscrafting.ecojobs.domain.Multipliers
import ru.ruscrafting.ecojobs.domain.VoucherPayload
import ru.ruscrafting.ecojobs.domain.VoucherSigner
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class VoucherOverrides(
    val duration: Duration? = null,
    val multiplierBasisPoints: Int? = null,
    val type: BoostType? = null,
    val jobs: Set<String>? = null,
)

sealed interface VoucherInspection {
    data object NotVoucher : VoucherInspection
    data class Invalid(val reason: String) : VoucherInspection
    data class Valid(val payload: VoucherPayload) : VoucherInspection
}

class VoucherService(
    private val plugin: JavaPlugin,
    private val locale: JobsLocale,
    private val settings: () -> AddonSettings,
    private val validJobIds: () -> Set<String>,
    private val jobNames: (Set<String>) -> Component,
    signingKey: ByteArray,
) {
    private val signer = VoucherSigner(signingKey)
    private val markerKey = key("voucher_version")
    private val presetKey = key("voucher_preset")
    private val voucherIdKey = key("voucher_id")
    private val recipientIdKey = key("voucher_recipient")
    private val typeKey = key("voucher_type")
    private val multiplierKey = key("voucher_multiplier_bp")
    private val durationKey = key("voucher_duration_seconds")
    private val jobsKey = key("voucher_jobs")
    private val issuedAtKey = key("voucher_issued_at")
    private val signatureKey = key("voucher_signature")

    fun create(preset: BoosterPreset, audience: Player, overrides: VoucherOverrides = VoucherOverrides()): ItemStack {
        val payload = VoucherPayload(
            presetId = preset.id,
            voucherId = UUID.randomUUID(),
            type = overrides.type ?: preset.type,
            multiplierBasisPoints = overrides.multiplierBasisPoints ?: preset.multiplierBasisPoints,
            durationSeconds = (overrides.duration ?: preset.duration).seconds,
            jobs = overrides.jobs ?: preset.jobs,
            issuedAtEpochSecond = Instant.now().epochSecond,
        )
        return create(preset, audience, payload)
    }

    fun create(preset: BoosterPreset, audience: Player, voucherId: UUID, issuedAtEpochSecond: Long): ItemStack = create(
        preset,
        audience,
        VoucherPayload(
            presetId = preset.id,
            voucherId = voucherId,
            type = preset.type,
            multiplierBasisPoints = preset.multiplierBasisPoints,
            durationSeconds = preset.duration.seconds,
            jobs = preset.jobs,
            issuedAtEpochSecond = issuedAtEpochSecond,
        ),
    )

    private fun create(preset: BoosterPreset, audience: Player, payload: VoucherPayload): ItemStack {
        validatePayload(payload)
        val values = displayValues(payload, audience)
        return ItemStack(preset.item.material).apply {
            editMeta { meta ->
                meta.displayName(locale.render(preset.item.nameKey, audience, values).decoration(TextDecoration.ITALIC, false))
                meta.lore(locale.lines(preset.item.loreKey, audience, values).map { it.decoration(TextDecoration.ITALIC, false) })
                preset.item.customModelData?.let(meta::setCustomModelData)
                preset.item.itemModel?.let { raw ->
                    meta.setItemModel(NamespacedKey.fromString(raw) ?: error("Invalid item-model for ${preset.id}: $raw"))
                }
                meta.isUnbreakable = preset.item.unbreakable
                meta.setEnchantmentGlintOverride(preset.item.glint)
                meta.setMaxStackSize(1)
                if (preset.item.flags.isNotEmpty()) meta.addItemFlags(*preset.item.flags.toTypedArray())
                preset.item.persistentData.forEach { (rawKey, value) ->
                    val extraKey = NamespacedKey.fromString(rawKey, plugin) ?: error("Invalid persistent-data key: $rawKey")
                    require(extraKey.namespace != plugin.name.lowercase()) { "Custom persistent-data cannot use the ArcEcoJobs namespace" }
                    meta.persistentDataContainer.set(extraKey, PersistentDataType.STRING, value)
                }
                val pdc = meta.persistentDataContainer
                pdc.set(markerKey, PersistentDataType.STRING, payload.signatureVersion)
                pdc.set(presetKey, PersistentDataType.STRING, payload.presetId)
                pdc.set(voucherIdKey, PersistentDataType.STRING, payload.voucherId.toString())
                pdc.set(typeKey, PersistentDataType.STRING, payload.type.token)
                pdc.set(multiplierKey, PersistentDataType.INTEGER, payload.multiplierBasisPoints)
                pdc.set(durationKey, PersistentDataType.LONG, payload.durationSeconds)
                pdc.set(jobsKey, PersistentDataType.STRING, payload.jobs.sorted().joinToString(","))
                pdc.set(issuedAtKey, PersistentDataType.LONG, payload.issuedAtEpochSecond)
                pdc.set(signatureKey, PersistentDataType.BYTE_ARRAY, signer.sign(payload))
            }
        }
    }

    fun inspect(item: ItemStack?): VoucherInspection {
        val meta = item?.itemMeta ?: return VoucherInspection.NotVoucher
        val pdc = meta.persistentDataContainer
        if (!pdc.has(markerKey, PersistentDataType.STRING)) return VoucherInspection.NotVoucher
        val version = pdc.get(markerKey, PersistentDataType.STRING)
        if (version !in VoucherPayload.SUPPORTED_SIGNATURE_VERSIONS) return VoucherInspection.Invalid("unsupported version")
        return runCatching {
            val payload = VoucherPayload(
                presetId = requireNotNull(pdc.get(presetKey, PersistentDataType.STRING)),
                voucherId = UUID.fromString(requireNotNull(pdc.get(voucherIdKey, PersistentDataType.STRING))),
                type = requireNotNull(BoostType.parse(pdc.get(typeKey, PersistentDataType.STRING))),
                multiplierBasisPoints = requireNotNull(pdc.get(multiplierKey, PersistentDataType.INTEGER)),
                durationSeconds = requireNotNull(pdc.get(durationKey, PersistentDataType.LONG)),
                jobs = requireNotNull(pdc.get(jobsKey, PersistentDataType.STRING)).split(',').map(String::lowercase).toSet(),
                issuedAtEpochSecond = requireNotNull(pdc.get(issuedAtKey, PersistentDataType.LONG)),
                signatureVersion = requireNotNull(version),
                legacyRecipientId = if (version == VoucherPayload.OWNER_BOUND_SIGNATURE_VERSION) {
                    UUID.fromString(requireNotNull(pdc.get(recipientIdKey, PersistentDataType.STRING)))
                } else null,
            )
            validatePayload(payload)
            val signature = requireNotNull(pdc.get(signatureKey, PersistentDataType.BYTE_ARRAY))
            require(signer.verify(payload, signature)) { "signature mismatch" }
            VoucherInspection.Valid(payload)
        }.getOrElse { VoucherInspection.Invalid(it.message ?: "invalid payload") }
    }

    fun remove(player: Player, voucherId: UUID): Boolean {
        val inventory = player.inventory
        for (slot in 0 until inventory.size) {
            val item = inventory.getItem(slot) ?: continue
            if (!matchesVoucherId(item, voucherId)) continue
            val valid = inspect(item) as? VoucherInspection.Valid ?: continue
            if (valid.payload.voucherId != voucherId) continue
            if (item.amount > 1) item.amount -= 1 else inventory.setItem(slot, null)
            return true
        }
        plugin.logger.warning("Spent voucher $voucherId was not present in ${player.name}'s inventory")
        return false
    }

    fun matchesVoucherId(item: ItemStack?, voucherId: UUID): Boolean {
        val data = item?.itemMeta?.persistentDataContainer ?: return false
        if (!data.has(markerKey, PersistentDataType.STRING)) return false
        return data.get(voucherIdKey, PersistentDataType.STRING) == voucherId.toString()
    }

    fun displayValues(payload: VoucherPayload, audience: Player): Map<String, Component> = mapOf(
        "type" to locale.type(payload.type, audience),
        "multiplier" to locale.text(Multipliers.format(payload.multiplierBasisPoints)),
        "duration" to locale.text(locale.duration(Duration.ofSeconds(payload.durationSeconds), audience)),
        "jobs" to if ("all" in payload.jobs) locale.allJobs(audience) else jobNames(payload.jobs),
    )

    private fun validatePayload(payload: VoucherPayload) {
        val bounds = settings()
        require(payload.presetId.matches(Regex("[a-z0-9_-]{1,48}"))) { "invalid preset id" }
        require(payload.multiplierBasisPoints in bounds.minimumMultiplierBasisPoints..bounds.maximumMultiplierBasisPoints) {
            "multiplier outside configured bounds"
        }
        require(payload.durationSeconds in 1..bounds.maximumBoostDuration.seconds) { "duration outside configured bounds" }
        require(payload.jobs.isNotEmpty()) { "no jobs" }
        require("all" in payload.jobs || payload.jobs.all { it in validJobIds() }) { "unknown job" }
        require("all" !in payload.jobs || payload.jobs.size == 1) { "mixed all scope" }
        require(payload.issuedAtEpochSecond <= Instant.now().plusSeconds(300).epochSecond) { "issued in the future" }
    }

    private fun key(value: String): NamespacedKey = NamespacedKey(plugin, value)
}
