package com.shortsclipper.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tiny hand-drawn transport icons (play/pause/undo/redo) so the app does not
 * need the heavy material-icons-extended dependency.
 */

@Composable
fun PlayIcon(size: Dp = 24.dp, tint: Color = Color.White) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.28f, h * 0.18f)
            lineTo(w * 0.82f, h * 0.5f)
            lineTo(w * 0.28f, h * 0.82f)
            close()
        }
        drawPath(path, tint)
    }
}

@Composable
fun PauseIcon(size: Dp = 24.dp, tint: Color = Color.White) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawRoundRect(
            tint,
            topLeft = Offset(w * 0.24f, h * 0.18f),
            size = androidx.compose.ui.geometry.Size(w * 0.17f, h * 0.64f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
        )
        drawRoundRect(
            tint,
            topLeft = Offset(w * 0.59f, h * 0.18f),
            size = androidx.compose.ui.geometry.Size(w * 0.17f, h * 0.64f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
        )
    }
}

@Composable
fun UndoIcon(size: Dp = 24.dp, tint: Color = Color.White, enabled: Boolean = true) {
    val color = if (enabled) tint else tint.copy(alpha = 0.35f)
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(w * 0.75f, h * 0.72f)
            cubicTo(w * 0.9f, h * 0.45f, w * 0.72f, h * 0.22f, w * 0.45f, h * 0.24f)
            lineTo(w * 0.2f, h * 0.45f)
        }
        drawPath(path, color, style = stroke)
        // arrow head
        val head = Path().apply {
            moveTo(w * 0.14f, h * 0.32f)
            lineTo(w * 0.2f, h * 0.45f)
            lineTo(w * 0.33f, h * 0.42f)
        }
        drawPath(head, color, style = stroke)
    }
}

@Composable
fun RedoIcon(size: Dp = 24.dp, tint: Color = Color.White, enabled: Boolean = true) {
    val color = if (enabled) tint else tint.copy(alpha = 0.35f)
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(w * 0.25f, h * 0.72f)
            cubicTo(w * 0.1f, h * 0.45f, w * 0.28f, h * 0.22f, w * 0.55f, h * 0.24f)
            lineTo(w * 0.8f, h * 0.45f)
        }
        drawPath(path, color, style = stroke)
        val head = Path().apply {
            moveTo(w * 0.86f, h * 0.32f)
            lineTo(w * 0.8f, h * 0.45f)
            lineTo(w * 0.67f, h * 0.42f)
        }
        drawPath(head, color, style = stroke)
    }
}

@Composable
fun CutIcon(size: Dp = 24.dp, tint: Color = Color.White) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.3f, h * 0.15f), Offset(w * 0.62f, h * 0.62f), 2.dp.toPx(), StrokeCap.Round)
        drawLine(tint, Offset(w * 0.7f, h * 0.15f), Offset(w * 0.38f, h * 0.62f), 2.dp.toPx(), StrokeCap.Round)
        drawCircle(tint, radius = w * 0.12f, center = Offset(w * 0.32f, h * 0.75f), style = stroke)
        drawCircle(tint, radius = w * 0.12f, center = Offset(w * 0.68f, h * 0.75f), style = stroke)
    }
}
