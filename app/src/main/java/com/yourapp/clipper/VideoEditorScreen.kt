package com.yourapp.clipper

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.min

private val ASPECTS = listOf(
    Triple("9:16", 1080, 1920),
    Triple("16:9", 1920, 1080),
    Triple("1:1", 1080, 1080)
)

@Composable
fun VideoEditorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---------- State ----------
    var cachedFile by remember { mutableStateOf<File?>(null) }
    var meta by remember { mutableStateOf<VideoMeta?>(null) }
    var copying by remember { mutableStateOf(false) }

    var startText by remember { mutableStateOf("00:00:00") }
    var endText by remember { mutableStateOf("00:00:30") }
    var timeError by remember { mutableStateOf<String?>(null) }

    var selectedAspect by remember { mutableStateOf(0) }
    var showCropFrame by remember { mutableStateOf(false) }

    var cropFrame by remember { mutableStateOf(CropFrame(0f, 0f, 100f, 100f)) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }

    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0) }

    // ---------- PHASE 2: Tracking state ----------
    var autoTracking by remember { mutableStateOf(false) }
    var trackState by remember { mutableStateOf(TrackState.IDLE) }
    var scanProgress by remember { mutableStateOf(0) }
    var trackPoints by remember { mutableStateOf<List<TrackPoint>>(emptyList()) }
    var manualOverride by remember { mutableStateOf(false) }

    val faceTracker = remember { FaceTracker() }
    DisposableEffect(Unit) { onDispose { faceTracker.close() } }

    // ---------- ExoPlayer ----------
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(cachedFile) {
        cachedFile?.let {
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(it)))
            exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
            exoPlayer.prepare()
        }
        onDispose { }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    // ---------- SAF video picker ----------
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                copying = true
                val f = withContext(Dispatchers.IO) { copyToCache(context, uri) }
                copying = false
                if (f != null) {
                    val m = withContext(Dispatchers.IO) { VideoUtils.readMeta(context, uri) }
                    if (m == null) {
                        Toast.makeText(context, "Unsupported video", Toast.LENGTH_SHORT).show()
                    } else if (m.durationMs > 3 * 3600_000L) {
                        Toast.makeText(context, "Video exceeds 3-hour limit", Toast.LENGTH_LONG).show()
                        f.delete()
                    } else {
                        cachedFile?.delete()
                        cachedFile = f
                        meta = m
                        val defEnd = minOf(30_000L, m.durationMs)
                        endText = VideoUtils.formatMs(defEnd)
                        timeError = null
                        showCropFrame = false
                        trackPoints = emptyList()
                        trackState = TrackState.IDLE
                    }
                } else Toast.makeText(context, "Failed to copy video", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- Time validation ----------
    fun validateTimes() {
        val m = meta ?: return
        val start = VideoUtils.parseHmsToMs(startText)
        val end = VideoUtils.parseHmsToMs(endText)
        timeError = when {
            start == null -> "Invalid start time (use HH:MM:SS)"
            end == null -> "Invalid end time (use HH:MM:SS)"
            end <= start -> "End must be after start"
            end > m.durationMs -> "End exceeds video duration"
            (end - start) > 120_000L -> "Clip must be ≤ 2 minutes"
            else -> null
        }
    }

    // ---------- PHASE 2: Scan when tracking toggled ON ----------
    LaunchedEffect(autoTracking, startText, endText, cachedFile) {
        val file = cachedFile ?: return@LaunchedEffect
        val m = meta ?: return@LaunchedEffect
        if (!autoTracking) {
            trackPoints = emptyList()
            trackState = TrackState.IDLE
            return@LaunchedEffect
        }

        val startMs = VideoUtils.parseHmsToMs(startText) ?: return@LaunchedEffect
        val endMs = VideoUtils.parseHmsToMs(endText) ?: return@LaunchedEffect
        if (timeError != null || endMs <= startMs) return@LaunchedEffect

        trackState = TrackState.SCANNING
        manualOverride = false
        val pts = faceTracker.scanRange(
            context, Uri.fromFile(file), startMs, endMs, m.width, m.height
        ) { scanProgress = it }
        trackPoints = pts
        trackState = if (pts.isEmpty()) TrackState.FAILED else TrackState.DONE
        if (pts.isEmpty()) {
            Toast.makeText(context, "No face detected — frame stays manual", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- PHASE 2: Live frame follows face during playback ----------
    LaunchedEffect(autoTracking, trackPoints) {
        val m = meta ?: return@LaunchedEffect
        val startMs = VideoUtils.parseHmsToMs(startText) ?: return@LaunchedEffect
        while (autoTracking && trackPoints.isNotEmpty() && !manualOverride) {
            if (!exoPlayer.isPlaying) { delay(100); continue }

            val pos = exoPlayer.currentPosition + startMs
            val nearest = trackPoints.minByOrNull { abs(it.timeMs - pos) }
            if (nearest != null && previewSize != IntSize.Zero) {
                val (dispW, dispH) = fittedVideoRect(
                    previewSize.width.toFloat(), previewSize.height.toFloat(),
                    m.width.toFloat(), m.height.toFloat()
                )
                val offX = (previewSize.width - dispW) / 2f
                val offY = (previewSize.height - dispH) / 2f
                val fW = cropFrame.width; val fH = cropFrame.height
                val targetX = offX + nearest.centerX * dispW - fW / 2f
                val targetY = offY + nearest.centerY * dispH - fH / 2f
                cropFrame = cropFrame.copy(
                    left = (cropFrame.left + (targetX - cropFrame.left) * 0.25f)
                        .coerceIn(0f, previewSize.width - fW),
                    top = (cropFrame.top + (targetY - cropFrame.top) * 0.25f)
                        .coerceIn(0f, previewSize.height - fH)
                )
            }
            delay(33)
        }
    }

    // ---------- Export ----------
    fun doExport() {
        val file = cachedFile ?: return
        val m = meta ?: return
        validateTimes()
        if (timeError != null) {
            Toast.makeText(context, timeError, Toast.LENGTH_LONG).show()
            return
        }
        val startMs = VideoUtils.parseHmsToMs(startText)!!
        val endMs = VideoUtils.parseHmsToMs(endText)!!

        // Compose pixels → video pixels (accounting for letterbox)
        val (dispW, dispH) = fittedVideoRect(
            previewSize.width.toFloat(), previewSize.height.toFloat(),
            m.width.toFloat(), m.height.toFloat()
        )
        val offX = (previewSize.width - dispW) / 2f
        val offY = (previewSize.height - dispH) / 2f

        val relX = (cropFrame.left - offX) / dispW
        val relY = (cropFrame.top - offY) / dispH
        val relW = cropFrame.width / dispW
        val relH = cropFrame.height / dispH

        val (_, aw, ah) = ASPECTS[selectedAspect]
        val scale = min(m.width.toFloat() / aw, m.height.toFloat() / ah)
        var outW = (aw * scale).toInt() / 2 * 2
        var outH = (ah * scale).toInt() / 2 * 2
        var cropX = (relX * m.width).toInt().coerceIn(0, m.width - outW)
        var cropY = (relY * m.height).toInt().coerceIn(0, m.height - outH)

        if (relW < 0.999f && relH < 0.999f) {
            outW = ((relW * m.width).toInt() / 2) * 2
            outH = ((relH * m.height).toInt() / 2) * 2
            cropX = (relX * m.width).toInt().coerceIn(0, m.width - outW)
            cropY = (relY * m.height).toInt().coerceIn(0, m.height - outH)
        }

        exporting = true
        exportProgress = 0
        scope.launch {
            val result = if (autoTracking && trackPoints.isNotEmpty()) {
                FFmpegHelper.exportTrackedClip(
                    context, file, startMs, endMs,
                    outW, outH, m.width, m.height,
                    trackPoints, onProgress = { exportProgress = it }
                )
            } else {
                FFmpegHelper.exportClip(
                    context, file, startMs, endMs,
                    outW, outH, cropX, cropY, m.width, m.height,
                    onProgress = { exportProgress = it }
                )
            }
            exporting = false
            when (result) {
                is ExportResult.Success -> Toast.makeText(
                    context, "Saved: ${result.outputFile.absolutePath}", Toast.LENGTH_LONG
                ).show()
                is ExportResult.Failure -> Toast.makeText(
                    context, "Export failed: ${result.message.take(200)}", Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ---------- UI ----------
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Long to Short Clipper", style = MaterialTheme.typography.titleLarge)

        Button(
            onClick = { pickLauncher.launch(arrayOf("video/*")) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Pick Video (up to 3 hours)") }

        if (copying) {
            LinearProgress(modifier = Modifier.fillMaxWidth())
            Text("Copying to cache…")
        }

        // ---------- Preview ----------
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onGloballyPositioned { previewSize = it.size }
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        player = exoPlayer
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (showCropFrame && meta != null && previewSize != IntSize.Zero) {
                val m = meta!!
                val (dispW, dispH) = fittedVideoRect(
                    previewSize.width.toFloat(), previewSize.height.toFloat(),
                    m.width.toFloat(), m.height.toFloat()
                )
                val offX = (previewSize.width - dispW) / 2f
                val offY = (previewSize.height - dispH) / 2f
                val aspect = ASPECTS[selectedAspect].second.toFloat() /
                        ASPECTS[selectedAspect].third.toFloat()

                LaunchedEffect(selectedAspect, showCropFrame) {
                    val fw = dispW * 0.9f
                    val fh = fw / aspect
                    val fH = if (fh <= dispH * 0.9f) fh else dispH * 0.9f
                    val fW = fH * aspect
                    cropFrame = CropFrame(
                        offX + (dispW - fW) / 2f,
                        offY + (dispH - fH) / 2f,
                        fW, fH
                    )
                }

                CropFrameOverlay(
                    containerWidth = previewSize.width.toFloat(),
                    containerHeight = previewSize.height.toFloat(),
                    aspectRatio = aspect,
                    frame = cropFrame,
                    onFrameChanged = { cropFrame = it },
                    onUserTouch = { if (autoTracking) manualOverride = true },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // ---------- ONLY Play/Pause ----------
        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(onClick = {
                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
            }) {
                Text(if (exoPlayer.isPlaying) "⏸ Pause" else "▶ Play")
            }
        }

                // ---------- Time inputs ----------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = startText,
                onValueChange = { startText = it; validateTimes() },
                label = { Text("Start HH:MM:SS") },
                isError = timeError != null,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = endText,
                onValueChange = { endText = it; validateTimes() },
                label = { Text("End HH:MM:SS") },
                isError = timeError != null,
                modifier = Modifier.weight(1f)
            )
        }
        timeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        meta?.let {
            Text(
                "Video: ${VideoUtils.formatMs(it.durationMs)} • " +
                        "it.widthx{it.width}xit.widthx{it.height} • ${(it.bitrate / 1000)} kbps",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // ---------- Aspect ratio buttons ----------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ASPECTS.forEachIndexed { i, (label, _, _) ->
                FilterChip(
                    selected = selectedAspect == i && showCropFrame,
                    onClick = {
                        selectedAspect = i
                        showCropFrame = true
                    },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (showCropFrame) {
            Text(
                "Drag the green frame; drag corners to resize.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // ---------- PHASE 2: Auto Tracking ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { autoTracking = !autoTracking },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (autoTracking) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondary
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(if (autoTracking) "🤖 Auto Tracking ON" else "🤖 Auto Tracking OFF")
            }
        }

        if (trackState == TrackState.SCANNING) {
            LinearProgressIndicator(
                progress = { scanProgress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Text("Scanning for faces… $scanProgress%")
        }
        if (trackState == TrackState.DONE && autoTracking) {
            Text(
                "✅ ${trackPoints.size} track points — frame follows face" +
                        if (manualOverride) " (manual override active)" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (manualOverride) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        }

        // ---------- Export ----------
        Button(
            onClick = { doExport() },
            enabled = cachedFile != null && !exporting && timeError == null,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (exporting) "Exporting… $exportProgress%" else "Export Clip") }

        if (exporting) {
            LinearProgressIndicator(
                progress = { exportProgress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = { FFmpegHelper.cancelCurrent() }) { Text("Cancel") }
        }

        // PHASE 3 placeholder — coming next
        // SplitScreenControls(...)
    }
}

// ---------- Helpers ----------

/** Letterbox-fitted video size inside the preview container. */
private fun fittedVideoRect(cw: Float, ch: Float, vw: Float, vh: Float): Pair<Float, Float> {
    if (vw <= 0f || vh <= 0f || cw <= 0f || ch <= 0f) return Pair(0f, 0f)
    val scale = min(cw / vw, ch / vh)
    return Pair(vw * scale, vh * scale)
}

/** Copies a SAF-selected video into app cache. */
private fun copyToCache(context: Context, uri: Uri): File? = try {
    val dest = File(context.cacheDir, "source_${System.currentTimeMillis()}.mp4")
    context.contentResolver.openInputStream(uri)?.use { input ->
        dest.outputStream().use { output ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                output.write(buf, 0, read)
            }
            output.flush()
        }
    } ?: return null
    dest
} catch (e: Exception) {
    null
}

