package com.example.bcweather

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var db: WeatherDatabase
    private lateinit var table: TableLayout
    private lateinit var status: TextView
    private lateinit var surreySummary: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var selected = Locations.all.first()
    private val refreshGeneration = AtomicInteger(0)

    private val darkBackground = Color.parseColor("#0B1120")
    private val darkSurface = Color.parseColor("#111827")
    private val elevatedSurface = Color.parseColor("#1F2937")
    private val lightText = Color.parseColor("#F8FAFC")
    private val mutedText = Color.parseColor("#CBD5E1")
    private val accent = Color.parseColor("#2563EB")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = WeatherDatabase(this)
        window.statusBarColor = Color.parseColor("#020617")
        window.navigationBarColor = Color.parseColor("#020617")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 112, 20, 20)
            setBackgroundColor(darkBackground)
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(accent, Color.parseColor("#38BDF8"), Color.parseColor("#22C55E"))
            setProgressBackgroundColorSchemeColor(elevatedSurface)
            setOnRefreshListener { refreshAllLocations() }
        }
        val scroll = ScrollView(this).apply { addView(content) }
        swipeRefresh.addView(scroll)
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, Locations.all.map { it.name }).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setBackgroundColor(elevatedSurface)
            minimumHeight = dp(80)
            setPadding(dp(12), dp(20), dp(12), dp(20))
        }
        status = TextView(this).apply { setTextColor(mutedText); textSize = 16f; setPadding(0, 16, 0, 10) }
        surreySummary = TextView(this).apply {
            setTextColor(lightText)
            textSize = 16f
            setPadding(18, 18, 18, 18)
            setBackgroundColor(elevatedSurface)
        }
        table = TableLayout(this).apply { setStretchAllColumns(true); setShrinkAllColumns(true); setBackgroundColor(darkSurface) }
        val export = Button(this).apply { text = "Export historical CSV"; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#0B6E4F")); setOnClickListener { exportCsv() } }
        val refresh = Button(this).apply { text = "Refresh data"; setTextColor(Color.WHITE); setBackgroundColor(accent); setOnClickListener { refreshAllLocations() } }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(export, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 })
            addView(refresh, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8 })
        }
        content.addView(spinner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96)))
        content.addView(status)
        content.addView(surreySummary, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16 })
        content.addView(table)
        content.addView(actions)
        root.addView(swipeRefresh, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        renderLocationSummary()
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                (view as? TextView)?.setTextColor(lightText)
                selected = Locations.all[position]
                loadAndRefresh()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun loadAndRefresh() {
        render(db.load(selected.name))
        renderLocationSummary()
        val staleLocations = Locations.all.filter { System.currentTimeMillis() - db.refreshedAt(it.name) > 60 * 60 * 1000 }
        if (staleLocations.isNotEmpty()) refreshAllLocations(staleLocations)
    }

    private fun refreshAllLocations(locations: List<Location> = Locations.all) {
        val targetLocations = locations.distinct()
        if (targetLocations.isEmpty()) return
        val selectedAtStart = selected
        val generation = refreshGeneration.incrementAndGet()
        val orderedLocations = targetLocations.sortedBy { if (it == selectedAtStart) 0 else 1 }
        status.text = "Refreshing ${selectedAtStart.name} first, then other locations…"
        swipeRefresh.isRefreshing = true
        thread {
            val failures = mutableListOf<String>()
            orderedLocations.forEachIndexed { index, location ->
                try {
                    val rows = EcccWeatherClient().fetch(location)
                    db.replace(location.name, rows)
                    runOnUiThread {
                        if (selected == location) {
                            val suffix = if (index == orderedLocations.lastIndex) "" else "; continuing other locations…"
                            status.text = "Updated ${location.name}$suffix"
                            render(db.load(location.name))
                            renderLocationSummary()
                        }
                        if (location.name == SURREY_NAME) WeatherSummaryWidget.updateAll(this@MainActivity)
                    }
                } catch (e: Exception) {
                    synchronized(failures) { failures.add("${location.name}: ${e.message}") }
                }
                if (refreshGeneration.get() != generation) return@thread
            }
            runOnUiThread {
                if (refreshGeneration.get() == generation) {
                    swipeRefresh.isRefreshing = false
                    render(db.load(selected.name))
                    renderLocationSummary()
                    WeatherSummaryWidget.updateAll(this@MainActivity)
                    status.text = if (failures.isEmpty()) "Updated all locations" else "Refresh completed with ${failures.size} issue(s)"
                }
            }
        }
    }

    private fun render(rows: List<PeriodWeather>) {
        table.removeAllViews()
        table.addView(row("Date", "Period", "Low/High °C", "Rain mm", "Wind km/h", "Status", header = true))
        val currentTimePoint = currentTimePoint()
        val highlightedTimePoints = rows.filter { isAtOrAfter(it.date to it.period, currentTimePoint) }.take(3).map { it.date to it.period }.toSet()
        rows.forEach { weather ->
            val lowHigh = if (weather.available) "%.0f/%.0f".format(weather.lowC, weather.highC) else "—"
            val rain = if (weather.available) "%.1f".format(weather.precipitationMm) else "—"
            val wind = if (weather.available) "%.0f".format(weather.maxWindKmh) else "—"
            val status = if (weather.observed) "Observed" else if (weather.available) "Forecast" else "Unavailable"
            table.addView(row(weather.date.format(DateTimeFormatter.ofPattern("MMM d")), weather.period.label, lowHigh, rain, wind, status, highlightRow = weather.date to weather.period in highlightedTimePoints, highlightedCellIndexes = setOfNotNull(3.takeIf { weather.available && weather.precipitationMm > 1.0 }, 4.takeIf { weather.available && weather.maxWindKmh > 10.0 })))
        }
        if (rows.isEmpty()) status.text = "No cached data for ${selected.name}; refresh will run automatically."
    }

    private fun renderLocationSummary() {
        val nextRows = next24HourRows(db.load(selected.name))
        surreySummary.text = if (nextRows.none { it.available }) {
            "${selected.name} next 24h\nNo cached data yet. Pull down or tap Refresh data."
        } else {
            "${selected.name} next 24h\n${formatWeatherSummary(nextRows)}"
        }
    }

    private fun row(vararg cells: String, header: Boolean = false, highlightRow: Boolean = false, highlightedCellIndexes: Set<Int> = emptySet()): TableRow = TableRow(this).apply {
        setBackgroundColor(when { header -> Color.parseColor("#334155"); highlightRow -> Color.parseColor("#1E3A8A"); else -> darkSurface })
        cells.forEachIndexed { index, text ->
            addView(TextView(this@MainActivity).apply {
                this.text = text; textSize = if (header) 13f else 11f; gravity = Gravity.CENTER; setPadding(4, 8, 4, 8)
                setTextColor(if (header) Color.WHITE else lightText)
                if (highlightRow || index in highlightedCellIndexes) setTypeface(typeface, Typeface.BOLD)
                if (index in highlightedCellIndexes) setBackgroundColor(Color.parseColor("#7F1D1D"))
            })
        }
    }

    private fun currentTimePoint(): Pair<LocalDate, DayPeriod> {
        val now = java.time.ZonedDateTime.now(ZoneId.of("America/Vancouver"))
        val period = when { now.hour < 6 -> DayPeriod.OVERNIGHT; now.hour < 12 -> DayPeriod.MORNING; now.hour < 18 -> DayPeriod.AFTERNOON_EVENING; else -> DayPeriod.OVERNIGHT }
        val date = if (now.hour >= 18) now.toLocalDate().plusDays(1) else now.toLocalDate()
        return date to period
    }

    private fun isAtOrAfter(candidate: Pair<LocalDate, DayPeriod>, threshold: Pair<LocalDate, DayPeriod>): Boolean = candidate.first.isAfter(threshold.first) || (candidate.first == threshold.first && periodOrder(candidate.second) >= periodOrder(threshold.second))
    private fun periodOrder(period: DayPeriod): Int = when (period) { DayPeriod.OVERNIGHT -> 0; DayPeriod.MORNING -> 1; DayPeriod.AFTERNOON_EVENING -> 2 }

    private fun exportCsv() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "text/csv"; putExtra(Intent.EXTRA_TITLE, "bc_weather_periods.csv") }
        startActivityForResult(intent, 42)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK) data?.data?.let { uri ->
            contentResolver.openOutputStream(uri)?.use { it.write(db.exportCsv().toByteArray()) }
            Toast.makeText(this, "Exported weather history", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object { const val SURREY_NAME = "Surrey" }
}
