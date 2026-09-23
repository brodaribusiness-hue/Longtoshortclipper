package com.shortsclipper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortsclipper.model.SmoothingPreset
import com.shortsclipper.tracking.TrackingEngine
import com.shortsclipper.ui.components.CutIcon
import com.shortsclipper.ui.components.PauseIcon
import com.shortsclipper.ui.components.PlayIcon
import com.shortsclipper.ui.components.RedoIcon
import com.shortsclipper.ui.components.TimelineView
import com.shortsclipper.ui.components.UndoIcon
import com.shortsclipper.ui.components.VideoPreview
import com.shortsclipper.ui.components.formatTime
import com.shortsclipper.ui.theme.Accent
import com.shortsclipper.ui.theme.BgControl
import com.shortsclipper.ui.theme.BgSecondary
import com.shortsclipper.ui.theme.Error
import com.shortsclipper.ui.theme.Success
import com.shortsclipper.ui.theme.TextPrimary
import com.shortsclipper.ui.theme.TextSecondary
import com.shortsclipper.ui.theme.Warning
import com.shortsclipper.video.CropCalculator

/**
 * Main editor. Layout follows the specification:
 * close/export top bar, dominant 9:16 preview, transport + undo/redo,
 * auto face tracking toggle, timeline, and bottom actions
 * (Tracking/Reframe and Silence).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onOpenExport: () -> Unit,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val playhead by viewModel.playheadMs.collectAsState()
    val playing by viewModel.isPlaying.collectAsState()
    val trackingProgress by viewModel.trackingPassProgress.collectAsState()

    var showTrackingSheet by remember { mutableStateOf(false) }
    var showSilenceSheet by remember { mutableStateOf(false) }
    val faceBoxes by viewModel.faceSelectionBoxes.collectAsState()
    val pickingFace by viewModel.showFacePicker.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.pause()
        }
    }

    val source = state.source
    if (source == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No video loaded", color = TextSecondary)
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {

        // ---- Top bar -------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close editor", tint = TextPrimary)
            }
            Text(
                state.name,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Button(
                onClick = onOpenExport,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp),
            ) {
                Text("Export", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // ---- Video preview (fitted 9:16, same crop the exporter writes) ---
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val fittedWidth = maxHeight * (9f / 16f)
            val previewWidth = if (maxWidth < fittedWidth) maxWidth else fittedWidth
            val previewHeight = previewWidth / (9f / 16f)
            VideoPreview(
                viewModel = viewModel,
                modifier = Modifier.size(previewWidth, previewHeight),
            )
            if (trackingProgress >= 0f) {
                Column(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(10.dp))
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Scanning faces… ${(trackingProgress * 100).toInt()}%", color = TextPrimary, fontSize = 12.sp)
                    LinearProgressIndicator(
                        progress = { trackingProgress },
                        color = Accent,
                        modifier = Modifier.padding(top = 8.dp).width(180.dp),
                    )
                    TextButton(onClick = { viewModel.setAutoTracking(false) }) {
                        Text("Cancel", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }

        // ---- Transport -----------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatTime(playhead, withFraction = true),
                color = TextSecondary,
                fontSize = 12.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { viewModel.undo() }, enabled = viewModel.canUndo) {
                UndoIcon(tint = TextPrimary, enabled = viewModel.canUndo)
            }
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(Accent, CircleShape)
                    .clickable { viewModel.togglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                if (playing) PauseIcon(28.dp, Color.Black) else PlayIcon(28.dp, Color.Black)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { viewModel.redo() }, enabled = viewModel.canRedo) {
                RedoIcon(tint = TextPrimary, enabled = viewModel.canRedo)
            }
        }

        // ---- Auto face tracking --------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Face, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Auto Face Tracking", color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (state.tracking.targetFaceId != null) {
                Text("Face #${state.tracking.targetFaceId}", color = TextSecondary, fontSize = 10.sp)
                Spacer(Modifier.width(6.dp))
            }
            if (faceBoxes.isNotEmpty()) {
                TextButton(onClick = {
                    if (pickingFace) viewModel.dismissFacePicker() else viewModel.revealFacePicker()
                }) {
                    Text(if (pickingFace) "Hide" else "Faces", color = Accent, fontSize = 11.sp)
                }
            }
            Switch(
                checked = state.tracking.autoEnabled,
                onCheckedChange = { viewModel.setAutoTracking(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.Black),
            )
        }

        // ---- Timeline ------------------------------------------------------
        TimelineView(
            durationMs = source.durationMs,
            selectionStartMs = state.timeline.selectionStartMs,
            selectionEndMs = state.timeline.selectionEndMs,
            playheadMs = playhead,
            envelope = state.audioEnvelope,
            envelopeStepMs = state.envelopeStepMs,
            zoom = state.timeline.zoom,
            removals = state.silenceRemovals,
            onSelection = { start, end -> viewModel.setSelection(start, end) },
            onSeek = { viewModel.seekTo(it) },
            onZoom = { viewModel.setTimelineZoom(it) },
            modifier = Modifier.padding(vertical = 4.dp),
        )

        if (state.silenceRemovals.isNotEmpty()) {
            Text(
                "Silence removal ON · edited duration ${formatTime(state.editedDurationMs)} · preview skips pauses",
                color = Success,
                fontSize = 10.sp,
            )
        }

        // ---- Bottom actions ------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EditorAction("Tracking / Reframe", Modifier.weight(1.2f)) { showTrackingSheet = true }
            EditorAction("Silence", Modifier.weight(0.8f)) { showSilenceSheet = true }
        }
        Spacer(Modifier.height(4.dp))
    }

    if (showTrackingSheet) {
        ModalBottomSheet(onDismissRequest = { showTrackingSheet = false }, containerColor = BgSecondary) {
            TrackingSheetContent(viewModel)
        }
    }
    if (showSilenceSheet) {
        ModalBottomSheet(onDismissRequest = { showSilenceSheet = false }, containerColor = BgSecondary) {
            SilenceSheetContent(viewModel)
        }
    }
}

@Composable
private fun EditorAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = BgControl,
            contentColor = TextPrimary,
        ),
        shape = RoundedCornerShape(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
    ) {
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun TrackingSheetContent(viewModel: EditorViewModel) {
    val state by viewModel.state.collectAsState()
    val playhead by viewModel.playheadMs.collectAsState()
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Tracking / Reframe", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Drag the preview to reframe. Manual keyframes correct automatic tracking (hybrid) and interpolate smoothly.",
            color = TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )

        val zoomNow = TrackingEngine.evaluateAt(state.tracking, playhead).zoom
        Text("Reframe zoom ${"%.2f".format(zoomNow)}×", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Slider(
            value = zoomNow,
            onValueChange = { z ->
                val target = TrackingEngine.evaluateAt(viewModel.state.value.tracking, viewModel.playheadMs.value)
                viewModel.dragKeyframeAtPlayhead(target.centerX, target.centerY, z)
            },
            valueRange = 1f..CropCalculator.MAX_ZOOM,
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
        )

        Text("Tracking smoothing", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Row(modifier = Modifier.padding(top = 6.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in SmoothingPreset.entries) {
                FilterChip(
                    selected = state.tracking.smoothing == preset,
                    onClick = { viewModel.setSmoothing(preset) },
                    label = { Text(preset.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Accent,
                        selectedLabelColor = Color.Black,
                        labelColor = TextPrimary,
                    ),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { viewModel.addManualKeyframe() },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
            ) {
                Text("Add keyframe at playhead", fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { viewModel.clearManualKeyframes() }) {
                Text("Clear all", color = TextSecondary, fontSize = 12.sp)
            }
        }

        val keyframes = state.tracking.manualKeyframes
        if (keyframes.isEmpty()) {
            Text("No manual keyframes yet.", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp))
        } else {
            for (keyframe in keyframes) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        formatTime(keyframe.timeMs, withFraction = true),
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "x=%.2f y=%.2f zoom=%.2f".format(keyframe.centerXFrac, keyframe.centerYFrac, keyframe.zoom),
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { viewModel.deleteKeyframe(keyframe.timeMs) }) {
                        Text("Delete", color = Error, fontSize = 11.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SilenceSheetContent(viewModel: EditorViewModel) {
    val state by viewModel.state.collectAsState()
    var thresholdDb by remember(state.detectedSilences) { mutableStateOf(-38f) }
    var minSilenceMs by remember(state.detectedSilences) { mutableStateOf(600f) }
    val skipSilences by viewModel.previewSkipSilences.collectAsState()
    val isPreparingAudio by viewModel.isPreparingAudio.collectAsState()
    val audioPreparationError by viewModel.audioPreparationError.collectAsState()

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Silence detection & removal", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Detects unnecessary pauses from the local audio envelope. Short natural pauses are kept by default. Removals never cut words: cuts happen inside silence windows only.",
            color = TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )

        Text("Loudness threshold: ${thresholdDb.toInt()} dB", color = TextPrimary, fontSize = 12.sp)
        Slider(
            value = thresholdDb,
            onValueChange = { thresholdDb = it },
            valueRange = -60f..-20f,
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
        )
        Text("Minimum pause: ${(minSilenceMs / 1000f).format1()} s", color = TextPrimary, fontSize = 12.sp)
        Slider(
            value = minSilenceMs,
            onValueChange = { minSilenceMs = it },
            valueRange = 300f..2000f,
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            when {
                isPreparingAudio -> {
                    Text("Analyzing local audio…", color = TextSecondary, fontSize = 11.sp)
                }
                state.audioEnvelope.isEmpty() -> {
                    Button(
                        onClick = viewModel::prepareAudioForSilence,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                    ) {
                        Text("Analyze audio", fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Analyze the local audio track to enable silence detection", color = TextSecondary, fontSize = 11.sp)
                }
                else -> {
                    Button(
                        onClick = { viewModel.detectSilences(thresholdDb, minSilenceMs.toInt()) },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                    ) {
                        Text("Detect silence", fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("${state.detectedSilences.size} pauses detected", color = TextSecondary, fontSize = 11.sp)
                }
            }
        }
        if (audioPreparationError != null) {
            Text(
                audioPreparationError!!,
                color = Error,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (state.detectedSilences.isNotEmpty()) {
            val total = state.detectedSilences.sumOf { it.durationMs }
            Text(
                "Total quiet time: ${formatTime(total)}",
                color = Warning,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { viewModel.applySilenceRemoval() },
                    colors = ButtonDefaults.buttonColors(containerColor = Success, contentColor = Color.Black),
                ) {
                    Text("Apply removal", fontSize = 12.sp)
                }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = { viewModel.clearSilenceRemoval() }) {
                    Text("Remove edits", color = TextSecondary, fontSize = 12.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Preview skips silences", color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = skipSilences,
                    onCheckedChange = { viewModel.previewSkipSilences.value = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.Black),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun Float.format1(): String = String.format("%.1f", this)

