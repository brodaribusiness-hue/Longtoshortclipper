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
import com.shortsclipper.video.CropRect

/**
 * Draws detected face boxes over the 9:16 preview; tapping a box selects the
 * tracking target. Normalized coordinates are mapped through the active CropRect.
 */
@Composable
fun TrackingOverlay(
    boxes: List<FaceBox>,
    selectedId: Int?,
    cropRect: CropRect,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit = {},
) {
    Canvas(
        modifier = modifier.pointerInput(boxes, cropRect) {
            detectTapGestures { position ->
                val leftFrac = cropRect.centerX - cropRect.widthFrac / 2f
                val topFrac = cropRect.centerY - cropRect.heightFrac / 2f
                val pad = 24f
                for (box in boxes) {
                    val x = (box.xFrac - leftFrac) / cropRect.widthFrac * size.width
                    val y = (box.yFrac - topFrac) / cropRect.heightFrac * size.height
                    val w = (box.wFrac / cropRect.widthFrac) * size.width
                    val h = (box.hFrac / cropRect.heightFrac) * size.height
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
        val leftFrac = cropRect.centerX - cropRect.widthFrac / 2f
        val topFrac = cropRect.centerY - cropRect.heightFrac / 2f
        for (box in boxes) {
            val selected = box.faceId == selectedId
            val x = (box.xFrac - leftFrac) / cropRect.widthFrac * size.width
            val y = (box.yFrac - topFrac) / cropRect.heightFrac * size.height
            val w = (box.wFrac / cropRect.widthFrac) * size.width
            val h = (box.hFrac / cropRect.heightFrac) * size.height
            val topLeft = Offset(x, y)
            val boxSize = Size(w, h)
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
