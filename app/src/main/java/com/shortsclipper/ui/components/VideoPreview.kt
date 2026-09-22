package com.shortsclipper.ui.components

import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import android.view.LayoutInflater
import com.shortsclipper.R
import com.shortsclipper.ui.EditorViewModel
import com.shortsclipper.video.CropCalculator
import kotlin.math.min

/** The editor deliberately distinguishes the uncropped source from 9:16 output. */
enum class PreviewMode { SOURCE, OUTPUT_9_16 }

/** Values read by the persistent preview drag detector between recompositions. */
private data class PreviewDragState(
    val cropRect: com.shortsclipper.video.CropRect,
    val contentWidthPx: Float,
    val contentHeightPx: Float,
    val outputCoordinates: Boolean,
    val onDragCropCenter: (Float, Float) -> Unit,
) {
    fun dragBy(deltaX: Float, deltaY: Float) {
        val horizontalFraction = if (outputCoordinates) {
            deltaX * cropRect.widthFrac / contentWidthPx.coerceAtLeast(1f)
        } else {
            deltaX / contentWidthPx.coerceAtLeast(1f)
        }
        val verticalFraction = if (outputCoordinates) {
            deltaY * cropRect.heightFrac / contentHeightPx.coerceAtLeast(1f)
        } else {
            deltaY / contentHeightPx.coerceAtLeast(1f)
        }
        onDragCropCenter(cropRect.centerX + horizontalFraction, cropRect.centerY + verticalFraction)
    }
}

/**
 * SOURCE mode always fits the original upright display aspect with no stretch
 * or crop. It overlays the tracked 9:16 output guide so selecting/reframing a
 * face is unambiguous. OUTPUT_9_16 mode renders the exact CropCalculator crop
 * used by export for a compact WYSIWYG check.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPreview(
    viewModel: EditorViewModel,
    mode: PreviewMode,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val playhead by viewModel.playheadMs.collectAsState()
    val boxes by viewModel.faceSelectionBoxes.collectAsState()
    val captionSegments = remember(state.transcript, state.captionEdits) { state.resolvedCaptionSegments() }
    val source = state.source ?: return

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val outerWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val outerHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val cropRect = CropCalculator.rectAt(state, playhead)

        when (mode) {
            PreviewMode.SOURCE -> {
                val sourceScale = min(outerWidthPx / source.displayWidth, outerHeightPx / source.displayHeight)
                val sourceWidthPx = source.displayWidth * sourceScale
                val sourceHeightPx = source.displayHeight * sourceScale
                val sourceWidthDp = with(density) { sourceWidthPx.toDp() }
                val sourceHeightDp = with(density) { sourceHeightPx.toDp() }
                val latestSourceDrag = rememberUpdatedState(
                    PreviewDragState(
                        cropRect = cropRect,
                        contentWidthPx = sourceWidthPx,
                        contentHeightPx = sourceHeightPx,
                        outputCoordinates = false,
                        onDragCropCenter = { centerX, centerY ->
                            viewModel.dragKeyframeAtPlayhead(centerX, centerY)
                        },
                    ),
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(sourceWidthDp, sourceHeightDp)
                        .clip(RoundedCornerShape(10.dp)),
                ) {
                    PlayerSurface(viewModel, Modifier.fillMaxSize())
                    SourceCropGuide(
                        cropRect = cropRect,
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    latestSourceDrag.value.dragBy(dragAmount.x, dragAmount.y)
                                }
                            },
                    )
                    if (boxes.isNotEmpty()) {
                        TrackingOverlay(
                            boxes = boxes,
                            selectedId = state.tracking.targetFaceId,
                            modifier = Modifier.matchParentSize(),
                            onSelect = viewModel::selectFace,
                            onDrag = { dragAmount ->
                                latestSourceDrag.value.dragBy(dragAmount.x, dragAmount.y)
                            },
                        )
                    }
                    // Kept above source-guide shading so readable captions are
                    // never dimmed simply because they fall outside the crop.
                    CaptionPreview(captionSegments, state.captionsEnabled, playhead, Modifier.align(Alignment.BottomCenter))
                }
            }

            PreviewMode.OUTPUT_9_16 -> {
                val outputHeightPx = min(outerHeightPx, outerWidthPx / CropCalculator.TARGET_ASPECT)
                val outputWidthPx = outputHeightPx * CropCalculator.TARGET_ASPECT
                val outputWidthDp = with(density) { outputWidthPx.toDp() }
                val outputHeightDp = with(density) { outputHeightPx.toDp() }
                val sourceFitScale = min(outputWidthPx / source.displayWidth, outputHeightPx / source.displayHeight)
                val sourceWidthDp = with(density) { (source.displayWidth * sourceFitScale).toDp() }
                val sourceHeightDp = with(density) { (source.displayHeight * sourceFitScale).toDp() }
                val transform = CropCalculator.previewTransform(
                    rect = cropRect,
                    boxWidthPx = outputWidthPx,
                    boxHeightPx = outputHeightPx,
                    displayW = source.displayWidth,
                    displayH = source.displayHeight,
                )
                val latestOutputDrag = rememberUpdatedState(
                    PreviewDragState(
                        cropRect = cropRect,
                        contentWidthPx = outputWidthPx,
                        contentHeightPx = outputHeightPx,
                        outputCoordinates = true,
                        onDragCropCenter = { centerX, centerY ->
                            viewModel.dragKeyframeAtPlayhead(centerX, centerY)
                        },
                    ),
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(outputWidthDp, outputHeightDp)
                        .clip(RoundedCornerShape(10.dp))
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                latestOutputDrag.value.dragBy(dragAmount.x, dragAmount.y)
                            }
                        },
                ) {
                    PlayerSurface(
                        viewModel = viewModel,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(sourceWidthDp, sourceHeightDp)
                            .graphicsLayer {
                                scaleX = transform.scale
                                scaleY = transform.scale
                                translationX = transform.translationXPx
                                translationY = transform.translationYPx
                            },
                    )
                    CaptionPreview(captionSegments, state.captionsEnabled, playhead, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(viewModel: EditorViewModel, modifier: Modifier) {
    AndroidView(
        factory = { context ->
            // The XML surface_type is texture_view. Unlike SurfaceView, its
            // pixels participate in the parent Compose clip/graphicsLayer,
            // which is essential for the transformed 9:16 WYSIWYG preview.
            (LayoutInflater.from(context)
                .inflate(R.layout.view_preview_player, null, false) as PlayerView).apply {
                player = viewModel.player
            }
        },
        update = { it.player = viewModel.player },
        modifier = modifier,
    )
}

@Composable
private fun CaptionPreview(
    captions: List<com.shortsclipper.model.Segment>,
    enabled: Boolean,
    timeMs: Long,
    modifier: Modifier = Modifier,
) {
    if (!enabled || captions.isEmpty()) return
    var low = 0
    var high = captions.lastIndex
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (captions[middle].startTimeMs <= timeMs) low = middle + 1 else high = middle - 1
    }
    val caption = captions.getOrNull(high)
        ?.takeIf { timeMs < it.endTimeMs }
        ?.text
        ?.trim()
        .orEmpty()
    if (caption.isBlank()) return
    Text(
        text = caption,
        color = Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        modifier = modifier
            .fillMaxWidth(0.86f)
            .padding(bottom = 14.dp)
            .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

@Composable
private fun SourceCropGuide(cropRect: com.shortsclipper.video.CropRect, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cropWidth = cropRect.widthFrac * size.width
        val cropHeight = cropRect.heightFrac * size.height
        val left = (cropRect.centerX * size.width - cropWidth / 2f).coerceIn(0f, size.width)
        val top = (cropRect.centerY * size.height - cropHeight / 2f).coerceIn(0f, size.height)
        val right = (left + cropWidth).coerceIn(0f, size.width)
        val bottom = (top + cropHeight).coerceIn(0f, size.height)
        val shade = Color.Black.copy(alpha = 0.48f)
        drawRect(shade, topLeft = Offset.Zero, size = Size(size.width, top))
        drawRect(shade, topLeft = Offset.Zero.copy(y = bottom), size = Size(size.width, size.height - bottom))
        drawRect(shade, topLeft = Offset.Zero.copy(x = 0f, y = top), size = Size(left, bottom - top))
        drawRect(shade, topLeft = Offset(right, top), size = Size(size.width - right, bottom - top))
        drawRect(
            color = Color(0xFFB8FF5C),
            topLeft = Offset(left, top),
            size = Size((right - left).coerceAtLeast(1f), (bottom - top).coerceAtLeast(1f)),
            style = Stroke(width = 3f),
        )
    }
}
