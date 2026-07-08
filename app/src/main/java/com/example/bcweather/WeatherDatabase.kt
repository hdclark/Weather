package com.example.bcweather

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate

class WeatherDatabase(context: Context) : SQLiteOpenHelper(context, "weather_periods.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE period_weather(
                location TEXT NOT NULL, date TEXT NOT NULL, period TEXT NOT NULL,
                low_c REAL NOT NULL, high_c REAL NOT NULL, precipitation_mm REAL NOT NULL,
                max_wind_kmh REAL NOT NULL, observed INTEGER NOT NULL, available INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(location, date, period)
            )
        """.trimIndent())
        db.execSQL("CREATE TABLE refresh_state(location TEXT PRIMARY KEY, refreshed_at_ms INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE period_weather ADD COLUMN available INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE period_weather SET available = observed")
        }
    }

    fun replace(location: String, rows: List<PeriodWeather>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("period_weather", "location=?", arrayOf(location))
            rows.forEach { r ->
                db.replace("period_weather", null, ContentValues().apply {
                    put("location", r.location); put("date", r.date.toString()); put("period", r.period.name)
                    put("low_c", r.lowC); put("high_c", r.highC); put("precipitation_mm", r.precipitationMm)
                    put("max_wind_kmh", r.maxWindKmh); put("observed", if (r.observed) 1 else 0); put("available", if (r.available) 1 else 0)
                })
            }
            db.replace("refresh_state", null, ContentValues().apply { put("location", location); put("refreshed_at_ms", System.currentTimeMillis()) })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun refreshedAt(location: String): Long = readableDatabase.rawQuery("SELECT refreshed_at_ms FROM refresh_state WHERE location=?", arrayOf(location)).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun load(location: String): List<PeriodWeather> = readableDatabase.rawQuery(
        "SELECT date, period, low_c, high_c, precipitation_mm, max_wind_kmh, observed, available FROM period_weather WHERE location=? ORDER BY date, CASE period WHEN 'OVERNIGHT' THEN 0 WHEN 'MORNING' THEN 1 WHEN 'AFTERNOON_EVENING' THEN 2 ELSE 3 END",
        arrayOf(location)
    ).use { c -> buildList { while (c.moveToNext()) add(PeriodWeather(location, LocalDate.parse(c.getString(0)), DayPeriod.valueOf(c.getString(1)), c.getDouble(2), c.getDouble(3), c.getDouble(4), c.getDouble(5), c.getInt(6) == 1, c.getInt(7) == 1)) } }

    fun exportCsv(): String = readableDatabase.rawQuery("SELECT location,date,period,low_c,high_c,precipitation_mm,max_wind_kmh,observed,available FROM period_weather ORDER BY location,date,CASE period WHEN 'OVERNIGHT' THEN 0 WHEN 'MORNING' THEN 1 WHEN 'AFTERNOON_EVENING' THEN 2 ELSE 3 END", null).use { c ->
        buildString {
            appendLine("location,date,period,low_c,high_c,precipitation_mm,max_wind_kmh,observed,available")
            while (c.moveToNext()) appendLine((0 until c.columnCount).joinToString(",") { c.getString(it) })
        }
    }
}
