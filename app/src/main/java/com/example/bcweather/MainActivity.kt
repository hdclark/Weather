package com.example.bcweather

import android.app.Activity
import android.content.Intent
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        val spinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Locations.all.map { it.name }) }
        status = TextView(this)
        table = TableLayout(this).apply { stretchAllColumns = true; shrinkAllColumns = true }
        val export = Button(this).apply { text = "Export historical CSV"; setOnClickListener { exportCsv() } }
        root.addView(spinner); root.addView(status); root.addView(table); root.addView(export)
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
                .onSuccess { rows -> db.replace(location.name, rows); runOnUiThread { status.text = "Updated ${location.name}"; render(db.load(location.name)) } }
                .onFailure { e -> runOnUiThread { status.text = "Refresh failed: ${e.message}" } }
        }
    }

    private fun render(rows: List<PeriodWeather>) {
        table.removeAllViews()
        table.addView(row("Date", "Period", "Low/High °C", "Rain mm", "Wind km/h", header = true))
        rows.forEach { table.addView(row(it.date.format(DateTimeFormatter.ofPattern("MMM d")), it.period.label, "%.0f/%.0f".format(it.lowC, it.highC), "%.1f".format(it.precipitationMm), "%.0f".format(it.maxWindKmh))) }
        if (rows.isEmpty()) status.text = "No cached data for ${selected.name}; refresh will run automatically."
    }

    private fun row(vararg cells: String, header: Boolean = false): TableRow = TableRow(this).apply {
        cells.forEach { text -> addView(TextView(this@MainActivity).apply { this.text = text; textSize = if (header) 13f else 11f; gravity = Gravity.CENTER; setPadding(2, 4, 2, 4) }) }
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
