package dev.calendar.mobile

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

internal enum class IconName { CalendarDays, Check, ChevronDown, ChevronLeft, ChevronRight, LogOut, Mail, Plus, Square, UserRound }

private class IconCatalog(context: Context) {
    data class Layer(val paths: List<Path>, val frames: List<FloatArray>)

    private val data = JSONObject(context.assets.open("calendar-icons.json").bufferedReader().use { it.readText() })
    private val icons = data.getJSONObject("icons")
    val frameRate = data.getInt("frameRate")

    private val parsed = mutableMapOf<IconName, List<Layer>>()

    fun layers(name: IconName): List<Layer> = parsed.getOrPut(name) {
        val layers = icons.getJSONArray(name.name)
        List(layers.length()) { index ->
            val layer = layers.getJSONObject(index)
            val paths = layer.getJSONArray("paths")
            val frames = layer.getJSONArray("frames")
            Layer(List(paths.length()) { pathIndex ->
                val commands = paths.getJSONArray(pathIndex)
                Path().apply {
                    repeat(commands.length()) {
                        val command = commands.getJSONArray(it)
                        fun value(i: Int) = command.getDouble(i).toFloat()
                        when (command.length()) {
                            0 -> close()
                            3 -> if (value(2) == 0f) moveTo(value(0), value(1)) else lineTo(value(0), value(1))
                            6 -> cubicTo(value(0), value(1), value(2), value(3), value(4), value(5))
                        }
                    }
                }
            }, List(frames.length()) { frame ->
                val values = frames.getJSONArray(frame)
                FloatArray(6) { values.getDouble(it).toFloat() }
            })
        }
    }

    companion object {
        private var shared: IconCatalog? = null

        fun get(context: Context): IconCatalog = shared ?: IconCatalog(context.applicationContext).also { shared = it }
    }
}

@Composable
internal fun CalendarIcon(name: IconName, iconSize: Int, color: Color, interactionSource: MutableInteractionSource? = null) {
    val context = LocalContext.current
    val catalog = remember(context) { IconCatalog.get(context) }
    val layers = remember(catalog, name) { catalog.layers(name) }
    val hover = remember { MutableInteractionSource() }
    val source = interactionSource ?: hover
    val animation = remember { Animatable(0f) }
    val end = layers.maxOf { it.frames.lastIndex }
    LaunchedEffect(source, end) {
        if (end < 1) return@LaunchedEffect
        coroutineScope {
            var playback: Job? = null
            source.interactions.collect { event ->
                when (event) {
                    is HoverInteraction.Enter, is FocusInteraction.Focus, is PressInteraction.Press -> {
                        playback?.cancel()
                        playback = launch {
                            animation.snapTo(0f)
                            animation.animateTo(end.toFloat(), tween(end * 1000 / catalog.frameRate, easing = LinearEasing))
                        }
                    }
                    is HoverInteraction.Exit, is FocusInteraction.Unfocus, is PressInteraction.Cancel -> {
                        playback?.cancel()
                        animation.snapTo(0f)
                    }
                }
            }
        }
    }
    Canvas(Modifier.size(iconSize.dp).then(if (interactionSource == null) Modifier.hoverable(hover) else Modifier)) {
        val factor = size.width / 24
        val frame = animation.value.toInt()
        val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        withTransform({ scale(factor, factor, Offset.Zero) }) {
            for ((paths, frames) in layers) {
                val values = frames.getOrNull(frame.coerceAtMost(frames.lastIndex))
                if (values == null) {
                    for (path in paths) drawPath(path, color, style = stroke)
                } else {
                    val (a, b, c, d) = values
                    val matrix = Matrix()
                    matrix[0, 0] = a
                    matrix[0, 1] = b
                    matrix[1, 0] = c
                    matrix[1, 1] = d
                    matrix[3, 0] = values[4]
                    matrix[3, 1] = values[5]
                    withTransform({ transform(matrix) }) {
                        for (path in paths) drawPath(path, color, style = stroke)
                    }
                }
            }
        }
    }
}
