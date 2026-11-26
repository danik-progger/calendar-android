package dev.calendar.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

private const val AUTH = "https://calendar-bff.vercel.app/api/auth"
private const val EVENTS = "https://calendar-server-eta.vercel.app/api/v1/events"

internal class SessionExpired : Exception()
internal class UnsupportedEvent : Exception()

class CalendarApi(internal val token: String) : CalendarEventService {
    override fun changes() = callbackFlow {
        val connection = URL("$EVENTS/stream").openConnection() as HttpsURLConnection
        val reader = launch(Dispatchers.IO) {
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "text/event-stream")
                if (connection.responseCode == 401) throw SessionExpired()
                check(connection.responseCode == 200 && connection.contentType.orEmpty().startsWith("text/event-stream")) {
                    "Event stream unavailable (HTTP ${connection.responseCode}, ${connection.contentType})"
                }
                connection.inputStream.bufferedReader().use { input ->
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line == "data: {}") trySend(Unit)
                    }
                }
                close()
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { close(exception) }
            finally { connection.disconnect() }
        }
        awaitClose { connection.disconnect(); reader.cancel() }
    }.buffer(Channel.CONFLATED)

    suspend fun email(): String {
        val data = request("$AUTH/get-session?disableCookieCache=true", token = token).body.trim()
        if (data == "null") throw SessionExpired()
        return JSONObject(data).getJSONObject("user").getString("email")
    }

    suspend fun signOut() {
        request("$AUTH/sign-out", "POST", token, JSONObject())
    }

    override suspend fun load(): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        val limit = 1000
        while (true) {
            val rows = JSONArray(request("$EVENTS?limit=$limit&offset=${events.size}", token = token).body)
            for (index in 0..<rows.length()) events.add(readRemoteEvent(rows.getJSONObject(index)))
            if (rows.length() < limit) return events
        }
    }

    override suspend fun save(event: CalendarEvent, existing: Boolean) {
        val data = writeRemoteEvent(event)
        val url = if (existing) "$EVENTS/${data.getString("id")}" else EVENTS
        request(url, if (existing) "PUT" else "POST", token, data)
    }

    override suspend fun delete(id: String) {
        request("$EVENTS/${UUID.fromString(id)}", "DELETE", token, allowMissing = true)
    }

    companion object {
        suspend fun signIn(idToken: String, nonce: String): CalendarApi {
            val body = JSONObject().put("provider", "google")
                .put("idToken", JSONObject().put("token", idToken).put("nonce", nonce))
            val response = request("$AUTH/sign-in/social", "POST", body = body)
            val token = requireNotNull(response.token).also { require(it.isNotBlank()) }
            return CalendarApi(token)
        }
    }
}

private class Response(val body: String, val token: String?)

private suspend fun request(
    url: String, method: String = "GET", token: String? = null, body: JSONObject? = null, allowMissing: Boolean = false,
): Response = withContext(Dispatchers.IO) {
    val connection = URL(url).openConnection() as HttpsURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().encodeToByteArray()) }
        }
        val status = connection.responseCode
        if (status == 401) throw SessionExpired()
        if (allowMissing && status == 404) return@withContext Response("", null)
        check(status in 200..299)
        Response(connection.inputStream.bufferedReader().use { it.readText() }, connection.getHeaderField("set-auth-token"))
    } finally {
        connection.disconnect()
    }
}

internal fun writeRemoteEvent(event: CalendarEvent, zone: ZoneId = ZoneId.systemDefault()): JSONObject {
    val start = if (event.allDay) event.date.atStartOfDay().toInstant(ZoneOffset.UTC)
        else instant(event.date, event.start, zone)
    val end = if (event.allDay) event.date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        else instant(event.date, event.end, zone)
    if (end <= start) throw UnsupportedEvent()
    return JSONObject().put("id", UUID.fromString(event.id).toString()).put("title", event.title)
        .put("description", event.notes).put("allDay", event.allDay)
        .put("startAt", start.toString()).put("endAt", end.toString())
        .put("metadata", JSONObject().put("calendarId", event.calendar.name.lowercase(Locale.ROOT))
            .put("presentation", event.presentation).put("durationMinute", event.duration))
}

internal fun readRemoteEvent(row: JSONObject, zone: ZoneId = ZoneId.systemDefault()): CalendarEvent {
    val start = Instant.parse(row.getString("startAt"))
    val end = Instant.parse(row.getString("endAt"))
    val allDay = row.getBoolean("allDay")
    val id = UUID.fromString(row.getString("id")).toString()
    val metadata = row.getJSONObject("metadata")
    val presentation = metadata.getString("presentation")
    if (end <= start || presentation !in setOf("standard", "focus", "call")) throw UnsupportedEvent()
    val date: LocalDate
    val first: Int
    val last: Int
    if (allDay) {
        date = start.atOffset(ZoneOffset.UTC).toLocalDate()
        if (start != date.atStartOfDay().toInstant(ZoneOffset.UTC) || end != start.plusSeconds(86_400)) {
            throw UnsupportedEvent()
        }
        first = 540
        last = 585
    } else {
        val localStart = start.atZone(zone)
        val localEnd = end.atZone(zone)
        date = localStart.toLocalDate()
        first = localStart.hour * 60 + localStart.minute
        last = when (localEnd.toLocalDate()) {
            date -> localEnd.hour * 60 + localEnd.minute
            date.plusDays(1) -> 1440
            else -> throw UnsupportedEvent()
        }
        if (instant(date, first, zone) != start || instant(date, last, zone) != end) throw UnsupportedEvent()
    }
    val calendar = EventCalendar.entries.single { it.name.equals(metadata.getString("calendarId"), ignoreCase = true) }
    return CalendarEvent(date, id, row.getString("title"), calendar,
        if (row.isNull("description")) "" else row.getString("description"), allDay, first, last,
        metadata.optInt("durationMinute", last - first), presentation)
}

private fun instant(date: LocalDate, minute: Int, zone: ZoneId): Instant {
    if (minute !in 0..1440) throw UnsupportedEvent()
    val local = date.atStartOfDay().plusMinutes(minute.toLong())
    val offset = zone.rules.getValidOffsets(local).singleOrNull() ?: throw UnsupportedEvent()
    return local.toInstant(offset)
}
