package com.shortsclipper.ui.components

import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.shortsclipper.ui.EditorViewModel
import com.shortsclipper.video.CropCalculator
import kotlin.math.min

/**
 * Live 9:16 preview. The video is transformed with the EXACT crop rect the
 * exporter uses (CropCalculator), so what you see is what gets exported.
 * Dragging the video creates/updates a manual reframe keyframe at the playhead.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPreview(
    viewModel: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val playhead by viewModel.playheadMs.collectAsState()
    val source = state.source ?: return

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black),
    ) {
        val density = LocalDensity.current
        val boxW = maxWidth
        val boxH = maxHeight
        val boxWpx = with(density) { boxW.toPx() }
        val boxHpx = with(density) { boxH.toPx() }

        val fitScale = min(boxWpx / source.displayWidth, boxHpx / source.displayHeight)
        val contentWpx = source.displayWidth * fitScale
        val contentHpx = source.displayHeight * fitScale
        val contentWdp = with(density) { contentWpx.toDp() }
        val contentHdp = with(density) { contentHpx.toDp() }

        val cropRect = CropCalculator.rectAt(state, playhead)
        val transform = CropCalculator.previewTransform(cropRect, boxWpx, boxHpx, source.displayWidth, source.displayHeight)

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .pointerInput(state.id) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val dCx = dragAmount.x * cropRect.widthFrac / boxWpx
                        val dCy = dragAmount.y * cropRect.heightFrac / boxHpx
                        viewModel.dragKeyframeAtPlayhead(cropRect.centerX - dCx, cropRect.centerY - dCy)
                    }
                },
        ) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        player = viewModel.player
                    }
                },
                modifier = Modifier
                    .size(contentWdp, contentHdp)
                    .graphicsLayer {
                        scaleX = transform.scale
                        scaleY = transform.scale
                        translationX = transform.translationXPx
                        translationY = transform.translationYPx
                    },
            )
        }

        // Face target selection overlay (shown only during selection).
        val boxes by viewModel.faceSelectionBoxes.collectAsState()
        if (boxes.isNotEmpty()) {
            TrackingOverlay(
                boxes = boxes,
                selectedId = state.tracking.targetFaceId,
                cropRect = cropRect,
                modifier = Modifier.fillMaxSize(),
                onSelect = { viewModel.selectFace(it) },
            )
        }
    }
}
