package ru.ruscrafting.ecojobs.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

enum class BoostType(val token: String) {
    ALL("all"),
    XP("xp"),
    MONEY("money");

    fun appliesTo(requested: BoostType): Boolean = this == ALL || this == requested

    companion object {
        fun parse(raw: String?): BoostType? = entries.firstOrNull { it.name.equals(raw, true) || it.token.equals(raw, true) }
    }
}
data class BoostInstance(
    val instanceId: UUID,
    val type: BoostType,
    val multiplierBasisPoints: Int,
    val jobs: Set<String>,
    val expiresAt: Instant,
) {
    val multiplier: Double get() = multiplierBasisPoints / 100.0

    fun applies(jobId: String, requestedType: BoostType): Boolean =
        type.appliesTo(requestedType) && ("all" in jobs || jobId.lowercase() in jobs)
}

data class VoucherPayload(
    val presetId: String,
    val voucherId: UUID,
    val type: BoostType,
    val multiplierBasisPoints: Int,
    val durationSeconds: Long,
    val jobs: Set<String>,
    val issuedAtEpochSecond: Long,
) {
    fun canonical(): String = listOf(
        SIGNATURE_VERSION,
        presetId,
        voucherId.toString(),
        type.token,
        multiplierBasisPoints.toString(),
        durationSeconds.toString(),
        jobs.map(String::lowercase).sorted().joinToString(","),
        issuedAtEpochSecond.toString(),
    ).joinToString("|")

    companion object {
        const val SIGNATURE_VERSION = "1"
    }
}

data class DecodedBoostNode(
    val type: BoostType,
    val multiplierBasisPoints: Int,
    val scope: String,
    val instanceId: UUID,
)

object BoostNodeCodec {
    const val PREFIX = "arcecojobs.boost.v1"
    const val USED_PREFIX = "arcecojobs.voucher.used"
    private val scopePattern = Regex("[a-z0-9_-]{1,48}")

    fun isValidScope(scope: String): Boolean = scopePattern.matches(scope.lowercase())

    fun encode(type: BoostType, multiplierBasisPoints: Int, scope: String, instanceId: UUID): String {
        val normalizedScope = scope.lowercase()
        require(multiplierBasisPoints in 101..100_000) { "Multiplier basis points out of range" }
        require(isValidScope(normalizedScope)) { "Invalid boost scope" }
        return "$PREFIX.${type.token}.$multiplierBasisPoints.$normalizedScope.$instanceId"
    }

    fun decode(key: String): DecodedBoostNode? {
        if (!key.startsWith("$PREFIX.")) return null
        val parts = key.removePrefix("$PREFIX.").split('.')
        if (parts.size != 4) return null
        val type = BoostType.parse(parts[0]) ?: return null
        val multiplier = parts[1].toIntOrNull()?.takeIf { it in 101..100_000 } ?: return null
        val scope = parts[2].takeIf(scopePattern::matches) ?: return null
        val instance = runCatching { UUID.fromString(parts[3]) }.getOrNull() ?: return null
        return DecodedBoostNode(type, multiplier, scope, instance)
    }

    fun used(voucherId: UUID): String = "$USED_PREFIX.$voucherId"
}

object DurationParser {
    private val token = Regex("(\\d+)([dhms])", RegexOption.IGNORE_CASE)

    fun parse(raw: String): Duration? {
        val compact = raw.trim().replace(" ", "").lowercase()
        if (compact.isBlank()) return null
        var cursor = 0
        var seconds = 0L
        val seen = mutableSetOf<Char>()
        for (match in token.findAll(compact)) {
            if (match.range.first != cursor) return null
            cursor = match.range.last + 1
            val unit = match.groupValues[2][0]
            if (!seen.add(unit)) return null
            val amount = match.groupValues[1].toLongOrNull() ?: return null
            val factor = when (unit) {
                'd' -> 86_400L
                'h' -> 3_600L
                'm' -> 60L
                's' -> 1L
                else -> return null
            }
            val contribution = runCatching { Math.multiplyExact(amount, factor) }.getOrNull() ?: return null
            seconds = runCatching { Math.addExact(seconds, contribution) }.getOrNull() ?: return null
        }
        if (cursor != compact.length || seconds <= 0) return null
        return Duration.ofSeconds(seconds)
    }

    fun format(duration: Duration, russian: Boolean = false): String {
        var seconds = duration.seconds.coerceAtLeast(0)
        val parts = mutableListOf<String>()
        val units = if (russian) mapOf('d' to "д", 'h' to "ч", 'm' to "м", 's' to "с")
        else mapOf('d' to "d", 'h' to "h", 'm' to "m", 's' to "s")
        val days = seconds / 86_400
        if (days > 0) {
            parts += "$days${units.getValue('d')}"
            seconds %= 86_400
        }
        val hours = seconds / 3_600
        if (hours > 0) {
            parts += "$hours${units.getValue('h')}"
            seconds %= 3_600
        }
        val minutes = seconds / 60
        if (minutes > 0) {
            parts += "$minutes${units.getValue('m')}"
            seconds %= 60
        }
        if (seconds > 0 || parts.isEmpty()) parts += "$seconds${units.getValue('s')}"
        return parts.joinToString(" ")
    }
}

object Multipliers {
    fun toBasisPoints(value: Double): Int = (value * 100.0).roundToInt()

    fun format(basisPoints: Int): String {
        val raw = basisPoints / 100.0
        val rendered = if (raw % 1.0 == 0.0) raw.toInt().toString() else raw.toString().trimEnd('0').trimEnd('.')
        return "${rendered}×"
    }

    fun effective(instances: Collection<BoostInstance>, jobId: String, requestedType: BoostType): Double =
        instances.asSequence()
            .filter { it.applies(jobId, requestedType) }
            .maxOfOrNull(BoostInstance::multiplier)
            ?: 1.0
}
