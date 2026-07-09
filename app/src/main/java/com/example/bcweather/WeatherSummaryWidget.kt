package com.example.bcweather

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews

class WeatherSummaryWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    companion object {
        private const val SURREY_NAME = "Surrey"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WeatherSummaryWidget::class.java))
            ids.forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val summary = runCatching {
                val rows = next24HourRows(WeatherDatabase(context).load(SURREY_NAME))
                if (rows.none { it.available }) {
                    "No cached data yet. Open the app and refresh."
                } else {
                    formatWeatherSummary(rows)
                }
            }.getOrElse { "Unable to load weather summary." }

            val views = RemoteViews(context.packageName, R.layout.weather_summary_widget).apply {
                setTextViewText(R.id.widget_title, "Surrey next 24h")
                setTextViewText(R.id.widget_summary, summary)
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
