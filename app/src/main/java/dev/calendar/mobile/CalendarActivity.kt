package dev.calendar.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalTextApi::class)
private val calendarFont = FontFamily(
    listOf(
        FontWeight.Normal to 400,
        FontWeight.Medium to 550,
        FontWeight.SemiBold to 690,
        FontWeight.Bold to 700,
    ).map { (weight, axisWeight) ->
        Font(R.font.mulish, weight = weight, variationSettings = FontVariation.Settings(FontVariation.weight(axisWeight)))
    },
)
private val calendarTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = calendarFont),
        displayMedium = displayMedium.copy(fontFamily = calendarFont),
        displaySmall = displaySmall.copy(fontFamily = calendarFont),
        headlineLarge = headlineLarge.copy(fontFamily = calendarFont),
        headlineMedium = headlineMedium.copy(fontFamily = calendarFont),
        headlineSmall = headlineSmall.copy(fontFamily = calendarFont),
        titleLarge = titleLarge.copy(fontFamily = calendarFont),
        titleMedium = titleMedium.copy(fontFamily = calendarFont),
        titleSmall = titleSmall.copy(fontFamily = calendarFont),
        bodyLarge = bodyLarge.copy(fontFamily = calendarFont, fontSize = 13.sp),
        bodyMedium = bodyMedium.copy(fontFamily = calendarFont),
        bodySmall = bodySmall.copy(fontFamily = calendarFont),
        labelLarge = labelLarge.copy(fontFamily = calendarFont),
        labelMedium = labelMedium.copy(fontFamily = calendarFont),
        labelSmall = labelSmall.copy(fontFamily = calendarFont),
    )
}

class CalendarActivity : ComponentActivity() {
    private val account by lazy { CalendarAccount(this, lifecycleScope) }
    private val localEvents by lazy { LocalCalendarEventService(File(filesDir, "events.json")) }
    private val hasLocalCalendar by lazy { localEvents.exists }
    private val localStore by lazy { EventStore(lifecycleScope, localEvents) }
    private var openDatePicker by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDatePicker = savedInstanceState?.getBoolean(ACTION_PICK_DATE) ?: (intent.action == ACTION_PICK_DATE)
        val systemBarColor = SurfaceColor.toArgb()
        enableEdgeToEdge(SystemBarStyle.light(systemBarColor, systemBarColor),
            SystemBarStyle.light(systemBarColor, systemBarColor))
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(primary = ControlFocus, background = SurfaceColor, surface = SurfaceColor,
                    onBackground = TextStrong, onSurface = TextStrong, error = ErrorColor),
                typography = calendarTypography,
            ) {
                Box(Modifier.fillMaxSize().background(SurfaceColor).safeDrawingPadding()) {
                    ConnectedCalendar()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_PICK_DATE) openDatePicker = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(ACTION_PICK_DATE, openDatePicker)
    }

    @Composable
    private fun ConnectedCalendar() {
        val shared = account.store
        var localOpen by rememberSaveable { mutableStateOf(false) }
        val openLocalCalendar: (() -> Unit)? = if (hasLocalCalendar) ({ localOpen = true }) else null
        LaunchedEffect(Unit) { account.restore() }
        if (localOpen && hasLocalCalendar) {
            BackHandler { localOpen = false }
            Column(Modifier.fillMaxSize()) {
                TextButton(onClick = { localOpen = false }, modifier = Modifier.align(Alignment.End)) {
                    Text("Close local calendar")
                }
                CalendarHome(localStore, openDatePicker = openDatePicker, onDatePickerOpened = { openDatePicker = false })
            }
        } else if (shared != null) {
            key(shared) {
                CalendarHome(shared, email = account.email, accountAction = { account.signOut() },
                    openLocalCalendar = openLocalCalendar, openDatePicker = openDatePicker,
                    onDatePickerOpened = { openDatePicker = false })
                LaunchedEffect(shared) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { shared.synchronize() }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                Text("Sign in to Calendar", style = MaterialTheme.typography.headlineSmall,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (account.busy) CircularProgressIndicator()
                TextButton(onClick = { lifecycleScope.launch { account.signIn() } }, enabled = !account.busy) {
                    Text("Continue with Google")
                }
                if (openLocalCalendar != null) {
                    TextButton(onClick = openLocalCalendar) { Text("Open local calendar") }
                }
                account.error?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    if (error.startsWith("Couldn’t check")) TextButton(
                        onClick = { lifecycleScope.launch { account.restore() } }, enabled = !account.busy,
                    ) { Text("Retry session") }
                }
            }
        }
    }
}

val SurfaceColor = Color.White
val OnSolidColor = SurfaceColor
val ClearColor = Color.Transparent
val ModalOverlayColor = Color(0x4d303640)
val PlaceholderColor = Color(0xffa0a5ae)
val OutsideMonthColor = Color(0xffaeb3bb)
val HourLabelColor = Color(0xff5f636e)
val ErrorColor = Color(0xffa83926)
val TextStrong = Color(0xff303744)
val TextSecondary = Color(0xff626b7b)
val Muted = Color(0xff737780)
val Line = Color(0xfff4f4f5)
val HoverColor = Color(0xfff5f5f5)
val Accent = Color(0xffff6b50)
val ControlFocus = TextStrong
val EventShadowColor = Color(0x203b3429)
fun EventCalendar.color() = Color(0xff000000 or rgb.toLong())
