package dev.calendar.mobile

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class CalendarWidgetTest {
    @Test fun reapplyingWidgetReplacesTheGridAndAgenda() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$name-reapply-test", mode)
        }
        instrumentation.runOnMainSync {
            try {
                CalendarWidgetSnapshot.clear(context)
                val widget = inflate(context)
                val weeks = widget.findViewById<LinearLayout>(R.id.widget_weeks)
                val status = widget.findViewById<TextView>(R.id.widget_empty)
                assertEquals(View.VISIBLE, status.visibility)

                CalendarWidgetSnapshot.publish(context, listOf(CalendarEvent(LocalDate.now().plusDays(1), title = "Review")))
                CalendarWidgetProvider.render(context).reapply(context, widget)
                assertEquals(7, weeks.childCount)
                assertEquals(View.GONE, status.visibility)
                assertEquals("Review", widget.findViewById<TextView>(R.id.widget_event_title).text.toString())

                CalendarWidgetSnapshot.clear(context)
                CalendarWidgetProvider.render(context).reapply(context, widget)
                assertEquals(7, weeks.childCount)
                assertEquals(View.VISIBLE, status.visibility)
                assertEquals(null, widget.findViewById<TextView>(R.id.widget_event_title))
            } finally {
                CalendarWidgetSnapshot.clear(context)
                CalendarWidgetProvider.refresh(instrumentation.targetContext)
            }
        }
    }

    @Test fun inflatesMonthAndAgendaAndClearsTheSnapshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply { setLocale(Locale.US) }
        val context = object : ContextWrapper(instrumentation.targetContext.createConfigurationContext(configuration)) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$name-test", mode)
        }
        val today = LocalDate.now()
        val morning = today.atTime(8, 0)
        instrumentation.runOnMainSync {
            try {
                CalendarWidgetSnapshot.publish(context, listOf(
                    CalendarEvent(today.plusDays(1), title = "Review", start = 600, end = 645),
                    CalendarEvent(today, title = "Day off", allDay = true),
                    CalendarEvent(today, title = "Finished", start = 420, end = 480),
                    CalendarEvent(today.minusDays(1), title = "Past"),
                    CalendarEvent(today.plusDays(8), title = "Later"),
                ))
                val widget = inflate(context, morning)
                val monthPicker = widget.findViewById<View>(R.id.widget_month_panel)
                assertTrue(monthPicker.isClickable)
                val monthTitle = widget.findViewById<TextView>(R.id.widget_month_title)
                assertEquals(today.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)), monthTitle.text.toString())
                val weeks = widget.findViewById<LinearLayout>(R.id.widget_weeks)
                assertEquals(7, weeks.childCount)
                for (week in 0 until weeks.childCount) {
                    val row = weeks.getChildAt(week) as LinearLayout
                    assertEquals(7, row.childCount)
                    assertTrue(row.height > 0)
                }
                val weekday = (weeks.getChildAt(0) as LinearLayout).getChildAt(0).findViewById<TextView>(R.id.widget_day)
                assertEquals("M", weekday.text.toString())
                val todayIndex = today.dayOfMonth - 1 + today.withDayOfMonth(1).dayOfWeek.value - 1
                val todayCell = (weeks.getChildAt(todayIndex / 7 + 1) as LinearLayout).getChildAt(todayIndex % 7)
                val highlightedDay = todayCell.findViewById<TextView>(R.id.widget_day)
                assertEquals(context.getColor(R.color.widget_calendar_surface), highlightedDay.currentTextColor)
                assertEquals(context.getColor(R.color.widget_calendar_text), (highlightedDay.background as GradientDrawable).color?.defaultColor)
                assertTrue(highlightedDay.textSize < weekday.textSize)
                val agenda = widget.findViewById<LinearLayout>(R.id.widget_agenda_events)
                assertEquals(2, agenda.childCount)
                val allDay = agenda.getChildAt(0)
                val todayHeading = allDay.findViewById<TextView>(R.id.widget_event_day)
                assertEquals(today.format(DateTimeFormatter.ofPattern("EEE d", Locale.US)), todayHeading.text.toString())
                val heading = todayHeading.text as Spanned
                val dateAccent = heading.getSpans(0, heading.length, ForegroundColorSpan::class.java).single()
                val coloredText = heading.subSequence(heading.getSpanStart(dateAccent), heading.getSpanEnd(dateAccent))
                assertEquals(today.dayOfMonth.toString(), coloredText.toString())
                assertEquals(context.getColor(R.color.widget_calendar_today_text), dateAccent.foregroundColor)
                assertEquals(context.getColor(R.color.widget_calendar_text), todayHeading.currentTextColor)
                assertEquals("Day off", allDay.findViewById<TextView>(R.id.widget_event_title).text.toString())
                assertEquals(View.GONE, allDay.findViewById<View>(R.id.widget_event_time).visibility)
                val timed = agenda.getChildAt(1)
                assertEquals("Review", timed.findViewById<TextView>(R.id.widget_event_title).text.toString())
                val time = timed.findViewById<TextView>(R.id.widget_event_time)
                assertEquals(View.VISIBLE, time.visibility)
                assertEquals("10 – 10:45", time.text.toString())
                assertTrue(timed.bottom <= agenda.height)

                CalendarWidgetSnapshot.publish(context, (0L..2L).map { offset ->
                    CalendarEvent(today.plusDays(offset), title = "Event ${offset + 1}")
                })
                val compact = inflate(context, morning).findViewById<LinearLayout>(R.id.widget_agenda_events)
                val fullAgenda = inflate(context, morning, heightDp = 186).findViewById<LinearLayout>(R.id.widget_agenda_events)
                assertEquals(2, compact.childCount)
                assertEquals(3, fullAgenda.childCount)
                assertTrue(fullAgenda.getChildAt(2).bottom <= fullAgenda.height)

                CalendarWidgetSnapshot.clear(context)
                val empty = inflate(context).findViewById<TextView>(R.id.widget_empty)
                assertEquals(context.getString(R.string.calendar_widget_sync), empty.text.toString())
            } finally {
                CalendarWidgetSnapshot.clear(context)
                CalendarWidgetProvider.refresh(instrumentation.targetContext)
            }
        }
    }

    private fun inflate(context: Context, now: LocalDateTime = LocalDateTime.now(), heightDp: Int = 144): View {
        val density = context.resources.displayMetrics.density
        val height = (heightDp * density).toInt()
        val widget = CalendarWidgetProvider.render(context, now, height).apply(context, FrameLayout(context))
        widget.measure(
            View.MeasureSpec.makeMeasureSpec((300 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        widget.layout(0, 0, widget.measuredWidth, widget.measuredHeight)
        return widget
    }
}
