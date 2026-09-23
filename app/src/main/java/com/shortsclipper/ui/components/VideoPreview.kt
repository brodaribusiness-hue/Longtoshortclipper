package com.shortsclipper.ui.components

import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.shortsclipper.R
import com.shortsclipper.ui.EditorViewModel
import com.shortsclipper.ui.theme.Accent
import com.shortsclipper.ui.theme.Error
import com.shortsclipper.video.CropCalculator

/**
 * Live preview of the **imported source video** (ExoPlayer + TextureView).
 * The full original frame is shown with FIT (letterbox), preserving aspect ratio.
 * The 9:16 export crop is drawn as an overlay; dragging updates a reframe keyframe.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPreview(
    viewModel: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val playhead by viewModel.playheadMs.collectAsState()
    val playerError by viewModel.playerError.collectAsState()
    val source = state.source ?: return
    val player = viewModel.player

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black),
    ) {
        val cropRect = CropCalculator.rectAt(state, playhead)

        AndroidView(
            factory = { context ->
                (LayoutInflater.from(context).inflate(R.layout.player_view, null) as PlayerView).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setKeepContentOnPlayerReset(true)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                if (view.player !== player) {
                    view.player = player
                }
            },
            onRelease = { view ->
                view.player = null
            },
            modifier = Modifier.fillMaxSize(),
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state.id, cropRect, source.displayWidth, source.displayHeight) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val w = size.width.coerceAtLeast(1f)
                        val h = size.height.coerceAtLeast(1f)
                        val dCx = dragAmount.x / w
                        val dCy = dragAmount.y / h
                        viewModel.dragKeyframeAtPlayhead(cropRect.centerX + dCx, cropRect.centerY + dCy)
                    }
                },
        ) {
            val left = (cropRect.centerX - cropRect.widthFrac / 2f) * size.width
            val top = (cropRect.centerY - cropRect.heightFrac / 2f) * size.height
            val cropW = cropRect.widthFrac * size.width
            val cropH = cropRect.heightFrac * size.height
            drawRect(
                color = Color.Black.copy(alpha = 0.35f),
                topLeft = Offset(0f, 0f),
                size = Size(size.width, top.coerceAtLeast(0f)),
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.35f),
                topLeft = Offset(0f, (top + cropH).coerceAtMost(size.height)),
                size = Size(size.width, (size.height - top - cropH).coerceAtLeast(0f)),
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.35f),
                topLeft = Offset(0f, top.coerceAtLeast(0f)),
                size = Size(left.coerceAtLeast(0f), cropH.coerceAtLeast(0f)),
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.35f),
                topLeft = Offset((left + cropW).coerceAtMost(size.width), top.coerceAtLeast(0f)),
                size = Size((size.width - left - cropW).coerceAtLeast(0f), cropH.coerceAtLeast(0f)),
            )
            drawRect(
                color = Accent,
                topLeft = Offset(left, top),
                size = Size(cropW.coerceAtLeast(2f), cropH.coerceAtLeast(2f)),
                style = Stroke(width = 3f),
            )
        }

        val boxes by viewModel.faceSelectionBoxes.collectAsState()
        if (boxes.isNotEmpty()) {
            TrackingOverlay(
                boxes = boxes,
                selectedId = state.tracking.targetFaceId,
                modifier = Modifier.fillMaxSize(),
                onSelect = { viewModel.selectFace(it) },
            )
        }

        if (playerError != null) {
            Text(
                playerError!!,
                color = Error,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(10.dp),
            )
        }
    }
}
