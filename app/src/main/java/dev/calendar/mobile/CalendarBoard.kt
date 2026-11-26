package dev.calendar.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.floor

private val rail = 52.dp
private val header = 42.dp
private val moveEasing = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)
private val fadeEasing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
private data class DragPreview(val original: CalendarEvent, val event: CalendarEvent?)
private data class PlacedEvent(val item: CalendarEvent, val position: DpOffset, val laneTop: Dp, val width: Dp)

@Composable
fun CalendarBoard(events: List<CalendarEvent>, days: List<LocalDate>, now: LocalDateTime, dropRevision: Int,
    open: (CalendarEvent) -> Unit, create: (LocalDate, Int?) -> Unit,
    move: (CalendarEvent) -> Unit, change: (CalendarEvent) -> Unit, shift: (Int) -> Unit) {
    val scroll = rememberScrollState()
    val horizontal = rememberScrollState()
    val previousPlacement = remember(dropRevision) { mutableMapOf<String, PlacedEvent>() }
    var drag by remember { mutableStateOf<DragPreview?>(null) }
    val onShift by rememberUpdatedState(shift)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val column = maxOf((maxWidth - rail) / days.size, 150.dp)
        val allDay = maxOf(34.dp, (days.maxOf { day -> events.count { it.date == day && it.allDay } } * 26 + 5).dp)
        val sticky = header + allDay
        val hour = maxOf(1.dp, (maxHeight - sticky) / 8)
        val viewport = maxHeight
        val density = LocalDensity.current
        val contentWidth = rail + column * days.size
        val allDayCards = days.flatMapIndexed { index, day ->
            events.filter { it.date == day && it.allDay }.mapIndexed { row, event ->
                PlacedEvent(event, DpOffset(rail + column * index, 3.dp + 26.dp * row), header, column - 4.dp)
            }
        }
        val timedCards = days.flatMapIndexed { index, day ->
            layoutTimedEvents(events.filter { it.date == day && !it.allDay }, rail + column * index, column, hour, sticky)
        }
        val placements = (allDayCards + timedCards).associateBy { it.item.id }
        SideEffect {
            previousPlacement.clear()
            previousPlacement.putAll(placements)
        }
        val update: (CalendarEvent, Offset, Offset) -> Unit = { event, origin, delta ->
            with(density) {
                val step = hour.toPx()
                val timelineTop = sticky.toPx() - scroll.value
                val point = origin + delta
                val index = floor((point.x - rail.toPx()) / column.toPx()).toInt()
                val next = if (index !in days.indices || point.y < header.toPx() || point.y > viewport.toPx()) null
                else {
                    val grab = if (event.allDay) 0f else (origin.y - timelineTop - event.start / 60f * step).coerceAtLeast(0f)
                    val minute = snapped((point.y - grab - timelineTop) / step * 60).coerceIn(0, 1440)
                    event.moved(days[index], if (point.y < sticky.toPx()) null else minute)
                }
                drag = DragPreview(event, next)
            }
        }
        val finish: () -> Unit = {
            drag?.let { current -> current.event?.takeIf { it != current.original }?.let(move) }
            drag = null
        }
        LaunchedEffect(Unit) { scroll.scrollTo(with(density) { (hour * 9).roundToPx() }) }

        Column(Modifier.fillMaxSize().horizontalScroll(horizontal, enabled = contentWidth > maxWidth).width(contentWidth)) {
            Row(Modifier.width(contentWidth).height(header).edge(), verticalAlignment = Alignment.CenterVertically) {
                TextAction("", Modifier.size(rail, header), color = Muted,
                    label = "Add event", icon = IconName.Plus, iconSize = 14, showFocusRing = true) {
                    val day = days.firstOrNull { it == now.toLocalDate() } ?: days.first()
                    create(day, 9 * 60)
                }
                days.forEach { day ->
                    Row(Modifier.width(column), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(day.label("EEE"), fontSize = 11.sp, color = TextSecondary)
                        Text(day.label("d"), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = if (day == now.toLocalDate()) Accent else TextStrong)
                    }
                }
            }
            Box(Modifier.width(contentWidth).height(allDay).background(HoverColor).edge()) {
                Row(Modifier.matchParentSize()) {
                    Spacer(Modifier.width(rail))
                    days.forEach { day ->
                        Box(Modifier.width(column).fillMaxHeight().edge(true)
                            .semantics { contentDescription = "Add all-day event on ${day.label("MMMM d")}" }
                            .clickable(role = Role.Button) { create(day, null) })
                    }
                }
                key(dropRevision) {
                    EventLayer(allDayCards, placements, previousPlacement, hour, scroll,
                        drag?.original?.id, open, update, finish, { drag = null }, change)
                }
                drag?.event?.takeIf { it.allDay }?.let {
                    val index = days.indexOf(it.date)
                    if (index >= 0) CardSkin(it, column - 4.dp, hour,
                        Modifier.offset(rail + column * index, 3.dp), preview = true)
                }
            }
            val swipe = if (days.size == 1) Modifier.pointerInput(Unit) {
                var distance = 0f
                detectHorizontalDragGestures(onDragStart = { distance = 0f }, onDragEnd = {
                    if (drag == null && abs(distance) > 65.dp.toPx()) onShift(if (distance < 0) 1 else -1)
                }) { _, dx -> distance += dx }
            } else Modifier
            Box(Modifier.width(contentWidth).weight(1f).then(swipe).verticalScroll(scroll)) {
                Row(Modifier.height(hour * 24)) {
                    Column(Modifier.width(rail)) {
                        repeat(24) { index ->
                            Box(Modifier.fillMaxWidth().height(hour), contentAlignment = Alignment.TopCenter) {
                                if (index > 0) Text(index.toString(), Modifier.offset(y = (-6).dp), color = HourLabelColor, fontSize = 10.sp)
                            }
                        }
                    }
                    days.forEach { day ->
                        Column(Modifier.width(column).fillMaxHeight().edge(true)) {
                            repeat(24) { slot ->
                                Box(Modifier.fillMaxWidth().height(hour).edge()
                                    .semantics { contentDescription = "Add event on ${day.label("MMMM d")} at ${clockLabel(slot * 60)}" }
                                    .clickable(role = Role.Button) { create(day, slot * 60) })
                            }
                        }
                    }
                }
                key(dropRevision) {
                    EventLayer(timedCards, placements, previousPlacement, hour, scroll,
                        drag?.original?.id, open, update, finish, { drag = null }, change)
                }
                drag?.event?.takeIf { !it.allDay }?.let { preview ->
                    val index = days.indexOf(preview.date)
                    if (index >= 0) {
                        val dayEvents = events.filter { !it.allDay && it.date == preview.date && it.id != preview.id }
                        val card = layoutTimedEvents(dayEvents + preview, rail + column * index, column, hour, sticky)
                            .first { it.item.id == preview.id }
                        CardSkin(preview, card.width, hour, Modifier.offset(card.position.x, card.position.y), preview = true)
                    }
                }
                val today = days.indexOf(now.toLocalDate())
                if (today >= 0) {
                    val minute = now.hour * 60 + now.minute
                    Canvas(Modifier.offset(rail + column * today, hour * (minute / 60f)).size(column, 2.dp)) {
                        drawLine(Accent, Offset(0f, center.y), Offset(size.width, center.y), 2.dp.toPx())
                        drawCircle(Accent, 3.5.dp.toPx(), Offset(0f, center.y))
                    }
                }
            }
        }
    }
}

@Composable private fun EventLayer(cards: List<PlacedEvent>, placements: Map<String, PlacedEvent>,
    previousPlacement: Map<String, PlacedEvent>, hour: Dp, scroll: ScrollState, draggedId: String?,
    open: (CalendarEvent) -> Unit, update: (CalendarEvent, Offset, Offset) -> Unit,
    finish: () -> Unit, cancel: () -> Unit, change: (CalendarEvent) -> Unit) {
    val byId = cards.associateBy { it.item.id }
    val visibility = updateTransition(byId.keys, label = "Visible events")
    for (id in byId.keys + visibility.currentState) {
        key(id) {
            val current = byId[id]
            var last by remember { mutableStateOf(current) }
            SideEffect { if (current != null) last = current }
            val card = current ?: last
            if (card != null) {
                val density = LocalDensity.current
                val fromOtherLane = remember {
                    previousPlacement[id]?.takeIf { it.item.allDay != card.item.allDay }
                }
                val position = remember {
                    val initial = fromOtherLane?.let {
                        val scrollOffset = with(density) { scroll.value.toDp() }
                        val relativeScroll = if (card.item.allDay) -scrollOffset else scrollOffset
                        DpOffset(it.position.x, it.position.y + it.laneTop - card.laneTop + relativeScroll)
                    } ?: card.position
                    Animatable(initial, DpOffset.VectorConverter)
                }
                LaunchedEffect(card.position) {
                    if (position.value != card.position) position.animateTo(card.position, tween(240, easing = moveEasing))
                }
                visibility.AnimatedVisibility(visible = { id in it },
                    modifier = Modifier.offset { IntOffset(position.value.x.roundToPx(), position.value.y.roundToPx()) },
                    enter = if (fromOtherLane != null) EnterTransition.None else fadeIn(tween(180, easing = fadeEasing)),
                    exit = fadeOut(tween(180, easing = fadeEasing))) {
                    if (current != null) {
                        EventCard(card.item, card.width, hour,
                            { with(density) {
                                val top = card.laneTop.toPx() - if (card.item.allDay) 0 else scroll.value
                                Offset(position.value.x.toPx(), top + position.value.y.toPx())
                            } },
                            id == draggedId, open, update, finish, cancel, change)
                    } else if (id !in placements) {
                        CardSkin(card.item, card.width, hour)
                    }
                }
            }
        }
    }
}

private fun Modifier.edge(vertical: Boolean = false) = drawBehind {
    val edge = (if (vertical) size.width else size.height) - 0.5.dp.toPx()
    val start = if (vertical) Offset(edge, 0f) else Offset(0f, edge)
    val end = if (vertical) Offset(edge, size.height) else Offset(size.width, edge)
    drawLine(Line, start, end, 1.dp.toPx())
}

@Composable private fun EventCard(item: CalendarEvent, targetWidth: Dp, hour: Dp,
    position: () -> Offset, faded: Boolean, open: (CalendarEvent) -> Unit,
    update: (CalendarEvent, Offset, Offset) -> Unit, finish: () -> Unit, cancel: () -> Unit,
    change: (CalendarEvent) -> Unit) {
    val onDrag by rememberUpdatedState(update)
    val onFinish by rememberUpdatedState(finish)
    val onCancel by rememberUpdatedState(cancel)
    val cardPosition by rememberUpdatedState(position)
    val width by animateDpAsState(targetWidth, tween(240, easing = moveEasing), label = "Event width")
    val height by animateDpAsState(cardHeight(item, hour), tween(240, easing = moveEasing), label = "Event height")
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val keyboardFocus = focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard
    Box(Modifier.size(width, height)
        .then(if (keyboardFocus) Modifier.border(2.dp, ControlFocus, RoundedCornerShape(8.dp)) else Modifier)
        .pointerInput(item) {
            var origin = Offset.Zero
            var delta = Offset.Zero
            detectDragGesturesAfterLongPress(onDragStart = {
                origin = cardPosition() + it
                delta = Offset.Zero
            }, onDragEnd = { onFinish() }, onDragCancel = { onCancel() }) { pointer, amount ->
                pointer.consume()
                delta += amount
                onDrag(item, origin, delta)
            }
        }
        .semantics {
            contentDescription = item.title + if (item.allDay) ", all day" else ", ${clockLabel(item.start)} to ${clockLabel(item.end)}"
            if (!item.allDay) customActions = buildList {
                if (item.end < 1440) add(CustomAccessibilityAction("Extend by five minutes") {
                    change(item.copy(end = (item.end + 5).coerceAtMost(1440))); true
                })
                if (item.end - item.start > 5) add(CustomAccessibilityAction("Shorten by five minutes") {
                    change(item.copy(end = item.end - 5)); true
                })
            }
        }.clickable(interactionSource = interaction, indication = null, role = Role.Button) { open(item) }) {
        CardSkin(item, width, hour, opacity = if (faded) 0.24f else 1f, height = height)
    }
}

private fun layoutTimedEvents(events: List<CalendarEvent>, x: Dp, width: Dp, hour: Dp, laneTop: Dp): List<PlacedEvent> {
    val sorted = events.sortedWith(compareBy<CalendarEvent> { it.start }.thenByDescending { it.end }.thenBy { it.id })
    val cards = mutableListOf<PlacedEvent>()
    val group = mutableListOf<Pair<CalendarEvent, Int>>()
    val columnEnds = mutableListOf<Dp>()
    var groupEnd = 0.dp

    fun finishGroup() {
        if (group.isEmpty()) return
        val step = width / columnEnds.size
        for ((event, column) in group) {
            cards += PlacedEvent(event, DpOffset(x + step * column, hour * (event.start / 60f)),
                laneTop, step - minOf(4.dp, step / 2))
        }
        group.clear()
        columnEnds.clear()
    }

    for (event in sorted) {
        val top = hour * (event.start / 60f)
        if (top >= groupEnd) finishGroup()
        val bottom = maxOf(hour * (event.end / 60f), top + cardHeight(event, hour))
        val column = columnEnds.indexOfFirst { it <= top }
        group += event to if (column < 0) columnEnds.size else column
        if (column < 0) columnEnds.add(bottom) else columnEnds[column] = bottom
        groupEnd = maxOf(groupEnd, bottom)
    }
    finishGroup()
    return cards
}

private fun cardHeight(event: CalendarEvent, hour: Dp): Dp = if (event.allDay) 24.dp
    else maxOf(hour * ((event.end - event.start) / 60f), if (event.end - event.start < 30) 24.dp else 38.dp) - 3.dp

@Composable private fun CardSkin(event: CalendarEvent, width: Dp, hour: Dp, modifier: Modifier = Modifier,
    preview: Boolean = false, opacity: Float = 1f, height: Dp = cardHeight(event, hour)) {
    val shape = RoundedCornerShape(8.dp)
    val color = event.calendar.color()
    val details = !event.allDay && event.end - event.start >= 30
    val fill = mixEventColor(SurfaceColor, color, (if (event.allDay) 0.28f else 0.24f) * opacity)
    val background by animateColorAsState(fill, tween(180, easing = fadeEasing), label = "Calendar color")
    val time = if (details) "${eventTime(event.start)} – ${eventTime(event.end)}" else null
    Column(modifier.size(width, height).then(if (preview) Modifier.dropShadow(
        shape, Shadow(radius = 8.dp, color = EventShadowColor, offset = DpOffset(0.dp, 4.dp)),
    ) else Modifier)
        .clip(shape).background(background)
        .padding(horizontal = minOf(if (event.allDay) 10.dp else 8.dp, width / 4), vertical = if (details) 4.dp else 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Crossfade(event.title, animationSpec = tween(180, easing = fadeEasing), label = "Event title") { title ->
            Text(title, color = lerp(SurfaceColor, mixEventColor(TextStrong, color, 0.82f), opacity),
                fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics {})
        }
        Crossfade(time, animationSpec = tween(180, easing = fadeEasing), label = "Event time") { label ->
            if (label != null) Text(label, color = lerp(SurfaceColor, color, opacity), fontSize = 10.sp,
                lineHeight = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

internal fun mixEventColor(base: Color, category: Color, weight: Float): Color = Color(
    red = base.red + (category.red - base.red) * weight,
    green = base.green + (category.green - base.green) * weight,
    blue = base.blue + (category.blue - base.blue) * weight,
)
