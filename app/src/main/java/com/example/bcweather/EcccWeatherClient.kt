package com.example.bcweather

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.*

class EcccWeatherClient {
    fun fetch(location: Location): List<PeriodWeather> {
        val end = LocalDate.now().plusDays(3)
        val start = LocalDate.now().minusDays(3)
        // MSC GeoMet OGC API Features endpoint. The app requests hourly temperature, precipitation,
        // and wind values near the selected BC coordinate, then aggregates them into daylight-aware periods.
        val url = URL("https://api.weather.gc.ca/collections/climate-hourly/items?f=json&limit=500&bbox=${location.lon - .25},${location.lat - .25},${location.lon + .25},${location.lat + .25}&datetime=${start}T00:00:00Z/${end}T23:00:00Z")
        val text = (url.openConnection() as HttpURLConnection).run {
            connectTimeout = 15000; readTimeout = 20000; requestMethod = "GET"
            inputStream.bufferedReader().use(BufferedReader::readText)
        }
        return parseGeoJson(location, text, start, end).ifEmpty { syntheticEmpty(location, start, end) }
    }

    private fun parseGeoJson(location: Location, json: String, start: LocalDate, end: LocalDate): List<PeriodWeather> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        val buckets = mutableMapOf<Pair<LocalDate, DayPeriod>, MutableList<Triple<Double, Double, Double>>>()
        for (i in 0 until features.length()) {
            val p = features.getJSONObject(i).optJSONObject("properties") ?: continue
            val timeText = p.optString("LOCAL_DATE", p.optString("datetime", p.optString("time"))).replace("Z", "")
            val time = runCatching { LocalDateTime.parse(timeText.take(19)) }.getOrNull() ?: continue
            val temp = firstNumber(p, "TEMP", "AIR_TEMPERATURE", "temperature") ?: continue
            val precip = firstNumber(p, "PRECIP_AMOUNT", "TOTAL_PRECIPITATION", "precipitation") ?: 0.0
            val wind = firstNumber(p, "WIND_SPEED", "wind_speed") ?: 0.0
            val period = periodFor(location, time)
            buckets.getOrPut(time.toLocalDate() to period) { mutableListOf() }.add(Triple(temp, precip, wind))
        }
        return buckets.map { (key, values) ->
            PeriodWeather(location.name, key.first, key.second, values.minOf { it.first }, values.maxOf { it.first }, values.sumOf { it.second }, values.maxOf { it.third }, !key.first.isAfter(LocalDate.now()))
        }.sortedWith(compareBy<PeriodWeather> { it.date }.thenBy { it.period.ordinal })
    }

    private fun firstNumber(obj: JSONObject, vararg names: String): Double? = names.firstNotNullOfOrNull { n -> obj.optDouble(n, Double.NaN).takeUnless { it.isNaN() } }

    private fun periodFor(location: Location, dateTime: LocalDateTime): DayPeriod {
        val sunrise = daylightTime(location, dateTime.toLocalDate(), true)
        val sunset = daylightTime(location, dateTime.toLocalDate(), false)
        val t = dateTime.toLocalTime()
        return when {
            t.isBefore(sunrise) -> DayPeriod.OVERNIGHT
            t.isBefore(LocalTime.NOON) -> DayPeriod.MORNING
            t.isBefore(sunset) -> DayPeriod.AFTERNOON_EVENING
            else -> DayPeriod.OVERNIGHT
        }
    }

    private fun daylightTime(location: Location, date: LocalDate, sunrise: Boolean): LocalTime {
        val zenith = Math.toRadians(90.833)
        val day = date.dayOfYear.toDouble()
        val lngHour = location.lon / 15.0
        val approx = day + (((if (sunrise) 6.0 else 18.0) - lngHour) / 24.0)
        val meanAnomaly = (0.9856 * approx) - 3.289
        var trueLong = meanAnomaly + 1.916 * sin(Math.toRadians(meanAnomaly)) + 0.020 * sin(Math.toRadians(2 * meanAnomaly)) + 282.634
        trueLong = (trueLong + 360) % 360
        var ra = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(trueLong))))
        ra += floor(trueLong / 90) * 90 - floor(ra / 90) * 90
        ra /= 15.0
        val sinDec = 0.39782 * sin(Math.toRadians(trueLong))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(zenith) - sinDec * sin(Math.toRadians(location.lat))) / (cosDec * cos(Math.toRadians(location.lat)))
        val h = (if (sunrise) 360 - Math.toDegrees(acos(cosH)) else Math.toDegrees(acos(cosH))) / 15.0
        val utc = (h + ra - (0.06571 * approx) - 6.622 - lngHour + 24) % 24
        val local = (utc - 7 + 24) % 24
        val hour = local.toInt()
        val minute = floor((local % 1) * 60).toInt().coerceIn(0, 59)
        return LocalTime.of(hour, minute)
    }

    private fun syntheticEmpty(location: Location, start: LocalDate, end: LocalDate): List<PeriodWeather> = generateSequence(start) { it.plusDays(1).takeIf { d -> !d.isAfter(end) } }
        .flatMap { d -> DayPeriod.entries.map { p -> PeriodWeather(location.name, d, p, 0.0, 0.0, 0.0, 0.0, !d.isAfter(LocalDate.now())) } }.toList()
}
