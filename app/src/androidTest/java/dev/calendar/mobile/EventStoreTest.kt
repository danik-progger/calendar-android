package dev.calendar.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class EventStoreTest {
    @Test fun failedWriteKeepsQueuedEditAndDefersReload() = runBlocking {
        withContext(Dispatchers.Main) {
            withTimeout(5000) {
                val original = CalendarEvent(LocalDate.of(2026, 11, 20), title = "First event")
                val moved = original.moved(original.date, 660)
                val added = CalendarEvent(original.date, title = "Second event")
                val service = TestEventService(original)
                val store = EventStore(this, service)
                store.load()

                val firstStarted = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                service.beforeSave = { event ->
                    if (event == moved) {
                        firstStarted.complete(Unit)
                        release.await()
                        error("Write failed")
                    }
                }
                val first = async(start = CoroutineStart.UNDISPATCHED) { store.save(moved) }
                firstStarted.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) { store.save(added) }
                assertEquals(listOf(moved, added), store.events)
                assertEquals(listOf(moved to true), service.saves)

                val reloaded = CompletableDeferred<Unit>()
                service.onLoad = { reloaded.complete(Unit) }
                store.load()
                assertFalse(reloaded.isCompleted)
                release.complete(Unit)

                assertFalse(first.await())
                assertTrue(second.await())
                reloaded.await()
                assertEquals(listOf(original, added), store.events)
                assertEquals(listOf(moved to true, added to false), service.saves)
            }
        }
    }

    @Test fun sharedEventKeepsDateMidnightAndMetadata() {
        val day = LocalDate.of(2026, 7, 16)
        val zone = ZoneId.of("Europe/Moscow")
        for (allDay in listOf(false, true)) {
            val event = CalendarEvent(day, title = "Shared event", calendar = EventCalendar.Health,
                notes = "Shared note", allDay = allDay, start = if (allDay) 540 else 1380,
                end = if (allDay) 585 else 1440, duration = 60, presentation = "focus")
            val wire = writeRemoteEvent(event, zone)
            assertEquals(if (allDay) "2026-07-16T00:00:00Z" else "2026-07-16T20:00:00Z", wire.getString("startAt"))
            assertEquals(if (allDay) "2026-07-17T00:00:00Z" else "2026-07-16T21:00:00Z", wire.getString("endAt"))
            assertEquals(event, readRemoteEvent(wire, zone))
        }
    }

    @Test fun invalidEditsDoNotReachServiceAndUndoRestoresOrder() = runBlocking {
        withContext(Dispatchers.Main) {
            val original = CalendarEvent(LocalDate.of(2026, 11, 20), title = "First event")
            val removed = CalendarEvent(original.date, title = "Second event")
            val last = CalendarEvent(original.date, title = "Third event")
            val service = TestEventService(original, removed, last)
            val store = EventStore(this, service)
            store.load()
            assertTrue(store.delete(removed.id))

            val invalid = listOf(
                original.copy(id = ""),
                original.copy(title = "  "),
                original.copy(duration = 0),
                original.copy(start = Int.MAX_VALUE),
                original.copy(end = Int.MIN_VALUE),
                original.copy(end = original.start),
            )
            for (event in invalid) {
                assertFalse(store.save(event))
                assertEquals(listOf(original, last), store.events)
            }
            assertTrue(service.saves.isEmpty())
            assertEquals(listOf(removed.id), service.deletes)

            assertTrue(store.undo())
            assertEquals(listOf(original, removed, last), store.events)
            assertEquals(listOf(removed to false), service.saves)
        }
    }
}

private class TestEventService(vararg events: CalendarEvent) : CalendarEventService {
    private val rows = events.associateBy { it.id }.toMutableMap()
    val saves = mutableListOf<Pair<CalendarEvent, Boolean>>()
    val deletes = mutableListOf<String>()
    var beforeSave: suspend (CalendarEvent) -> Unit = {}
    var onLoad: () -> Unit = {}

    override fun changes() = emptyFlow<Unit>()

    override suspend fun load(): List<CalendarEvent> {
        onLoad()
        return rows.values.toList()
    }

    override suspend fun save(event: CalendarEvent, existing: Boolean) {
        saves.add(event to existing)
        beforeSave(event)
        rows[event.id] = event
    }

    override suspend fun delete(id: String) {
        deletes.add(id)
        rows.remove(id)
    }
}
