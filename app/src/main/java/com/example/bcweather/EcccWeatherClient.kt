package com.example.bcweather

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.*

class EcccWeatherClient {
    fun fetch(location: Location): List<PeriodWeather> {
        val today = LocalDate.now(BC_ZONE)
        val start = today.minusDays(3)
        val end = today.plusDays(3)
        val observedRows = fetchObserved(location, start, today)
        val forecastRows = runCatching { fetchForecast(location, today, end) }.getOrElse { emptyList() }
        return mergeRows(location, start, end, observedRows, forecastRows)
    }

    private fun fetchObserved(location: Location, start: LocalDate, end: LocalDate): List<PeriodWeather> {
        val startUtc = start.atStartOfDay(BC_ZONE).withZoneSameInstant(ZoneOffset.UTC)
        val endUtc = end.plusDays(1).atStartOfDay(BC_ZONE).minusNanos(1).withZoneSameInstant(ZoneOffset.UTC)
        val pages = mutableListOf<String>()
        var offset = 0
        do {
            val text = fetchPage(location, startUtc, endUtc, offset)
            pages.add(text)
            val featureCount = JSONObject(text).optJSONArray("features")?.length() ?: 0
            offset += PAGE_LIMIT
        } while (featureCount == PAGE_LIMIT)
        return parseGeoJson(location, pages, start, end)
    }

    private fun fetchForecast(location: Location, start: LocalDate, end: LocalDate): List<PeriodWeather> {
        val url = URL("https://api.weather.gc.ca/collections/citypageweather-realtime/items?f=json&limit=500&bbox=${location.lon - 1.0},${location.lat - 1.0},${location.lon + 1.0},${location.lat + 1.0}")
        val text = (url.openConnection() as HttpURLConnection).run {
            connectTimeout = 15000; readTimeout = 20000; requestMethod = "GET"
            inputStream.bufferedReader().use(BufferedReader::readText)
        }
        val features = JSONObject(text).optJSONArray("features") ?: return emptyList()
        val best = (0 until features.length()).map { features.getJSONObject(it) }
            .minByOrNull { stationDistance(location, it) ?: Double.MAX_VALUE } ?: return emptyList()
        val forecasts = best.optJSONObject("properties")?.optJSONObject("forecastGroup")?.optJSONArray("forecasts") ?: return emptyList()
        val rows = mutableListOf<PeriodWeather>()
        var currentDate = start
        var sawDayPeriod = false
        var previousWasNight = false
        for (i in 0 until forecasts.length()) {
            val f = forecasts.getJSONObject(i)
            val name = f.optJSONObject("period")?.optJSONObject("textForecastName")?.optString("en").orEmpty().lowercase()
            val isNight = name.contains("night") || name.contains("tonight")
            if (!isNight && previousWasNight && sawDayPeriod) currentDate = currentDate.plusDays(1)
            if (currentDate.isAfter(end)) break
            val temp = forecastTemperature(f)
            val wind = forecastMaxWindKmh(f)
            val rain = forecastPrecipitationMm(f)
            temp?.let { value ->
                rows.add(PeriodWeather(location.name, currentDate, if (isNight) DayPeriod.OVERNIGHT else DayPeriod.AFTERNOON_EVENING, value, value, rain, wind, observed = false, available = true))
            }
            if (!isNight) sawDayPeriod = true
            previousWasNight = isNight
        }
        return rows
    }

    private fun mergeRows(location: Location, start: LocalDate, end: LocalDate, observedRows: List<PeriodWeather>, forecastRows: List<PeriodWeather>): List<PeriodWeather> {
        val byKey = forecastRows.associateBy { it.date to it.period }.toMutableMap()
        observedRows.filter { it.available }.forEach { byKey[it.date to it.period] = it }
        return generateSequence(start) { it.plusDays(1).takeIf { d -> !d.isAfter(end) } }
            .flatMap { date -> DayPeriod.entries.map { period -> byKey[date to period] ?: PeriodWeather(location.name, date, period, 0.0, 0.0, 0.0, 0.0, observed = false, available = false) } }
            .toList()
    }

    private fun forecastTemperature(forecast: JSONObject): Double? {
        val temperatures = forecast.optJSONObject("temperatures") ?: return null
        listOf("temperature", "temp_high", "temp_low").forEach { key ->
            firstLocalizedNumber(temperatures.opt(key))?.let { return it }
        }
        return null
    }

    private fun forecastMaxWindKmh(forecast: JSONObject): Double {
        val periods = forecast.optJSONObject("winds")?.opt("periods") ?: return 0.0
        val periodObjects = when (periods) {
            is org.json.JSONArray -> (0 until periods.length()).map { periods.getJSONObject(it) }
            is JSONObject -> listOf(periods)
            else -> emptyList()
        }
        return periodObjects.maxOfOrNull { period ->
            listOfNotNull(
                localizedNumber(period.optJSONObject("speed")),
                localizedNumber(period.optJSONObject("gust")),
            ).maxOrNull() ?: 0.0
        } ?: 0.0
    }

    private fun forecastPrecipitationMm(forecast: JSONObject): Double {
        val accumulation = forecast.optJSONObject("precipitation")
            ?: forecast.optJSONObject("precipitations")
            ?: forecast.optJSONObject("accumulation")
        localizedNumber(accumulation)?.let { return it }
        firstLocalizedNumber(accumulation?.optJSONObject("accumulation")?.opt("amount"))?.let { return it }
        listOf("amount", "rain", "snow", "precipitation").forEach { key ->
            firstLocalizedNumber(accumulation?.opt(key))?.let { return it }
        }
        val summary = forecast.optJSONObject("textSummary")?.optString("en").orEmpty()
        return Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*mm\\b").find(summary)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    }

    private fun firstLocalizedNumber(value: Any?): Double? = when (value) {
        is JSONObject -> localizedNumber(value) ?: value.keys().asSequence().firstNotNullOfOrNull { key -> firstLocalizedNumber(value.opt(key)) }
        is org.json.JSONArray -> (0 until value.length()).asSequence().firstNotNullOfOrNull { index -> firstLocalizedNumber(value.opt(index)) }
        else -> null
    }

    private fun localizedNumber(obj: JSONObject?): Double? = obj
        ?.optJSONObject("value")
        ?.optDouble("en", Double.NaN)
        ?.takeUnless { it.isNaN() }

    private fun fetchPage(location: Location, startUtc: java.time.ZonedDateTime, endUtc: java.time.ZonedDateTime, offset: Int): String {
        val url = URL("https://api.weather.gc.ca/collections/climate-hourly/items?f=json&limit=$PAGE_LIMIT&offset=$offset&bbox=${location.lon - .25},${location.lat - .25},${location.lon + .25},${location.lat + .25}&datetime=${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(startUtc)}/${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(endUtc)}")
        return (url.openConnection() as HttpURLConnection).run {
            connectTimeout = 15000; readTimeout = 20000; requestMethod = "GET"
            inputStream.bufferedReader().use(BufferedReader::readText)
        }
    }

    private fun parseGeoJson(location: Location, pages: List<String>, start: LocalDate, end: LocalDate): List<PeriodWeather> {
        val observationsByStation = mutableMapOf<String, MutableList<Observation>>()
        val distancesByStation = mutableMapOf<String, Double>()
        pages.forEach { json ->
            val features = JSONObject(json).optJSONArray("features") ?: return@forEach
            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val p = feature.optJSONObject("properties") ?: continue
                val timeText = p.optString("LOCAL_DATE", p.optString("datetime", p.optString("time")))
                val time = parseObservationTime(timeText) ?: continue
                if (time.toLocalDate().isBefore(start) || time.toLocalDate().isAfter(end)) continue
                val temp = firstNumber(p, "TEMP", "AIR_TEMPERATURE", "temperature") ?: continue
                val precip = firstNumber(p, "PRECIP_AMOUNT", "TOTAL_PRECIPITATION", "precipitation") ?: continue
                val wind = firstNumber(p, "WIND_SPEED", "wind_speed") ?: continue
                val station = stationKey(p, feature, i)
                observationsByStation.getOrPut(station) { mutableListOf() }.add(Observation(time, temp, precip, wind))
                stationDistance(location, feature)?.let { distance ->
                    distancesByStation[station] = minOf(distancesByStation[station] ?: Double.MAX_VALUE, distance)
                }
            }
        }
        val stationObservations = observationsByStation.entries
            .minWithOrNull(compareBy<Map.Entry<String, MutableList<Observation>>> { distancesByStation[it.key] ?: Double.MAX_VALUE }.thenByDescending { it.value.size })
            ?.value
            .orEmpty()
        val buckets = mutableMapOf<Pair<LocalDate, DayPeriod>, MutableList<Observation>>()
        stationObservations.forEach { observation ->
            buckets.getOrPut(observation.time.toLocalDate() to periodFor(location, observation.time)) { mutableListOf() }.add(observation)
        }
        return generateSequence(start) { it.plusDays(1).takeIf { d -> !d.isAfter(end) } }
            .flatMap { date -> DayPeriod.entries.map { period -> toPeriodWeather(location, date, period, buckets[date to period]) } }
            .toList()
    }

    private fun parseObservationTime(value: String): LocalDateTime? {
        val normalized = value.removeSuffix("Z").take(19)
        return runCatching { LocalDateTime.parse(normalized) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) }.getOrNull()
    }

    private fun firstNumber(obj: JSONObject, vararg names: String): Double? = names.firstNotNullOfOrNull { n -> obj.optDouble(n, Double.NaN).takeUnless { it.isNaN() } }

    private fun stationKey(properties: JSONObject, feature: JSONObject, index: Int): String =
        listOf("CLIMATE_IDENTIFIER", "STATION_NAME", "station_name", "STN_ID", "station_id")
            .firstNotNullOfOrNull { key -> properties.optString(key).takeIf { it.isNotBlank() } }
            ?: feature.optString("id").takeIf { it.isNotBlank() }
            ?: "feature-$index"

    private fun stationDistance(location: Location, feature: JSONObject): Double? {
        val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
        if (coordinates.length() < 2) return null
        val lon = coordinates.optDouble(0, Double.NaN)
        val lat = coordinates.optDouble(1, Double.NaN)
        if (lon.isNaN() || lat.isNaN()) return null
        return hypot(location.lat - lat, location.lon - lon)
    }

    private fun toPeriodWeather(location: Location, date: LocalDate, period: DayPeriod, observations: List<Observation>?): PeriodWeather {
        if (observations.isNullOrEmpty()) {
            return PeriodWeather(location.name, date, period, 0.0, 0.0, 0.0, 0.0, false)
        }
        return PeriodWeather(
            location.name,
            date,
            period,
            observations.minOf { it.tempC },
            observations.maxOf { it.tempC },
            observations.sumOf { it.precipitationMm },
            observations.maxOf { it.windKmh },
            !date.isAfter(LocalDate.now(BC_ZONE)),
        )
    }

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
        return date.atStartOfDay(ZoneOffset.UTC)
            .plusMinutes((utc * 60).toLong())
            .withZoneSameInstant(BC_ZONE)
            .toLocalTime()
            .withSecond(0)
            .withNano(0)
    }

    private data class Observation(val time: LocalDateTime, val tempC: Double, val precipitationMm: Double, val windKmh: Double)

    private companion object {
        val BC_ZONE: ZoneId = ZoneId.of("America/Vancouver")
        const val PAGE_LIMIT = 500
    }

}
