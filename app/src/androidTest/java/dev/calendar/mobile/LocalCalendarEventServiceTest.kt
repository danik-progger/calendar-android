package dev.calendar.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class LocalCalendarEventServiceTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun recoversAndEditsEventsFromThePreviousApp() = runBlocking {
        val directory = files.newFolder()
        val path = File(directory, "events.json")
        val service = LocalCalendarEventService(path)
        assertFalse(service.exists)

        File(directory, "events.json.bak").writeText(
            """
            [
                {"date":"2026-11-21","id":"legacy-birthday","title":"Birthday","calendar":"birthdays",
                 "notes":"","allDay":true,"start":540,"end":585,"duration":45},
                {"date":"2026-11-22","id":"legacy-call","title":"Call","calendar":"personal",
                 "notes":"Saved offline","allDay":false,"start":1380,"end":1440,"duration":60}
            ]
            """.trimIndent(),
        )
        assertTrue(service.exists)
        val birthday = CalendarEvent(LocalDate.of(2026, 11, 21), id = "legacy-birthday", title = "Birthday",
            calendar = EventCalendar.Birthdays, allDay = true)
        val call = CalendarEvent(LocalDate.of(2026, 11, 22), id = "legacy-call", title = "Call",
            calendar = EventCalendar.Personal, notes = "Saved offline", start = 1380, end = 1440, duration = 60)
        assertEquals(listOf(birthday, call), service.load())

        val edited = call.copy(title = "Updated call")
        service.save(edited, existing = true)
        assertEquals(listOf(birthday, edited), LocalCalendarEventService(path).load())
        service.delete(edited.id)
        assertEquals(listOf(birthday), LocalCalendarEventService(path).load())
    }
}
