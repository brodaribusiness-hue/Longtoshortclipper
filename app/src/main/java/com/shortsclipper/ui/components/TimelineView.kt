package com.shortsclipper.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortsclipper.ui.theme.Accent
import com.shortsclipper.ui.theme.BgControl
import com.shortsclipper.ui.theme.TextPrimary
import com.shortsclipper.ui.theme.TextSecondary
import kotlin.math.max
import kotlin.math.min

private enum class DragMode { NONE, START_HANDLE, END_HANDLE, SCRUB }

fun formatTime(ms: Long, withFraction: Boolean = false): String {
    val totalSeconds = ms / 1000.0
    val m = (totalSeconds / 60).toInt()
    val s = (totalSeconds % 60).toInt()
    return if (withFraction) {
        val frac = ((totalSeconds % 60) - s)
        String.format("%d:%02d.%d", m, s, (frac * 10).toInt())
    } else {
        String.format("%d:%02d", m, s)
    }
}

/**
 * Lightweight professional timeline: waveform, selected region with draggable
 * start/end handles, draggable playhead and pinch-free zoom (slider).
 */
@Composable
fun TimelineView(
    durationMs: Long,
    selectionStartMs: Long,
    selectionEndMs: Long,
    playheadMs: Long,
    envelope: List<Float>,
    zoom: Float,
    removals: List<com.shortsclipper.model.SilenceEdit>,
    onSelection: (Long, Long) -> Unit,
    onSeek: (Long) -> Unit,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (durationMs <= 0) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(
                formatTime(selectionStartMs, withFraction = true),
                color = Accent,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${formatTime(selectionEndMs - selectionStartMs)} clip",
                color = TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatTime(selectionEndMs, withFraction = true),
                color = Accent,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        var dragMode by remember { mutableIntStateOf(DragMode.NONE.ordinal) }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(BgControl)
                .horizontalScroll(rememberScrollState()),
        ) {
            val density = LocalDensity.current
            val totalWidth = maxWidth * zoom
            val widthPx = with(density) { totalWidth.toPx() }
            val msPerPx = durationMs / widthPx

            fun xToMs(x: Float): Long = (x * msPerPx).toLong().coerceIn(0L, durationMs)

            Canvas(
                modifier = Modifier
                    .width(totalWidth)
                    .height(84.dp)
                    .pointerInput(durationMs, selectionStartMs, selectionEndMs, zoom) {
                        detectTapGestures { offset -> onSeek(xToMs(offset.x)) }
                    }
                    .pointerInput(durationMs, selectionStartMs, selectionEndMs, zoom) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val startPx = selectionStartMs / msPerPx
                                val endPx = selectionEndMs / msPerPx
                                val playPx = playheadMs / msPerPx
                                dragMode = when {
                                    kotlin.math.abs(offset.x - startPx) < 48f -> DragMode.START_HANDLE.ordinal
                                    kotlin.math.abs(offset.x - endPx) < 48f -> DragMode.END_HANDLE.ordinal
                                    else -> DragMode.SCRUB.ordinal
                                }
                                if (dragMode == DragMode.SCRUB.ordinal && kotlin.math.abs(offset.x - playPx) > 48f) {
                                    onSeek(xToMs(offset.x))
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val ms = xToMs(change.position.x)
                                when (DragMode.entries[dragMode]) {
                                    DragMode.START_HANDLE -> onSelection(min(ms, selectionEndMs - 1000), selectionEndMs)
                                    DragMode.END_HANDLE -> onSelection(selectionStartMs, max(ms, selectionStartMs + 1000))
                                    DragMode.SCRUB -> onSeek(ms)
                                    else -> {}
                                }
                            },
                            onDragEnd = { dragMode = DragMode.NONE.ordinal },
                        )
                    },
            ) {
                // Waveform (from the decoded audio envelope) or a flat track.
                val mid = size.height / 2f
                val maxRms = envelope.maxOrNull() ?: 0f
                if (envelope.isNotEmpty() && maxRms > 0f) {
                    val barCount = (size.width / 6f).toInt().coerceAtLeast(1)
                    val stepMs = durationMs / barCount
                    for (b in 0 until barCount) {
                        val fromMs = b * stepMs
                        val toMs = fromMs + stepMs
                        val fromIdx = (fromMs / 100).toInt().coerceIn(0, envelope.size - 1)
                        val toIdx = (toMs / 100).toInt().coerceIn(fromIdx + 1, envelope.size)
                        var peak = 0f
                        for (i in fromIdx until toIdx) peak = max(peak, envelope[i])
                        val h = (peak / maxRms) * (size.height * 0.82f)
                        val inSelection = fromMs >= selectionStartMs && fromMs <= selectionEndMs
                        drawRoundRect(
                            color = if (inSelection) Accent.copy(alpha = 0.85f) else Color(0xFF55555C),
                            topLeft = Offset(b * 6f + 1f, mid - h / 2f),
                            size = Size(4f, h.coerceAtLeast(2f)),
                            cornerRadius = CornerRadius(2f, 2f),
                        )
                    }
                } else {
                    drawRoundRect(
                        color = Color(0xFF55555C),
                        topLeft = Offset(0f, mid - 2f),
                        size = Size(size.width, 4f),
                        cornerRadius = CornerRadius(2f, 2f),
                    )
                }

                // Silence removals overlay.
                val activeRemovals = currentRemovals
                for (r in activeRemovals) {
                    drawRect(
                        color = Color(0xFFEF4444).copy(alpha = 0.25f),
                        topLeft = Offset((r.startMs / msPerPx), 0f),
                        size = Size(((r.endMs - r.startMs) / msPerPx).coerceAtLeast(2f), size.height),
                    )
                }

                // Selected region highlight + handles.
                val sx = selectionStartMs / msPerPx
                val ex = selectionEndMs / msPerPx
                drawRect(
                    color = Accent.copy(alpha = 0.12f),
                    topLeft = Offset(sx, 0f),
                    size = Size((ex - sx).coerceAtLeast(2f), size.height),
                )
                val handleW = 8f
                drawRoundRect(
                    Accent,
                    topLeft = Offset(sx - handleW / 2, 0f),
                    size = Size(handleW, size.height),
                    cornerRadius = CornerRadius(4f, 4f),
                )
                drawRoundRect(
                    Accent,
                    topLeft = Offset(ex - handleW / 2, 0f),
                    size = Size(handleW, size.height),
                    cornerRadius = CornerRadius(4f, 4f),
                )

                // Playhead.
                val px = playheadMs / msPerPx
                drawLine(
                    TextPrimary,
                    Offset(px, 0f),
                    Offset(px, size.height),
                    strokeWidth = 3f,
                )
                drawCircle(TextPrimary, radius = 7f, center = Offset(px, 8f))
                drawCircle(BgControl, radius = 3.5f, center = Offset(px, 8f))
            }

            // Second canvas just for removals capture (kept in a var used above).
            currentRemovals = removalsLocal
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("Zoom", color = TextSecondary, fontSize = 11.sp)
            Slider(
                value = zoom,
                onValueChange = onZoom,
                valueRange = 1f..16f,
                modifier = Modifier.weight(1f).height(28.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = BgControl,
                ),
            )
        }
    }
}

// Timeline needs the applied removals for the red overlay; set by EditorScreen
// right before composing. Kept as a small composition-local style holder.
private var currentRemovals: List<com.shortsclipper.model.SilenceEdit> = emptyList()
private var removalsLocal: List<com.shortsclipper.model.SilenceEdit> = emptyList()
