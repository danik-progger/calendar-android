package dev.calendar.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

enum class EventCalendar(val rgb: Int) {
    Birthdays(0xf2ad3d), Personal(0xe86bb5), Work(0x7283db), Health(0x42beb9)
}
enum class ViewMode(val count: Int, val label: String) {
    Day(1, "Day"), ThreeDay(3, "3-day"), Week(7, "Week");
    val step get() = if (this == Week) 7L else 1L
    companion object {
        fun initial(width: Float) = when {
            width < 960 -> Day
            width < 1440 -> ThreeDay
            else -> Week
        }
    }
}

fun LocalDate.monday(): LocalDate = with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
fun LocalDate.monthGridStart(): LocalDate = withDayOfMonth(1).monday()
fun LocalDate.label(pattern: String): String = format(DateTimeFormatter.ofPattern(pattern, Locale.US))
fun clockLabel(minute: Int) = String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60)
fun eventTime(minute: Int) = if (minute % 60 == 0) "${minute / 60}" else clockLabel(minute).trimStart('0').let {
    if (it.startsWith(":")) "0$it" else it
}
fun parseMinute(text: String): Int? {
    val match = Regex("([0-9]{1,2}):([0-5][0-9])").matchEntire(text.trim()) ?: return null
    val (hours, minutes) = match.destructured
    val hour = hours.toInt()
    return if (hour < 24 || (hour == 24 && minutes == "00")) hour * 60 + minutes.toInt() else null
}
fun snapped(minute: Float) = (minute / 5).roundToInt() * 5

data class CalendarEvent(
    val date: LocalDate,
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val calendar: EventCalendar = EventCalendar.Work,
    val notes: String = "",
    val allDay: Boolean = false,
    val start: Int = 540,
    val end: Int = 585,
    val duration: Int = 45,
    val presentation: String = "standard",
) {
    fun times(start: Int, end: Int): CalendarEvent {
        val last = maxOf(1, end, minOf(start, 1439) + 5).coerceAtMost(1440)
        return copy(start = start.coerceIn(0, last - 5), end = last)
    }

    fun moved(date: LocalDate, minute: Int?): CalendarEvent {
        val next = copy(date = date, duration = if (allDay) duration else end - start, allDay = minute == null)
        return if (minute == null) next else next.times(minute, minute + next.duration)
    }
}

enum class UndoNotice(val message: String) {
    Deleted("Event deleted"),
    RestoreFailed("Couldn’t restore. Try again."),
}

class EventStore(
    private val scope: CoroutineScope,
    private val api: CalendarEventService,
    private val onExpired: suspend () -> Unit = {},
    private val onConfirmedEvents: (List<CalendarEvent>) -> Unit = {},
) {
    var events by mutableStateOf<List<CalendarEvent>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var notice by mutableStateOf<UndoNotice?>(null)
        private set
    private val deleted = mutableListOf<Pair<CalendarEvent, Int>>()
    private var noticeTask: Job? = null
    private var refreshPending = false
    private var confirmedEvents: List<CalendarEvent>? = null
    private data class PendingSave(val event: CalendarEvent, val result: CompletableDeferred<Boolean>)
    private val pendingSaves = ArrayDeque<PendingSave>()

    suspend fun synchronize() {
        var retry = 1000L
        while (true) {
            try {
                api.changes().collect {
                    retry = 1000L
                    load()
                }
            } catch (exception: CancellationException) { throw exception }
            catch (_: SessionExpired) { onExpired(); return }
            catch (_: Exception) { }
            delay(retry)
            retry = (retry * 2).coerceAtMost(30_000L)
        }
    }

    suspend fun load() {
        if (busy) { refreshPending = true; return }
        busy = true
        refreshPending = false
        error = null
        try {
            val rows = api.load()
            validate(rows)
            acceptLoaded(rows)
        } catch (exception: CancellationException) { throw exception }
        catch (_: SessionExpired) { onExpired() }
        catch (_: Exception) { error = "Couldn’t load your calendar." }
        finally { finishOperation() }
    }

    var dropRevision by mutableStateOf(0)
        private set

    suspend fun save(event: CalendarEvent, animated: Boolean = true): Boolean {
        val current = events ?: return false
        val next = event.copy(title = event.title.trim())
        if (next.title.isBlank()) { error = "Add a title to save this event."; return false }
        val updated = current.withEvent(next)
        try { validate(updated) }
        catch (_: Exception) { error = "Couldn’t save this event. Check its date and times."; return false }
        if (confirmedEvents == null) confirmedEvents = current
        if (animated) {
            events = updated
        } else {
            Snapshot.withMutableSnapshot {
                events = updated
                dropRevision++
            }
        }
        error = null
        val result = CompletableDeferred<Boolean>()
        pendingSaves.addLast(PendingSave(next, result))
        startNextSave()
        return result.await()
    }

    private fun acceptLoaded(rows: List<CalendarEvent>) {
        onConfirmedEvents(rows)
        if (confirmedEvents != null) { confirmedEvents = rows; publishSaves() }
        else events = rows
    }

    private fun publishSaves() {
        val confirmed = confirmedEvents ?: return
        events = pendingSaves.fold(confirmed) { rows, pending -> rows.withEvent(pending.event) }
    }

    private fun startNextSave() {
        if (busy) return
        val pending = pendingSaves.firstOrNull() ?: return
        val current = confirmedEvents ?: return
        val index = current.indexOfFirst { it.id == pending.event.id }
        val next = current.withEvent(pending.event, index)
        busy = true
        scope.launch {
            var saved = false
            try {
                api.save(pending.event, existing = index >= 0)
                confirmedEvents = next
                onConfirmedEvents(next)
                saved = true
            } catch (exception: CancellationException) {
                pendingSaves.forEach { it.result.complete(false) }
                pendingSaves.clear()
                throw exception
            } catch (_: SessionExpired) { onExpired() }
            catch (_: Exception) { error = "Couldn’t save your changes. Try again." }
            finally {
                if (pendingSaves.firstOrNull() === pending) pendingSaves.removeFirst()
                publishSaves()
                if (pendingSaves.isEmpty()) confirmedEvents = null
                pending.result.complete(saved)
                finishOperation()
            }
        }
    }

    suspend fun delete(id: String): Boolean {
        if (busy) return false
        val next = events?.toMutableList() ?: return false
        val index = next.indexOfFirst { it.id == id }
        if (index == -1) return false
        val event = next.removeAt(index)
        if (!commit(next) { delete(id) }) return false
        deleted.add(event to index)
        notice = UndoNotice.Deleted
        noticeTask?.cancel()
        noticeTask = scope.launch { delay(5000); notice = null }
        return true
    }

    suspend fun undo(): Boolean {
        if (busy) return false
        val next = events?.toMutableList() ?: return false
        val (event, index) = deleted.lastOrNull() ?: return false
        next.add(index.coerceAtMost(next.size), event)
        noticeTask?.cancel()
        if (!commit(next) { save(event, existing = false) }) {
            error = null
            notice = UndoNotice.RestoreFailed
            return false
        }
        deleted.removeAt(deleted.lastIndex)
        notice = null
        return true
    }

    private suspend fun commit(next: List<CalendarEvent>, remote: suspend CalendarEventService.() -> Unit): Boolean {
        busy = true
        try {
            validate(next)
            api.remote()
            acceptLoaded(next)
            error = null
            return true
        } catch (exception: CancellationException) { throw exception }
        catch (_: SessionExpired) { onExpired() }
        catch (_: UnsupportedEvent) { error = "Couldn’t save this event. Check its date and times." }
        catch (_: Exception) { error = "Couldn’t save your changes. Try again." }
        finally { finishOperation() }
        return false
    }

    private fun finishOperation() {
        busy = false
        startNextSave()
        if (!busy && refreshPending) scope.launch { load() }
    }

    private fun List<CalendarEvent>.withEvent(event: CalendarEvent, index: Int = indexOfFirst { it.id == event.id }) =
        if (index < 0) this + event else toMutableList().apply { this[index] = event }

    private fun validate(events: List<CalendarEvent>) {
        require(events.distinctBy { it.id }.size == events.size)
        require(events.all { event ->
            event.id.isNotEmpty() && event.title.isNotBlank() && event.duration in 1..1440 &&
                event.start in 0..<1440 && event.end <= 1440 && event.end > event.start
        })
    }
}
