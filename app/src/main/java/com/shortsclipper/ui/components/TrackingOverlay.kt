package com.shortsclipper.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.shortsclipper.model.FaceBox
import com.shortsclipper.ui.theme.Accent
import com.shortsclipper.ui.theme.TextPrimary

/** Draws source-coordinate face boxes and supports face selection/reframing. */
@Composable
fun TrackingOverlay(
    boxes: List<FaceBox>,
    selectedId: Int?,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit = {},
    onDrag: ((Offset) -> Unit)? = null,
) {
    // Face boxes and callbacks change as playback advances or a drag creates a
    // keyframe. Keep the gesture coroutines alive and read the latest values so
    // Compose recomposition cannot truncate a selection or reframe drag.
    val latestBoxes = rememberUpdatedState(boxes)
    val latestOnSelect = rememberUpdatedState(onSelect)
    val latestOnDrag = rememberUpdatedState(onDrag)
    val interactionModifier = modifier
        .pointerInput(Unit) {
            detectTapGestures { position ->
                for (box in latestBoxes.value) {
                    val x = box.xFrac * size.width
                    val y = box.yFrac * size.height
                    val width = box.wFrac * size.width
                    val height = box.hFrac * size.height
                    val padding = 24f
                    if (position.x >= x - padding && position.x <= x + width + padding &&
                        position.y >= y - padding && position.y <= y + height + padding
                    ) {
                        latestOnSelect.value(box.faceId)
                        return@detectTapGestures
                    }
                }
            }
        }
        .then(
            if (onDrag == null) Modifier else Modifier.pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    latestOnDrag.value?.invoke(amount)
                }
            },
        )

    Canvas(modifier = interactionModifier) {
        for (box in boxes) {
            val selected = box.faceId == selectedId
            val topLeft = Offset(box.xFrac * size.width, box.yFrac * size.height)
            val boxSize = Size(box.wFrac * size.width, box.hFrac * size.height)
            drawRect(
                color = if (selected) Accent else TextPrimary.copy(alpha = 0.8f),
                topLeft = topLeft,
                size = boxSize,
                style = Stroke(width = if (selected) 6f else 3f),
            )
            if (selected) {
                drawRect(
                    color = Accent.copy(alpha = 0.15f),
                    topLeft = topLeft,
                    size = boxSize,
                )
            }
        }
    }
}
