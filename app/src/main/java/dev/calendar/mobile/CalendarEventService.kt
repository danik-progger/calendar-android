package dev.calendar.mobile

import kotlinx.coroutines.flow.Flow

interface CalendarEventService {
    fun changes(): Flow<Unit>
    suspend fun load(): List<CalendarEvent>
    suspend fun save(event: CalendarEvent, existing: Boolean)
    suspend fun delete(id: String)
}
