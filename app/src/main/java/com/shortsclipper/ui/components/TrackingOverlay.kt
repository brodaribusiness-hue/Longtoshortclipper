package com.shortsclipper.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.shortsclipper.model.FaceBox
import com.shortsclipper.ui.theme.Accent
import com.shortsclipper.ui.theme.TextPrimary

/**
 * Draws detected face boxes over the preview; tapping a box selects the
 * tracking target.
 */
@Composable
fun TrackingOverlay(
    boxes: List<FaceBox>,
    selectedId: Int?,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit = {},
) {
    Canvas(
        modifier = modifier.pointerInput(boxes) {
            detectTapGestures { position ->
                for (box in boxes) {
                    val x = box.xFrac * size.width
                    val y = box.yFrac * size.height
                    val w = box.wFrac * size.width
                    val h = box.hFrac * size.height
                    val pad = 24f
                    if (position.x >= x - pad && position.x <= x + w + pad &&
                        position.y >= y - pad && position.y <= y + h + pad
                    ) {
                        onSelect(box.faceId)
                        return@detectTapGestures
                    }
                }
            }
        },
    ) {
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
