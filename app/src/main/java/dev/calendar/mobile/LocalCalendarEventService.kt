package dev.calendar.mobile

import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.Locale

internal class LocalCalendarEventService(private val path: File) : CalendarEventService {
    private val file = AtomicFile(path)
    val exists: Boolean get() = path.isFile || File(path.path + ".bak").isFile

    override fun changes() = emptyFlow<Unit>()

    override suspend fun load(): List<CalendarEvent> = access { readEvents() }

    override suspend fun save(event: CalendarEvent, existing: Boolean) = access {
        val events = readEvents().toMutableList()
        val index = events.indexOfFirst { it.id == event.id }
        if (index < 0) events.add(event) else events[index] = event
        writeEvents(events)
    }

    override suspend fun delete(id: String) = access {
        writeEvents(readEvents().filterNot { it.id == id })
    }

    private suspend fun <T> access(operation: () -> T): T =
        withContext(Dispatchers.IO) { synchronized(fileLock, operation) }

    private fun readEvents(): List<CalendarEvent> {
        val rows = JSONArray(file.readFully().decodeToString())
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val category = row.getString("calendar")
            CalendarEvent(
                date = LocalDate.parse(row.getString("date")),
                id = row.getString("id"),
                title = row.getString("title"),
                calendar = EventCalendar.entries.single { it.name.equals(category, ignoreCase = true) },
                notes = row.getString("notes"),
                allDay = row.getBoolean("allDay"),
                start = row.getInt("start"),
                end = row.getInt("end"),
                duration = row.getInt("duration"),
            )
        }
    }

    private fun writeEvents(events: List<CalendarEvent>) {
        val rows = JSONArray()
        for (event in events) {
            rows.put(JSONObject()
                .put("date", event.date.toString())
                .put("id", event.id)
                .put("title", event.title)
                .put("calendar", event.calendar.name.lowercase(Locale.ROOT))
                .put("notes", event.notes)
                .put("allDay", event.allDay)
                .put("start", event.start)
                .put("end", event.end)
                .put("duration", event.duration))
        }
        val data = rows.toString().encodeToByteArray()
        val stream = file.startWrite()
        try {
            stream.write(data)
            file.finishWrite(stream)
        } catch (exception: Exception) {
            file.failWrite(stream)
            throw exception
        }
    }

    private companion object {
        val fileLock = Any()
    }
}
