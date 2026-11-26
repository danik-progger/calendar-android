package dev.calendar.mobile

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import java.time.LocalDate
import kotlinx.coroutines.launch

data class EditorDraft(val event: CalendarEvent, val existing: Boolean)
private enum class Field { Date, Start, End, Calendar }


@Composable
fun EventEditor(draft: EditorDraft, compact: Boolean, cancel: () -> Unit, save: suspend (CalendarEvent) -> Boolean,
    delete: suspend () -> Boolean, error: () -> String?) {
    var event by remember { mutableStateOf(draft.event) }
    var startText by remember { mutableStateOf(clockLabel(event.start)) }
    var endText by remember { mutableStateOf(clockLabel(event.end)) }
    var message by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<Field?>(null) }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var submitting by remember { mutableStateOf(false) }
    fun close() { if (!submitting) cancel() }
    fun submit(operation: suspend () -> Boolean) {
        if (submitting) return
        submitting = true
        scope.launch {
            try {
                if (operation()) cancel() else message = error()
            } finally {
                submitting = false
            }
        }
    }
    fun updateTime(field: Field) {
        if (field == Field.Start) parseMinute(startText)?.let { event = event.times(it, event.end) }
        if (field == Field.End) parseMinute(endText)?.let { event = event.times(event.start, it) }
        startText = clockLabel(event.start)
        endText = clockLabel(event.end)
    }
    fun saveDraft() {
        if (!event.allDay) {
            val start = parseMinute(startText) ?: event.start
            val end = parseMinute(endText) ?: event.end
            event = event.times(start, end)
        }
        val draftEvent = event
        submit { save(draftEvent) }
    }

    Dialog(onDismissRequest = ::close, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        SideEffect { window.setDimAmount(0f) }
        BoxWithConstraints(Modifier.fillMaxSize().background(ModalOverlayColor)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
            contentAlignment = Alignment.TopCenter) {
            val screenHeight = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                // Older dialog windows can shrink with the keyboard.
                LocalConfiguration.current.screenHeightDp.dp
            } else maxHeight
            val topInset = screenHeight * 0.14f
            Box(Modifier.matchParentSize().clickable(
                interactionSource = null, indication = null, onClick = ::close,
            ).clearAndSetSemantics {})
            Column(Modifier.padding(top = topInset).imePadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                .widthIn(max = 420.dp).fillMaxWidth()
                .dropShadow(RoundedCornerShape(8.dp), Shadow(radius = 24.dp, color = EventShadowColor, offset = DpOffset(0.dp, 12.dp)))
                .background(SurfaceColor, RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState()).semantics { paneTitle = "Edit event" }) {
                val inset = if (compact) 22.dp else 28.dp
                Column {
                    EditorField(event.title, { event = event.copy(title = it); message = null }, "Event title",
                        Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 16.dp)
                            .padding(horizontal = if (compact) 24.dp else 28.dp),
                        size = 20, color = TextStrong, title = true, action = ::saveDraft)
                    Row(Modifier.padding(horizontal = inset).heightIn(min = 32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (compact) 9.dp else 10.dp)) {
                        Box(Modifier.widthIn(max = 140.dp)) {
                            val interaction = remember { MutableInteractionSource() }
                            Row(Modifier.fillMaxWidth().height(32.dp)
                                .semantics { contentDescription = "Date, ${event.date.label("MMMM d, yyyy")}" }
                                .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                                    focus.clearFocus()
                                    picker = Field.Date
                                }, verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CalendarIcon(IconName.CalendarDays, 16, PlaceholderColor, interaction)
                                Text(event.date.label("MM.dd.yyyy"), color = Muted, fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium, maxLines = 1,
                                    style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
                            }
                            if (picker == Field.Date) Picker(onClose = { picker = null }) {
                                DatePanel(event.date) {
                                    event = event.copy(date = it)
                                    picker = null
                                }
                            }
                        }
                        if (!event.allDay) Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TimeInput(startText, { startText = it }, "Start time", picker == Field.Start,
                                open = { picker = Field.Start }, close = { if (picker == Field.Start) picker = null },
                                commit = { updateTime(Field.Start) }, choose = {
                                    startText = clockLabel(it)
                                    updateTime(Field.Start)
                                    picker = null
                                })
                            Text("–", color = PlaceholderColor, fontSize = 12.sp)
                            TimeInput(endText, { endText = it }, "End time", picker == Field.End,
                                open = { picker = Field.End }, close = { if (picker == Field.End) picker = null },
                                commit = { updateTime(Field.End) }, choose = {
                                    endText = clockLabel(it)
                                    updateTime(Field.End)
                                    picker = null
                                })
                        }
                    }
                    val allDayInteraction = remember { MutableInteractionSource() }
                    Row(Modifier.padding(horizontal = inset).height(32.dp).toggleable(
                        value = event.allDay, role = Role.Checkbox, interactionSource = allDayInteraction, indication = null
                    ) {
                        focus.clearFocus()
                        event = event.copy(allDay = it)
                    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(16.dp).background(if (event.allDay) Accent else ClearColor, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center) {
                            if (event.allDay) CalendarIcon(IconName.Check, 12, OnSolidColor, allDayInteraction)
                            else CalendarIcon(IconName.Square, 16, Muted, allDayInteraction)
                        }
                        Text("All day", color = Muted, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                    }
                    Box(Modifier.padding(horizontal = inset).padding(top = 4.dp)) {
                        val interaction = remember { MutableInteractionSource() }
                        Row(Modifier.height(32.dp).clickable(
                            interactionSource = interaction, indication = null, role = Role.Button) {
                            focus.clearFocus()
                            picker = Field.Calendar
                        }.semantics { contentDescription = "Calendar, ${event.calendar.name}" },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Swatch(event.calendar)
                            Text(event.calendar.name, color = Muted, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                            CalendarIcon(IconName.ChevronDown, 13, PlaceholderColor, interaction)
                        }
                        if (picker == Field.Calendar) Picker(onClose = { picker = null }) {
                            Column(Modifier.width(142.dp).verticalScroll(rememberScrollState())
                                .semantics { paneTitle = "Choose calendar" }) {
                                EventCalendar.entries.forEach { calendar ->
                                    PickerOption(calendar.name, selected = calendar == event.calendar, calendar = calendar) {
                                        event = event.copy(calendar = calendar)
                                        picker = null
                                    }
                                }
                            }
                        }
                    }
                    EditorField(event.notes, { event = event.copy(notes = it) }, "Description",
                        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp).padding(horizontal = inset).heightIn(min = 67.dp),
                        placeholder = "Add description...", multi = true)
                    message?.let { Text(it, Modifier.padding(horizontal = inset).padding(bottom = 8.dp), color = ErrorColor, fontSize = 12.sp) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (draft.existing) TextAction("Delete", Modifier.size(52.dp, 32.dp), label = "Delete event",
                        color = Muted, enabled = !submitting,
                        weight = FontWeight.SemiBold, fontSize = 13, showFocusRing = true) { submit(delete) }
                    Spacer(Modifier.weight(1f))
                    TextAction("Cancel", Modifier.size(60.dp, 32.dp), color = Muted, enabled = !submitting,
                        weight = FontWeight.SemiBold, fontSize = 13, showFocusRing = true, action = ::close)
                    TextAction("Save", Modifier.size(52.dp, 32.dp).background(TextStrong, RoundedCornerShape(6.dp)),
                        color = OnSolidColor, enabled = !submitting, weight = FontWeight.SemiBold, fontSize = 13,
                        showFocusRing = true, action = ::saveDraft)
                }
            }
        }
    }
}

@Composable private fun EditorField(value: String, change: (String) -> Unit, label: String, modifier: Modifier,
    placeholder: String = label, size: Int = 12, color: Color = Muted, title: Boolean = false, multi: Boolean = false,
    tabularNumbers: Boolean = false, action: () -> Unit = {}) {
    val style = LocalTextStyle.current.copy(color = color, fontSize = size.sp,
        lineHeight = (if (size == 20) 27 else 18).sp,
        fontWeight = if (title) FontWeight.Bold else FontWeight.Medium,
        letterSpacing = if (title) (-0.02).em else 0.em,
        fontFeatureSettings = if (tabularNumbers) "tnum" else null)
    BasicTextField(value, change, modifier.semantics { contentDescription = label }, singleLine = !multi, maxLines = if (multi) 5 else 1,
        textStyle = style,
        cursorBrush = SolidColor(TextStrong), keyboardOptions = KeyboardOptions(
            imeAction = if (multi) ImeAction.Default else ImeAction.Done), keyboardActions = KeyboardActions(onDone = { action() }),
        decorationBox = { input ->
            Box {
                if (value.isEmpty()) Text(placeholder, color = Muted, style = style,
                    modifier = Modifier.clearAndSetSemantics {})
                input()
            }
        })
}

@Composable private fun TimeInput(value: String, change: (String) -> Unit, label: String, show: Boolean,
    open: () -> Unit, close: () -> Unit, commit: () -> Unit, choose: (Int) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val openOnTap by rememberUpdatedState(open)
    val selectionColors = LocalTextSelectionColors.current
    BackHandler(enabled = show && focused, onBack = close)
    Box {
        CompositionLocalProvider(LocalTextSelectionColors provides if (show) {
            TextSelectionColors(ClearColor, selectionColors.backgroundColor)
        } else selectionColors) {
            EditorField(value, change, label, Modifier.width(42.dp)
                .onFocusChanged {
                    if (focused == it.isFocused) return@onFocusChanged
                    focused = it.isFocused
                    if (focused) open() else {
                        commit()
                        close()
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (focused) openOnTap()
                    }
                }, tabularNumbers = true, action = { commit(); close() })
        }
        if (show) Picker(onClose = close, focusable = !focused) {
            val currentMinute = parseMinute(value)
            val listState = rememberLazyListState(((currentMinute ?: 0) / 30).coerceAtMost(47))
            LazyColumn(Modifier.size(90.dp, 166.dp), state = listState) {
                items(48) { index ->
                    val minute = index * 30
                    PickerOption(clockLabel(minute), selected = minute == currentMinute) { choose(minute) }
                }
            }
        }
    }
}

@Composable private fun PickerOption(label: String, selected: Boolean, calendar: EventCalendar? = null, choose: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val keyboardFocus = focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val background = when {
        selected -> TextStrong
        hovered || pressed || keyboardFocus -> HoverColor
        else -> ClearColor
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp).clip(RoundedCornerShape(5.dp)).background(background)
        .selectable(selected = selected, interactionSource = interaction, indication = null,
            role = Role.Button, onClick = choose)
        .padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (calendar != null) Box(Modifier.size(14.dp).background(calendar.color(), RoundedCornerShape(5.dp)))
        Text(label, color = if (selected) OnSolidColor else TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            style = LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum"),
            maxLines = 1)
    }
}

@Composable internal fun Picker(onClose: () -> Unit, focusable: Boolean = true, content: @Composable () -> Unit) {
    Popup(menuPosition(), onDismissRequest = onClose, properties = PopupProperties(focusable = focusable)) {
        val shape = RoundedCornerShape(8.dp)
        Box(Modifier.dropShadow(shape, Shadow(radius = 12.dp, color = EventShadowColor, offset = DpOffset(0.dp, 8.dp)))
            .background(SurfaceColor, shape).padding(7.dp)) { content() }
    }
}

@Composable
private fun menuPosition(): PopupPositionProvider {
    val density = LocalDensity.current
    return remember(density) {
        val offset = with(density) { 5.dp.roundToPx() }
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
            ): IntOffset {
                val rightAligned = layoutDirection == LayoutDirection.Rtl
                val x = if (rightAligned) anchorBounds.right - popupContentSize.width else anchorBounds.left
                val below = anchorBounds.bottom + offset
                val y = if (below + popupContentSize.height <= windowSize.height) below
                    else anchorBounds.top - offset - popupContentSize.height
                return IntOffset(
                    x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
                )
            }
        }
    }
}

@Composable internal fun DatePanel(selectedDate: LocalDate, choose: (LocalDate) -> Unit) {
    var month by remember(selectedDate) { mutableStateOf(selectedDate.withDayOfMonth(1)) }
    val first = month.monthGridStart()
    Column(Modifier.width(238.dp).padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(month.label("MMMM yyyy"), color = TextStrong, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            TextAction("", Modifier.size(26.dp), label = "Previous month", color = TextSecondary, icon = IconName.ChevronLeft) { month = month.minusMonths(1) }
            Spacer(Modifier.width(2.dp))
            TextAction("", Modifier.size(26.dp), label = "Next month", color = TextSecondary, icon = IconName.ChevronRight) { month = month.plusMonths(1) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { day ->
                Box(Modifier.weight(1f).height(29.dp), contentAlignment = Alignment.Center) { Text(day, color = PlaceholderColor, fontSize = 12.sp) }
            }
        }
        repeat(6) { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(7) { index ->
                    val day = first.plusDays((week * 7 + index).toLong())
                    val active = day == selectedDate
                    TextAction(day.dayOfMonth.toString(), Modifier.weight(1f).height(29.dp)
                        .background(if (active) TextStrong else SurfaceColor, RoundedCornerShape(5.dp))
                        .semantics { selected = active }, color = if (active) OnSolidColor else if (day.month == month.month) TextSecondary else OutsideMonthColor,
                        label = day.label("EEEE, MMMM d, yyyy")) { choose(day) }
                }
            }
        }
    }
}

@Composable private fun Swatch(calendar: EventCalendar) {
    Box(Modifier.size(16.dp).background(calendar.color(), RoundedCornerShape(6.dp)))
}
