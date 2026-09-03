package com.yourapp.clipper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.min

data class CropFrame(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right get() = left + width
    val bottom get() = top + height
    val centerX get() = left + width / 2f
    val centerY get() = top + height / 2f
}

@Composable
fun CropFrameOverlay(
    containerWidth: Float,
    containerHeight: Float,
    aspectRatio: Float,
    frame: CropFrame,
    onFrameChanged: (CropFrame) -> Unit,
    onUserTouch: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val frameColor = Color(0xFF00E676)

    fun clampFrame(f: CropFrame): CropFrame {
        val w = min(f.width, containerWidth)
        val h = min(f.height, containerHeight)
        val l = f.left.coerceIn(0f, containerWidth - w)
        val t = f.top.coerceIn(0f, containerHeight - h)
        return CropFrame(l, t, w, h)
    }

    fun handleRadius() = 22f

    fun cornerAt(pos: Offset): String? {
        val r = handleRadius()
        val corners = listOf(
            "tl" to Offset(frame.left, frame.top),
            "tr" to Offset(frame.right, frame.top),
            "bl" to Offset(frame.left, frame.bottom),
            "br" to Offset(frame.right, frame.bottom)
        )
        corners.forEach { (name, c) ->
            if (abs(pos.x - c.x) < r && abs(pos.y - c.y) < r) return name
        }
        return null
    }

    fun resize(corner: String, drag: Offset) {
        val minSize = 60f
        var l = frame.left; var t = frame.top
        var r = frame.right; var b = frame.bottom

        when (corner) {
            "tl" -> { l += drag.x; t += drag.y }
            "tr" -> { r += drag.x; t += drag.y }
            "bl" -> { l += drag.x; b += drag.y }
            "br" -> { r += drag.x; b += drag.y }
        }

        val newW = r - l
        val newH = b - t
        val targetW = maxOf(minSize, maxOf(newW, newH * aspectRatio))
        val targetH = targetW / aspectRatio

        val fl = when (corner) {
            "tl", "bl" -> r - targetW
            else -> l
        }
        val ft = when (corner) {
            "tl", "tr" -> b - targetH
            else -> t
        }
        val nf = CropFrame(fl, ft, targetW, targetH)
        if (nf.width <= containerWidth && nf.height <= containerHeight) {
            onFrameChanged(clampFrame(nf))
        }
    }

    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(frame, containerWidth, containerHeight) {
                    detectDragGestures(
                        onDragStart = { onUserTouch?.invoke() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val pos = change.position
                            val corner = cornerAt(pos) ?: cornerAt(pos - dragAmount)
                            when (corner) {
                                null -> {
                                    onFrameChanged(
                                        clampFrame(
                                            frame.copy(
                                                left = frame.left + dragAmount.x,
                                                top = frame.top + dragAmount.y
                                            )
                                        )
                                    )
                                }
                                else -> resize(corner, dragAmount)
                            }
                        }
                    )
                }
        ) {
            val dim = Color.Black.copy(alpha = 0.55f)
            drawRect(dim, Offset(0f, 0f), Size(size.width, frame.top))
            drawRect(dim, Offset(0f, frame.bottom), Size(size.width, size.height - frame.bottom))
            drawRect(dim, Offset(0f, frame.top), Size(frame.left, frame.height))
            drawRect(dim, Offset(frame.right, frame.top), Size(size.width - frame.right, frame.height))

            drawRect(
                frameColor,
                Offset(frame.left, frame.top),
                Size(frame.width, frame.height),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
            )
            for (i in 1..2) {
                drawLine(
                    Color.White.copy(alpha = 0.4f),
                    Offset(frame.left + frame.width * i / 3f, frame.top),
                    Offset(frame.left + frame.width * i / 3f, frame.bottom),
                    strokeWidth = 1f
                )
                drawLine(
                    Color.White.copy(alpha = 0.4f),
                    Offset(frame.left, frame.top + frame.height * i / 3f),
                    Offset(frame.right, frame.top + frame.height * i / 3f),
                    strokeWidth = 1f
                )
            }
            val r = handleRadius()
            listOf(
                Offset(frame.left, frame.top),
                Offset(frame.right, frame.top),
                Offset(frame.left, frame.bottom),
                Offset(frame.right, frame.bottom)
            ).forEach { c ->
                drawCircle(frameColor, radius = r * 0.45f, center = c,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
            }
        }
    }
}
