package com.example.bcweather

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
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
            setPadding(20, 36, 20, 20)
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
        rows.forEach {
            val lowHigh = if (it.available) "%.0f/%.0f".format(it.lowC, it.highC) else "—"
            val rain = if (it.available) "%.1f".format(it.precipitationMm) else "—"
            val wind = if (it.available) "%.0f".format(it.maxWindKmh) else "—"
            val status = if (it.observed) "Observed" else if (it.available) "Forecast" else "Unavailable"
            table.addView(row(it.date.format(DateTimeFormatter.ofPattern("MMM d")), it.period.label, lowHigh, rain, wind, status))
        }
        if (rows.isEmpty()) status.text = "No cached data for ${selected.name}; refresh will run automatically."
    }

    private fun row(vararg cells: String, header: Boolean = false): TableRow = TableRow(this).apply {
        setBackgroundColor(if (header) Color.parseColor("#084C61") else Color.WHITE)
        cells.forEach { text -> addView(TextView(this@MainActivity).apply { this.text = text; textSize = if (header) 13f else 11f; gravity = Gravity.CENTER; setPadding(4, 8, 4, 8); setTextColor(if (header) Color.WHITE else Color.parseColor("#1F2933")) }) }
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
