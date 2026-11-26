package dev.calendar.mobile

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.icu.text.DateFormat.Field
import android.icu.text.SimpleDateFormat
import android.icu.util.GregorianCalendar
import android.icu.util.TimeZone
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.text.NumberFormat
import java.text.FieldPosition
import java.util.Date
import java.util.Locale

internal const val ACTION_PICK_DATE = "dev.calendar.mobile.android.PICK_DATE"

class CalendarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) update(context, manager, id, manager.getAppWidgetOptions(id))
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, id, options)
    }

    companion object {
        internal fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CalendarWidgetProvider::class.java))
            for (id in ids) update(context, manager, id, manager.getAppWidgetOptions(id))
        }

        private fun update(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
            val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            val heightKey = if (portrait) {
                AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
            } else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
            val height = options.getInt(heightKey, 0)
            val heightPx = (height * context.resources.displayMetrics.density).toInt()
            manager.updateAppWidget(id, render(context, heightPx = heightPx))
        }

        internal fun render(context: Context, now: LocalDateTime = LocalDateTime.now(), heightPx: Int = 0): RemoteViews {
            val today = now.toLocalDate()
            val locale = context.resources.configuration.locales[0]
            val snapshot = CalendarWidgetSnapshot.load(context)?.takeIf { it.through >= today }
            val last = today.plusDays(CalendarWidgetSnapshot.AGENDA_DAYS - 1)
            val minute = now.hour * 60 + now.minute
            val upcoming = snapshot?.events.orEmpty().filter {
                it.day in today..last && (it.day != today || (it.end ?: 1440) > minute)
            }.take(CalendarWidgetSnapshot.EVENT_LIMIT)
            val events = fittingEvents(context, upcoming, heightPx)
            val openCalendar = PendingIntent.getActivity(
                context, 0, Intent(context, CalendarActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            val openDatePicker = PendingIntent.getActivity(
                context, 0, Intent(context, CalendarActivity::class.java).setAction(ACTION_PICK_DATE),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return RemoteViews(context.packageName, R.layout.calendar_widget).apply {
                setOnClickPendingIntent(R.id.widget_root, openCalendar)
                setOnClickPendingIntent(R.id.widget_month_panel, openDatePicker)
                renderMonth(context, today, locale)
                renderAgenda(context, today, locale, events, snapshot != null)
            }
        }

        private fun RemoteViews.renderMonth(context: Context, today: LocalDate, locale: Locale) {
            val gridStart = today.monthGridStart()
            val dateLabel = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
            val numbers = NumberFormat.getIntegerInstance(locale)
            val monthLabel = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMy"), locale)
            setTextViewText(R.id.widget_month_title, today.format(monthLabel))
            setContentDescription(R.id.widget_month_panel, context.getString(R.string.calendar_widget_pick_date, today.format(dateLabel)))

            removeAllViews(R.id.widget_weeks)
            for (week in -1..5) {
                val row = RemoteViews(context.packageName, R.layout.calendar_widget_week)
                for (column in 0..6) {
                    val cell = RemoteViews(context.packageName, R.layout.calendar_widget_day)
                    if (week < 0) {
                        val weekday = DayOfWeek.MONDAY.plus(column.toLong()).getDisplayName(TextStyle.NARROW_STANDALONE, locale)
                        cell.setTextViewText(R.id.widget_day, weekday)
                        cell.setTextColor(R.id.widget_day, context.getColor(R.color.widget_calendar_month_muted))
                        cell.setTextViewTextSize(R.id.widget_day, TypedValue.COMPLEX_UNIT_SP, 11f)
                    } else {
                        val date = gridStart.plusDays((week * 7 + column).toLong())
                        val color = when {
                            date == today -> R.color.widget_calendar_surface
                            date.month == today.month -> R.color.widget_calendar_muted
                            else -> R.color.widget_calendar_month_muted
                        }
                        cell.setTextViewText(R.id.widget_day, numbers.format(date.dayOfMonth))
                        cell.setTextColor(R.id.widget_day, context.getColor(color))
                        if (date == today) cell.setInt(R.id.widget_day, "setBackgroundResource", R.drawable.calendar_widget_today)
                    }
                    row.addView(R.id.widget_week, cell)
                }
                addView(R.id.widget_weeks, row)
            }
        }

        private fun fittingEvents(context: Context, events: List<CalendarWidgetEvent>, heightPx: Int): List<CalendarWidgetEvent> {
            val resources = context.resources
            var remaining = maxOf(heightPx, resources.getDimensionPixelSize(R.dimen.widget_min_height)) -
                2 * resources.getDimensionPixelSize(R.dimen.widget_inset)
            val headingHeight = resources.getDimensionPixelSize(R.dimen.widget_agenda_day_height) +
                resources.getDimensionPixelSize(R.dimen.widget_agenda_event_gap)
            val cardHeight = resources.getDimensionPixelSize(R.dimen.widget_agenda_title_height) +
                2 * resources.getDimensionPixelSize(R.dimen.widget_agenda_card_inset)
            val timeHeight = resources.getDimensionPixelSize(R.dimen.widget_agenda_time_height) +
                resources.getDimensionPixelSize(R.dimen.widget_agenda_time_gap)
            var previousDay: LocalDate? = null
            return events.takeWhile { event ->
                val startsDay = event.day != previousDay
                remaining -= cardHeight
                if (event.start != null && event.end != null) remaining -= timeHeight
                if (startsDay) remaining -= headingHeight
                if (previousDay != null) remaining -= resources.getDimensionPixelSize(
                    if (startsDay) R.dimen.widget_agenda_day_gap else R.dimen.widget_agenda_event_gap,
                )
                previousDay = event.day
                remaining >= 0
            }
        }

        private fun RemoteViews.renderAgenda(
            context: Context, today: LocalDate, locale: Locale, events: List<CalendarWidgetEvent>, synced: Boolean,
        ) {
            removeAllViews(R.id.widget_agenda_events)
            setViewVisibility(R.id.widget_empty, if (events.isEmpty()) View.VISIBLE else View.GONE)
            if (events.isEmpty()) {
                val message = if (synced) R.string.calendar_widget_empty else R.string.calendar_widget_sync
                setTextViewText(R.id.widget_empty, context.getString(message))
                return
            }
            val dayFormatter = SimpleDateFormat("EEE d", locale).apply {
                calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"), locale)
            }
            val todayColor = context.getColor(R.color.widget_calendar_today_text)
            for ((index, event) in events.withIndex()) {
                val day = event.day
                val startsDay = events.getOrNull(index - 1)?.day != day
                val row = RemoteViews(context.packageName, R.layout.calendar_widget_event)
                row.setViewVisibility(R.id.widget_event_day, if (startsDay) View.VISIBLE else View.GONE)
                if (startsDay) {
                    val label = agendaDayLabel(day, dayFormatter, if (day == today) todayColor else null)
                    row.setTextViewText(R.id.widget_event_day, label)
                }
                if (index != 0) {
                    val gap = if (startsDay) R.dimen.widget_agenda_day_gap else R.dimen.widget_agenda_event_gap
                    row.setViewPadding(R.id.widget_event_row, 0, context.resources.getDimensionPixelSize(gap), 0, 0)
                }
                row.setTextViewText(R.id.widget_event_title, event.title)
                val color = Color(0xff000000 or event.color.toLong())
                val fill = mixEventColor(SurfaceColor, color, if (event.start == null) 0.28f else 0.24f).toArgb()
                row.setTextColor(R.id.widget_event_title, mixEventColor(TextStrong, color, 0.35f).toArgb())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    row.setColorStateList(R.id.widget_event_card, "setBackgroundTintList", ColorStateList.valueOf(fill))
                } else {
                    row.setInt(R.id.widget_event_card, "setBackgroundColor", fill)
                }
                if (event.start != null && event.end != null) {
                    row.setTextViewText(R.id.widget_event_time, "${eventTime(event.start)} – ${eventTime(event.end)}")
                    row.setContentDescription(R.id.widget_event_time, context.getString(
                        R.string.calendar_widget_time_range, clockLabel(event.start), clockLabel(event.end),
                    ))
                    row.setTextColor(R.id.widget_event_time, HourLabelColor.toArgb())
                    row.setViewVisibility(R.id.widget_event_time, View.VISIBLE)
                }
                addView(R.id.widget_agenda_events, row)
            }
        }

        private fun agendaDayLabel(day: LocalDate, formatter: SimpleDateFormat, color: Int?): CharSequence {
            val date = Date.from(day.atStartOfDay().toInstant(ZoneOffset.UTC))
            val dayNumber = FieldPosition(Field.DAY_OF_MONTH)
            val label = formatter.format(date, StringBuffer(), dayNumber).toString()
            if (color == null || dayNumber.endIndex <= dayNumber.beginIndex) return label
            return SpannableString(label).apply {
                setSpan(ForegroundColorSpan(color), dayNumber.beginIndex, dayNumber.endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
