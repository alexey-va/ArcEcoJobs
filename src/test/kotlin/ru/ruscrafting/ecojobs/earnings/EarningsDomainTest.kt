package ru.ruscrafting.ecojobs.earnings

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class EarningsDomainTest : StringSpec({
    "UTC buckets are grouped by the configured Moscow calendar day and hour" {
        val player = UUID.randomUUID()
        val zone = ZoneId.of("Europe/Moscow")
        fun row(instant: String, money: String, xp: String) = HourlyEarnings(
            player,
            "builder",
            EarningsService.epochHour(Instant.parse(instant)),
            EarningsTotals(BigDecimal(money), BigDecimal(xp), 1, 1),
        )
        val report = EarningsReport(
            listOf(
                row("2026-08-23T20:00:00Z", "2.5", "4"),
                row("2026-08-23T21:00:00Z", "3.5", "6"),
            ),
            zone,
        )

        report.forDate(LocalDate.of(2026, 8, 23)).money shouldBe BigDecimal("2.5")
        report.forDate(LocalDate.of(2026, 8, 24)).money shouldBe BigDecimal("3.5")
        report.forHour(LocalDate.of(2026, 8, 24), 0).xp shouldBe BigDecimal("6")
    }
})
