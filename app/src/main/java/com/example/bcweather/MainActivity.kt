package com.example.bcweather

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var db: WeatherDatabase
    private lateinit var table: TableLayout
    private lateinit var status: TextView
    private var selected = Locations.all.first()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = WeatherDatabase(this)
        window.statusBarColor = Color.parseColor("#084C61")
        window.navigationBarColor = Color.parseColor("#06313F")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 72, 20, 20)
            setBackgroundColor(Color.parseColor("#EAF6F6"))
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(content) }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, Locations.all.map { it.name }).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setBackgroundColor(Color.WHITE)
        }
        status = TextView(this).apply { setTextColor(Color.parseColor("#153B44")); textSize = 16f; setPadding(0, 16, 0, 10) }
        table = TableLayout(this).apply { setStretchAllColumns(true); setShrinkAllColumns(true); setBackgroundColor(Color.WHITE) }
        val export = Button(this).apply { text = "Export historical CSV"; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#0B6E4F")); setOnClickListener { exportCsv() } }
        content.addView(spinner); content.addView(status); content.addView(table); content.addView(export)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { selected = Locations.all[position]; loadAndRefresh() }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun loadAndRefresh() {
        render(db.load(selected.name))
        val stale = System.currentTimeMillis() - db.refreshedAt(selected.name) > 60 * 60 * 1000
        if (stale) refresh()
    }

    private fun refresh() {
        status.text = "Refreshing ${selected.name} from MSC GeoMet…"
        val location = selected
        thread {
            runCatching { EcccWeatherClient().fetch(location) }
                .onSuccess { rows ->
                    db.replace(location.name, rows)
                    runOnUiThread {
                        if (selected == location) {
                            status.text = "Updated ${location.name}"
                            render(db.load(location.name))
                        }
                    }
                }
                .onFailure { e -> runOnUiThread { if (selected == location) status.text = "Refresh failed: ${e.message}" } }
        }
    }

    private fun render(rows: List<PeriodWeather>) {
        table.removeAllViews()
        table.addView(row("Date", "Period", "Low/High °C", "Rain mm", "Wind km/h", "Status", header = true))
        val currentTimePoint = currentTimePoint()
        val highlightedTimePoints = rows
            .filter { isAtOrAfter(it.date to it.period, currentTimePoint) }
            .take(3)
            .map { it.date to it.period }
            .toSet()
        rows.forEach { weather ->
            val lowHigh = if (weather.available) "%.0f/%.0f".format(weather.lowC, weather.highC) else "—"
            val rain = if (weather.available) "%.1f".format(weather.precipitationMm) else "—"
            val wind = if (weather.available) "%.0f".format(weather.maxWindKmh) else "—"
            val status = if (weather.observed) "Observed" else if (weather.available) "Forecast" else "Unavailable"
            table.addView(
                row(
                    weather.date.format(DateTimeFormatter.ofPattern("MMM d")),
                    weather.period.label,
                    lowHigh,
                    rain,
                    wind,
                    status,
                    highlightRow = weather.date to weather.period in highlightedTimePoints,
                    highlightedCellIndexes = setOfNotNull(
                        3.takeIf { weather.available && weather.precipitationMm > 1.0 },
                        4.takeIf { weather.available && weather.maxWindKmh > 10.0 },
                    ),
                )
            )
        }
        if (rows.isEmpty()) status.text = "No cached data for ${selected.name}; refresh will run automatically."
    }

    private fun row(
        vararg cells: String,
        header: Boolean = false,
        highlightRow: Boolean = false,
        highlightedCellIndexes: Set<Int> = emptySet(),
    ): TableRow = TableRow(this).apply {
        setBackgroundColor(
            when {
                header -> Color.parseColor("#084C61")
                highlightRow -> Color.parseColor("#FFF4CC")
                else -> Color.WHITE
            }
        )
        cells.forEachIndexed { index, text ->
            addView(TextView(this@MainActivity).apply {
                this.text = text
                textSize = if (header) 13f else 11f
                gravity = Gravity.CENTER
                setPadding(4, 8, 4, 8)
                setTextColor(if (header) Color.WHITE else Color.parseColor("#1F2933"))
                if (highlightRow || index in highlightedCellIndexes) setTypeface(typeface, Typeface.BOLD)
                if (index in highlightedCellIndexes) setBackgroundColor(Color.parseColor("#FFE1E1"))
            })
        }
    }

    private fun currentTimePoint(): Pair<LocalDate, DayPeriod> {
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

    private fun isAtOrAfter(candidate: Pair<LocalDate, DayPeriod>, threshold: Pair<LocalDate, DayPeriod>): Boolean =
        candidate.first.isAfter(threshold.first) ||
            (candidate.first == threshold.first && periodOrder(candidate.second) >= periodOrder(threshold.second))

    private fun periodOrder(period: DayPeriod): Int = when (period) {
        DayPeriod.OVERNIGHT -> 0
        DayPeriod.MORNING -> 1
        DayPeriod.AFTERNOON_EVENING -> 2
    }

    private fun exportCsv() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "text/csv"; putExtra(Intent.EXTRA_TITLE, "bc_weather_periods.csv")
        }
        startActivityForResult(intent, 42)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK) data?.data?.let { uri ->
            contentResolver.openOutputStream(uri)?.use { it.write(db.exportCsv().toByteArray()) }
            Toast.makeText(this, "Exported weather history", Toast.LENGTH_SHORT).show()
        }
    }
}
