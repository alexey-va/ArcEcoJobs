package ru.ruscrafting.ecojobs.earnings

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class EarningsTotals(
    val money: BigDecimal = BigDecimal.ZERO,
    val xp: BigDecimal = BigDecimal.ZERO,
    val moneyEvents: Long = 0,
    val xpEvents: Long = 0,
) {
    operator fun plus(other: EarningsTotals): EarningsTotals = EarningsTotals(
        money = money.add(other.money),
        xp = xp.add(other.xp),
        moneyEvents = moneyEvents + other.moneyEvents,
        xpEvents = xpEvents + other.xpEvents,
    )

    val empty: Boolean
        get() = money.signum() == 0 && xp.signum() == 0
}

data class HourlyEarnings(
    val playerId: UUID,
    val jobId: String,
    val epochHour: Long,
    val totals: EarningsTotals,
)

data class EarningsReport(
    val hourly: List<HourlyEarnings>,
    val zoneId: ZoneId,
) {
    val total: EarningsTotals = hourly.fold(EarningsTotals()) { total, entry -> total + entry.totals }

    fun forDate(date: LocalDate): EarningsTotals = hourly
        .asSequence()
        .filter { it.localDate(zoneId) == date }
        .fold(EarningsTotals()) { total, entry -> total + entry.totals }

    fun forHour(date: LocalDate, hour: Int): EarningsTotals {
        require(hour in 0..23) { "hour must be between 0 and 23" }
        return hourly
            .asSequence()
            .filter {
                val local = it.localDateTime(zoneId)
                local.toLocalDate() == date && local.hour == hour
            }
            .fold(EarningsTotals()) { total, entry -> total + entry.totals }
    }

    fun since(instant: Instant): EarningsTotals = hourly
        .asSequence()
        .filter { it.epochHour >= instant.epochSecond.floorDiv(SECONDS_PER_HOUR) }
        .fold(EarningsTotals()) { total, entry -> total + entry.totals }
}

internal fun HourlyEarnings.localDate(zoneId: ZoneId): LocalDate = localDateTime(zoneId).toLocalDate()

internal fun HourlyEarnings.localDateTime(zoneId: ZoneId) =
    Instant.ofEpochSecond(epochHour * SECONDS_PER_HOUR).atZone(zoneId)

internal const val SECONDS_PER_HOUR = 3_600L
