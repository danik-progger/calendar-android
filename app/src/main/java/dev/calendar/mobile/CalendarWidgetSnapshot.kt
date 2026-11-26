package dev.calendar.mobile

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeParseException

internal data class CalendarWidgetEvent(
    val day: LocalDate,
    val title: String,
    val color: Int,
    val start: Int?,
    val end: Int?,
)

internal data class CalendarWidgetSnapshot(val through: LocalDate, val events: List<CalendarWidgetEvent>) {
    companion object {
        const val AGENDA_DAYS = 7L
        const val EVENT_LIMIT = 3
        private const val CACHE_DAYS = 14L
        private const val KEY = "snapshot"

        private fun preferences(context: Context): SharedPreferences =
            context.getSharedPreferences("calendar-widget", Context.MODE_PRIVATE)

        fun load(context: Context): CalendarWidgetSnapshot? {
            val data = preferences(context).getString(KEY, null) ?: return null
            return try {
                val snapshot = JSONObject(data)
                val events = snapshot.getJSONArray("events")
                CalendarWidgetSnapshot(LocalDate.parse(snapshot.getString("through")), List(events.length()) { index ->
                    val event = events.getJSONObject(index)
                    CalendarWidgetEvent(
                        LocalDate.parse(event.getString("day")), event.getString("title"), event.getInt("color"),
                        if (event.has("start")) event.getInt("start") else null,
                        if (event.has("end")) event.getInt("end") else null,
                    )
                })
            } catch (_: JSONException) {
                null
            } catch (_: DateTimeParseException) {
                null
            }
        }

        fun publish(context: Context, events: List<CalendarEvent>, today: LocalDate = LocalDate.now()) {
            val through = today.plusDays(CACHE_DAYS - 1)
            val upcoming = events.filter { it.date in today..through }.sortedWith(
                compareBy<CalendarEvent>({ it.date }, { if (it.allDay) -1 else it.start }, { it.title }, { it.id }),
            )
            val rows = JSONArray()
            for (event in upcoming) {
                rows.put(JSONObject().put("day", event.date.toString()).put("title", event.title)
                    .put("color", event.calendar.rgb).apply {
                        if (!event.allDay) put("start", event.start).put("end", event.end)
                    })
            }
            val data = JSONObject().put("through", through.toString()).put("events", rows).toString()
            val preferences = preferences(context)
            if (preferences.getString(KEY, null) == data) return
            preferences.edit().putString(KEY, data).apply()
            CalendarWidgetProvider.refresh(context)
        }

        fun clear(context: Context) {
            preferences(context).edit().remove(KEY).apply()
            CalendarWidgetProvider.refresh(context)
        }
    }
}
