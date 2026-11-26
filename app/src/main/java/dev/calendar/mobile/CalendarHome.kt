package dev.calendar.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

@Composable
internal fun TextAction(text: String, modifier: Modifier = Modifier, color: Color = Muted, enabled: Boolean = true,
    label: String? = null, weight: FontWeight = FontWeight.Normal, fontSize: Int = 12,
    icon: IconName? = null, iconSize: Int = 17, showFocusRing: Boolean = false, action: () -> Unit) {
    val interaction = if (icon != null || showFocusRing) remember { MutableInteractionSource() } else null
    val focused = if (showFocusRing && interaction != null) interaction.collectIsFocusedAsState().value else false
    val keyboardFocus = focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard
    Box(modifier.then(if (keyboardFocus) Modifier.border(2.dp, ControlFocus, RoundedCornerShape(5.dp)) else Modifier)
        .then(if (label == null) Modifier else Modifier.semantics { contentDescription = label })
        .clickable(interactionSource = interaction, indication = if (icon == null) LocalIndication.current else null,
            enabled = enabled, role = Role.Button, onClick = action),
        contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) CalendarIcon(icon, iconSize, color, interaction)
            if (text.isNotEmpty()) Text(text, color = color, fontSize = fontSize.sp, fontWeight = weight, maxLines = 1)
        }
    }
}

@Composable
fun CalendarHome(store: EventStore, email: String? = null, accountAction: (suspend () -> Unit)? = null,
    openLocalCalendar: (() -> Unit)? = null, openDatePicker: Boolean = false, onDatePickerOpened: () -> Unit = {}) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var editor by remember { mutableStateOf<EditorDraft?>(null) }
    var editorRevision by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var datePickerSelection by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (store.events == null) store.load() }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(30_000) } }
    LaunchedEffect(openDatePicker) {
        if (openDatePicker) {
            datePickerSelection = LocalDate.now()
            onDatePickerOpened()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().onPreviewKeyEvent {
        if (editor == null && it.type == KeyEventType.KeyDown && (it.isCtrlPressed || it.isMetaPressed) &&
            !it.isShiftPressed && !it.isAltPressed && it.key == Key.Z) {
            scope.launch { store.undo() }
            true
        } else false
    }) {
        val compact = maxWidth < 720.dp
        var mode by rememberSaveable { mutableStateOf(ViewMode.initial(maxWidth.value)) }
        var anchorKey by rememberSaveable {
            val today = LocalDate.now()
            mutableStateOf((if (mode == ViewMode.Week) today.monday() else today).toString())
        }
        val anchor = LocalDate.parse(anchorKey)
        fun shift(days: Long) { anchorKey = LocalDate.parse(anchorKey).plusDays(days).toString() }
        fun setMode(next: ViewMode) {
            if (next == ViewMode.Week && mode != ViewMode.Week) anchorKey = LocalDate.parse(anchorKey).monday().toString()
            mode = next
        }
        LaunchedEffect(maxWidth) {
            if (compact) mode = ViewMode.Day
            else if (mode == ViewMode.Day && maxWidth >= 1440.dp) setMode(ViewMode.Week)
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    val month = anchor.label("MMM yyyy")
                    TextAction(month, Modifier.height(44.dp), color = TextStrong, fontSize = 14,
                        label = "$month, choose a date", showFocusRing = true) { datePickerSelection = anchor }
                    datePickerSelection?.let { selection ->
                        Picker(onClose = { datePickerSelection = null }) {
                            DatePanel(selection) { date ->
                                anchorKey = (if (mode == ViewMode.Week) date.monday() else date).toString()
                                datePickerSelection = null
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextAction("", Modifier.size(24.dp, 36.dp), color = TextSecondary,
                        label = "Previous period", icon = IconName.ChevronLeft, showFocusRing = true) { shift(-mode.step) }
                    TextAction("Today", Modifier.size(44.dp, 32.dp), color = TextSecondary, showFocusRing = true) {
                        val today = LocalDate.now()
                        anchorKey = (if (mode == ViewMode.Week) today.monday() else today).toString()
                    }
                    TextAction("", Modifier.size(24.dp, 36.dp), color = TextSecondary,
                        label = "Next period", icon = IconName.ChevronRight, showFocusRing = true) { shift(mode.step) }
                }
                Spacer(Modifier.weight(1f))
                if (!compact) Row(Modifier.background(HoverColor, RoundedCornerShape(7.dp)).padding(2.dp)) {
                    listOf(ViewMode.ThreeDay, ViewMode.Week).forEach { option ->
                        val active = option == mode
                        TextAction(option.label, Modifier.size(52.dp, 26.dp)
                            .background(if (active) SurfaceColor else ClearColor, RoundedCornerShape(6.dp))
                            .semantics { selected = active },
                            color = if (active) TextStrong else Muted,
                            showFocusRing = true) { setMode(option) }
                    }
                }
                if (email != null && accountAction != null) Box {
                    val interaction = remember { MutableInteractionSource() }
                    val hovered by interaction.collectIsHoveredAsState()
                    val focused by interaction.collectIsFocusedAsState()
                    val keyboardFocus = focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard
                    val active = menu || hovered
                    val accountShape = RoundedCornerShape(50)
                    val menuOffset = with(LocalDensity.current) { 50.dp.roundToPx() }
                    Box(Modifier.size(44.dp).semantics { contentDescription = "Account" }
                        .clickable(interactionSource = interaction, indication = null,
                            enabled = !store.busy, role = Role.Button) { menu = true },
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.size(28.dp).background(HoverColor, accountShape)
                            .then(if (keyboardFocus) Modifier.border(2.dp, ControlFocus, accountShape) else Modifier),
                            contentAlignment = Alignment.Center) {
                            CalendarIcon(IconName.UserRound, 17, if (active) TextStrong else Muted, interaction)
                        }
                    }
                    if (menu) Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, menuOffset),
                        onDismissRequest = { menu = false }, properties = PopupProperties(focusable = true)) {
                        val shape = RoundedCornerShape(12.dp)
                        val menuMaxWidth = (this@BoxWithConstraints.maxWidth - 24.dp).coerceAtLeast(0.dp)
                        Column(Modifier.widthIn(min = minOf(200.dp, menuMaxWidth), max = menuMaxWidth)
                            .width(IntrinsicSize.Max)
                            .dropShadow(shape, Shadow(radius = 12.dp, color = EventShadowColor, offset = DpOffset(0.dp, 4.dp)))
                            .background(SurfaceColor, shape).padding(4.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CalendarIcon(IconName.Mail, 16, TextStrong)
                                Text(email, color = TextStrong, fontSize = 13.sp,
                                    fontWeight = FontWeight.Normal, modifier = Modifier.weight(1f))
                            }
                            if (openLocalCalendar != null) {
                                AccountMenuAction("Open local calendar", IconName.CalendarDays) {
                                    menu = false
                                    openLocalCalendar()
                                }
                            }
                            AccountMenuAction("Log out", IconName.LogOut) {
                                menu = false
                                scope.launch { accountAction() }
                            }
                        }
                    }
                }
            }
            val events = store.events
            if (events == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val error = store.error
                    Text(error ?: "Loading your calendar…", color = if (error == null) TextStrong else ErrorColor, fontSize = 13.sp)
                    if (error != null) TextAction("Try again") { scope.launch { store.load() } }
                }
            } else CalendarBoard(events, (0..<mode.count).map { anchor.plusDays(it.toLong()) }, now, store.dropRevision,
                open = { editorRevision++; store.error = null; editor = EditorDraft(it, true) },
                create = { date, minute ->
                        editorRevision++
                        store.error = null
                        val draft = CalendarEvent(date, allDay = minute == null)
                        editor = EditorDraft(if (minute == null) draft else draft.times(minute, minute + 45), false)
                }, move = { scope.launch { store.save(it, animated = false) } },
                change = { scope.launch { store.save(it) } }, shift = { shift(it.toLong()) })
        }
        val notice = store.notice
        val message = notice?.message ?: if (editor == null && store.events != null) store.error else null
        if (message != null) Row(Modifier.align(Alignment.BottomEnd).padding(20.dp)
            .dropShadow(RoundedCornerShape(8.dp), Shadow(radius = 12.dp, color = EventShadowColor,
                offset = DpOffset(0.dp, 8.dp)))
            .background(SurfaceColor, RoundedCornerShape(8.dp))
            .padding(start = 17.dp, end = 13.dp, top = 11.dp, bottom = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
                color = if (notice == UndoNotice.Deleted) TextStrong else ErrorColor,
                fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
            if (notice != null) {
                TextAction("Undo", Modifier.size(60.dp, 32.dp), enabled = !store.busy,
                    weight = FontWeight.SemiBold, fontSize = 13, showFocusRing = true) {
                    scope.launch { store.undo() }
                }
            }
            else TextAction("OK", weight = FontWeight.SemiBold) { store.error = null }
        }
        editor?.let { draft ->
            key(draft.event.id) {
                EventEditor(draft, compact = maxWidth <= 560.dp, cancel = {
                    if (editor != null) { editorRevision++; editor = null }
                }, save = { event ->
                    val revision = editorRevision
                    editor = null
                    scope.launch {
                        val saved = store.save(event)
                        if (!saved && editor == null && editorRevision == revision) editor = EditorDraft(event, draft.existing)
                    }
                    true
                },
                    delete = { store.delete(draft.event.id) }, error = { store.error })
            }
        }
    }
}

@Composable
private fun AccountMenuAction(text: String, icon: IconName, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(RoundedCornerShape(6.dp))
        .clickable(role = Role.Button, onClick = action).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CalendarIcon(icon, 16, TextStrong)
        Text(text, color = TextStrong, fontSize = 13.sp, fontWeight = FontWeight.Normal)
    }
}
