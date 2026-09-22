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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortsclipper.model.SilenceEdit
import com.shortsclipper.ui.theme.Accent
import com.shortsclipper.ui.theme.BgControl
import com.shortsclipper.ui.theme.TextPrimary
import com.shortsclipper.ui.theme.TextSecondary
import kotlin.math.max
import kotlin.math.min

private enum class DragMode { NONE, START_HANDLE, END_HANDLE, SCRUB }
private const val MIN_SELECTION_MS = 250L

/**
 * Current values read by the long-lived pointer-input coroutines. Keeping this
 * immutable snapshot in [rememberUpdatedState] lets selection updates redraw
 * the timeline without cancelling an in-progress handle drag.
 */
private data class TimelinePointerState(
    val durationMs: Long,
    val selectionStartMs: Long,
    val selectionEndMs: Long,
    val widthPx: Float,
    val minimumSelectionMs: Long,
    val onSelection: (Long, Long) -> Unit,
    val onSeek: (Long) -> Unit,
) {
    private val msPerPx: Float get() = durationMs.toFloat() / widthPx.coerceAtLeast(1f)

    fun xToMs(x: Float): Long = (x * msPerPx).toLong().coerceIn(0L, durationMs)
    fun msToX(ms: Long): Float = ms / msPerPx
}

fun formatTime(ms: Long, withFraction: Boolean = false): String {
    val safeMs = ms.coerceAtLeast(0L)
    val totalSeconds = safeMs / 1000.0
    val minutes = (totalSeconds / 60).toInt()
    val seconds = (totalSeconds % 60).toInt()
    return if (withFraction) {
        val tenths = (((totalSeconds % 60) - seconds) * 10).toInt().coerceIn(0, 9)
        String.format("%d:%02d.%d", minutes, seconds, tenths)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

/**
 * Timeline state is wholly composition-scoped: no global drawing/removal
 * overlays survive recomposition or leak into another editor instance.
 */
@Composable
fun TimelineView(
    durationMs: Long,
    selectionStartMs: Long,
    selectionEndMs: Long,
    playheadMs: Long,
    envelope: List<Float>,
    /** Source timestamp represented by envelope index zero. */
    envelopeStartMs: Long,
    envelopeStepMs: Int,
    zoom: Float,
    removals: List<SilenceEdit>,
    onSelection: (Long, Long) -> Unit,
    onSeek: (Long) -> Unit,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (durationMs <= 0L) return

    val scrollState = rememberScrollState()
    var dragMode by remember { mutableStateOf(DragMode.NONE) }
    val safeStart = selectionStartMs.coerceIn(0L, durationMs)
    val safeEnd = selectionEndMs.coerceIn(safeStart, durationMs)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(
                formatTime(safeStart, withFraction = true),
                color = Accent,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${formatTime(safeEnd - safeStart)} clip",
                color = TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatTime(safeEnd, withFraction = true),
                color = Accent,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(BgControl)
                .horizontalScroll(scrollState),
        ) {
            val density = LocalDensity.current
            val totalWidth = maxWidth * zoom.coerceAtLeast(1f)
            val widthPx = with(density) { totalWidth.toPx() }.coerceAtLeast(1f)
            val msPerPx = durationMs.toFloat() / widthPx
            val effectiveMinSelection = min(MIN_SELECTION_MS, durationMs)
            val latestPointerState = rememberUpdatedState(
                TimelinePointerState(
                    durationMs = durationMs,
                    selectionStartMs = safeStart,
                    selectionEndMs = safeEnd,
                    widthPx = widthPx,
                    minimumSelectionMs = effectiveMinSelection,
                    onSelection = onSelection,
                    onSeek = onSeek,
                ),
            )

            fun msToX(ms: Long): Float = ms / msPerPx

            Canvas(
                modifier = Modifier
                    .width(totalWidth)
                    .height(84.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val current = latestPointerState.value
                            current.onSeek(current.xToMs(offset.x))
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val current = latestPointerState.value
                                val startPx = current.msToX(current.selectionStartMs)
                                val endPx = current.msToX(current.selectionEndMs)
                                dragMode = when {
                                    kotlin.math.abs(offset.x - startPx) < 48f -> DragMode.START_HANDLE
                                    kotlin.math.abs(offset.x - endPx) < 48f -> DragMode.END_HANDLE
                                    else -> DragMode.SCRUB
                                }
                                if (dragMode == DragMode.SCRUB) current.onSeek(current.xToMs(offset.x))
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val current = latestPointerState.value
                                val positionMs = current.xToMs(change.position.x)
                                when (dragMode) {
                                    DragMode.START_HANDLE -> {
                                        val maxStart = (current.selectionEndMs - current.minimumSelectionMs).coerceAtLeast(0L)
                                        current.onSelection(positionMs.coerceIn(0L, maxStart), current.selectionEndMs)
                                    }
                                    DragMode.END_HANDLE -> {
                                        val minEnd = (current.selectionStartMs + current.minimumSelectionMs)
                                            .coerceAtMost(current.durationMs)
                                        current.onSelection(current.selectionStartMs, positionMs.coerceIn(minEnd, current.durationMs))
                                    }
                                    DragMode.SCRUB -> current.onSeek(positionMs)
                                    DragMode.NONE -> Unit
                                }
                            },
                            onDragEnd = { dragMode = DragMode.NONE },
                            onDragCancel = { dragMode = DragMode.NONE },
                        )
                    },
            ) {
                drawWaveform(envelope, envelopeStartMs, envelopeStepMs, durationMs, safeStart, safeEnd)

                // These are the same edits provided to export; no hidden
                // global overlay state can become stale after undo/recompose.
                removals.forEach { removal ->
                    val start = removal.startMs.coerceIn(0L, durationMs)
                    val end = removal.endMs.coerceIn(start, durationMs)
                    if (end > start) {
                        drawRect(
                            color = Color(0xFFEF4444).copy(alpha = 0.25f),
                            topLeft = Offset(msToX(start), 0f),
                            size = Size((msToX(end) - msToX(start)).coerceAtLeast(2f), size.height),
                        )
                    }
                }

                val startX = msToX(safeStart)
                val endX = msToX(safeEnd)
                drawRect(
                    color = Accent.copy(alpha = 0.12f),
                    topLeft = Offset(startX, 0f),
                    size = Size((endX - startX).coerceAtLeast(2f), size.height),
                )
                val handleWidth = 8f
                drawRoundRect(
                    color = Accent,
                    topLeft = Offset(startX - handleWidth / 2f, 0f),
                    size = Size(handleWidth, size.height),
                    cornerRadius = CornerRadius(4f, 4f),
                )
                drawRoundRect(
                    color = Accent,
                    topLeft = Offset(endX - handleWidth / 2f, 0f),
                    size = Size(handleWidth, size.height),
                    cornerRadius = CornerRadius(4f, 4f),
                )

                val playheadX = msToX(playheadMs.coerceIn(0L, durationMs))
                drawLine(TextPrimary, Offset(playheadX, 0f), Offset(playheadX, size.height), strokeWidth = 3f)
                drawCircle(TextPrimary, radius = 7f, center = Offset(playheadX, 8f))
                drawCircle(BgControl, radius = 3.5f, center = Offset(playheadX, 8f))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("Zoom", color = TextSecondary, fontSize = 11.sp)
            Slider(
                value = zoom.coerceIn(1f, 16f),
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWaveform(
    envelope: List<Float>,
    envelopeStartMs: Long,
    envelopeStepMs: Int,
    durationMs: Long,
    selectionStartMs: Long,
    selectionEndMs: Long,
) {
    val mid = size.height / 2f
    var maxRms = 0f
    for (sample in envelope) {
        if (sample.isFinite()) maxRms = max(maxRms, sample.coerceAtLeast(0f))
    }
    if (envelope.isEmpty() || maxRms <= 0f) {
        drawRoundRect(
            color = Color(0xFF55555C),
            topLeft = Offset(0f, mid - 2f),
            size = Size(size.width, 4f),
            cornerRadius = CornerRadius(2f, 2f),
        )
        return
    }
    val barCount = (size.width / 6f).toInt().coerceAtLeast(1)
    val stepMs = durationMs.toFloat() / barCount
    val safeEnvelopeStepMs = envelopeStepMs.coerceAtLeast(1).toLong()
    val envelopeEndMs = envelopeStartMs + envelope.size.toLong() * safeEnvelopeStepMs
    for (bar in 0 until barCount) {
        val fromMs = bar * stepMs
        val toMs = (bar + 1) * stepMs
        var peak = 0f
        if (toMs > envelopeStartMs && fromMs < envelopeEndMs) {
            val fromIndex = ((fromMs - envelopeStartMs).coerceAtLeast(0f) / safeEnvelopeStepMs)
                .toInt().coerceIn(0, envelope.lastIndex)
            val exclusiveEnd = (((toMs - envelopeStartMs).coerceAtLeast(0f) / safeEnvelopeStepMs).toInt() + 1)
                .coerceIn(fromIndex + 1, envelope.size)
            for (index in fromIndex until exclusiveEnd) {
                val sample = envelope[index]
                if (sample.isFinite()) peak = max(peak, sample.coerceAtLeast(0f))
            }
        }
        val height = (peak / maxRms) * (size.height * 0.82f)
        val isSelected = fromMs >= selectionStartMs && fromMs <= selectionEndMs
        drawRoundRect(
            color = if (isSelected) Accent.copy(alpha = 0.85f) else Color(0xFF55555C),
            topLeft = Offset(bar * 6f + 1f, mid - height / 2f),
            size = Size(4f, height.coerceAtLeast(2f)),
            cornerRadius = CornerRadius(2f, 2f),
        )
    }
}
