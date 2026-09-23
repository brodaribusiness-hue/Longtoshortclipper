package com.shortsclipper.ui.components

import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.shortsclipper.R
import com.shortsclipper.ui.EditorViewModel
import com.shortsclipper.ui.theme.Error
import com.shortsclipper.ui.theme.TextSecondary
import com.shortsclipper.video.CropCalculator
import com.shortsclipper.video.PreviewTransform
import kotlin.math.min

/**
 * Live 9:16 preview. The video is a TextureView (SurfaceView ignores Compose
 * transforms and shows a black frame) scaled with the exact crop rect the
 * exporter uses, so what you see is what gets exported.
 * Dragging the preview creates or updates a manual reframe keyframe.
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
    val showFacePicker by viewModel.showFacePicker.collectAsState()
    val boxes by viewModel.faceSelectionBoxes.collectAsState()
    val source = state.source ?: return
    val player = viewModel.player
    val picking = showFacePicker && boxes.isNotEmpty()

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black),
    ) {
        val density = LocalDensity.current
        val boxWpx = with(density) { maxWidth.toPx() }
        val boxHpx = with(density) { maxHeight.toPx() }
        if (boxWpx < 1f || boxHpx < 1f || source.displayWidth <= 0 || source.displayHeight <= 0) {
            return@BoxWithConstraints
        }

        val fitScale = min(boxWpx / source.displayWidth, boxHpx / source.displayHeight)
        val contentWpx = source.displayWidth * fitScale
        val contentHpx = source.displayHeight * fitScale
        val contentWdp = with(density) { contentWpx.toDp() }
        val contentHdp = with(density) { contentHpx.toDp() }
        val cropRect = CropCalculator.rectAt(state, playhead)
        val transform = if (picking) {
            PreviewTransform(1f, 0f, 0f)
        } else {
            CropCalculator.previewTransform(cropRect, boxWpx, boxHpx, source.displayWidth, source.displayHeight)
        }

        AndroidView(
            factory = { context ->
                (LayoutInflater.from(context).inflate(R.layout.player_view, null) as PlayerView).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setKeepContentOnPlayerReset(true)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier
                .align(Alignment.Center)
                .size(contentWdp, contentHdp)
                .graphicsLayer {
                    scaleX = transform.scale
                    scaleY = transform.scale
                    translationX = transform.translationXPx
                    translationY = transform.translationYPx
                },
        )

        if (!picking) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(state.id) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val rect = CropCalculator.rectAt(viewModel.state.value, viewModel.playheadMs.value)
                            val dCx = dragAmount.x * rect.widthFrac / w
                            val dCy = dragAmount.y * rect.heightFrac / h
                            viewModel.dragKeyframeAtPlayhead(rect.centerX + dCx, rect.centerY + dCy)
                        }
                    },
            )
        } else {
            TrackingOverlay(
                boxes = boxes,
                selectedId = state.tracking.targetFaceId,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(contentWdp, contentHdp),
                onSelect = { viewModel.selectFace(it) },
            )
            Text(
                "Tap a face to track",
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp),
            )
        }

        if (player == null && playerError == null) {
            Text(
                "Preparing playback…",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center),
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
