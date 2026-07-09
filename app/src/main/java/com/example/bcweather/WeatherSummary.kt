package com.example.bcweather

import java.time.LocalDate
import java.time.ZoneId

private fun currentSummaryTimePoint(): Pair<LocalDate, DayPeriod> {
    val now = java.time.ZonedDateTime.now(ZoneId.of("America/Vancouver"))
    val period = when {
        now.hour < 6 -> DayPeriod.OVERNIGHT
        now.hour < 12 -> DayPeriod.MORNING
        now.hour < 18 -> DayPeriod.AFTERNOON_EVENING
        else -> DayPeriod.OVERNIGHT
    }
    val date = if (now.hour >= 18) now.toLocalDate().plusDays(1) else now.toLocalDate()
    return date to period
}

private fun periodOrder(period: DayPeriod): Int = when (period) {
    DayPeriod.OVERNIGHT -> 0
    DayPeriod.MORNING -> 1
    DayPeriod.AFTERNOON_EVENING -> 2
}

private fun isAtOrAfter(candidate: Pair<LocalDate, DayPeriod>, threshold: Pair<LocalDate, DayPeriod>): Boolean =
    candidate.first.isAfter(threshold.first) ||
        (candidate.first == threshold.first && periodOrder(candidate.second) >= periodOrder(threshold.second))

fun next24HourRows(rows: List<PeriodWeather>): List<PeriodWeather> {
    val currentTimePoint = currentSummaryTimePoint()
    return rows.filter { isAtOrAfter(it.date to it.period, currentTimePoint) }.take(3)
}

fun formatWeatherSummary(rows: List<PeriodWeather>): String {
    val availableRows = rows.filter { it.available }
    if (availableRows.isEmpty()) return "No forecast values available for the next 24h."
    return "Temperature: %.0f–%.0f °C\nPrecipitation: %.1f mm\nMax wind: %.0f km/h".format(
        availableRows.minOf { it.lowC },
        availableRows.maxOf { it.highC },
        availableRows.sumOf { it.precipitationMm },
        availableRows.maxOf { it.maxWindKmh },
    )
}
