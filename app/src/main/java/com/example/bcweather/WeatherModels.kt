package com.example.bcweather

import java.time.LocalDate

enum class DayPeriod(val label: String) { OVERNIGHT("Overnight"), MORNING("Morning"), AFTERNOON_EVENING("Afternoon/evening") }

data class PeriodWeather(
    val location: String,
    val date: LocalDate,
    val period: DayPeriod,
    val lowC: Double,
    val highC: Double,
    val precipitationMm: Double,
    val maxWindKmh: Double,
    val observed: Boolean,
    val available: Boolean = observed,
)
