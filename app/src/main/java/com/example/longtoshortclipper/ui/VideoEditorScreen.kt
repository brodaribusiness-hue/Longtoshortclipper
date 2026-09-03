package com.example.longtoshortclipper.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.longtoshortclipper.ffmpeg.FFmpegHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity

// ---------- Aspect Ratio Data ----------
data class AspectRatio(val label: String, val w: Int, val h: Int)

val ASPECT_RATIOS = listOf(
    AspectRatio("9:16", 1080, 1920),
    AspectRatio("16:9", 1920, 1080),
    AspectRatio("1:1", 1080, 1080)
)

@Composable
fun VideoEditorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---------- States ----------
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var cachedFile by remember { mutableStateOf<File?>(null) }
    var selectedAspect by remember { mutableStateOf<AspectRatio?>(null) }

    var startInput by remember { mutableStateOf("00:00:00") }
    var endInput by remember { mutableStateOf("00:00:30") }
    var timeError by remember { mutableStateOf<String?>(null) }

    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0f) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    // Crop frame state (position + size) in px, relative to video display box
    var frameOffsetX by remember { mutableStateOf(0f) }
    var frameOffsetY by remember { mutableStateOf(0f) }
    var frameWidth by remember { mutableStateOf(300f) }
    var frameHeight by remember { mutableStateOf(300f) }

    // Video display box size (px)
    var displaySize by remember { mutableStateOf(IntSize.Zero) }
    // Actual scaled video size inside display box (letterbox calculation)
    var videoDisplaySize by remember { mutableStateOf(IntSize.Zero) }
    var videoPixelSize by remember { mutableStateOf(IntSize.Zero) }

    // ---------- SAF Video Picker ----------
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                exporting = true
                exportMessage = "Video cache me copy ho raha hai..."
                withContext(Dispatchers.IO) {
                    val f = File(context.cacheDir, "input_video_${System.currentTimeMillis()}.mp4")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { output -> input.copyTo(output) }
                    }
                    cachedFile = f
                }
                // Video ki real resolution nikaalo (crop scaling ke liye)
                withContext(Dispatchers.Default) {
                    val dims = FFmpegHelper.getVideoDimensions(context, uri)
                    videoPixelSize = IntSize(dims.first, dims.second)
                }
                videoUri = uri
                exporting = false
                exportMessage = null
            }
        }
    }

    // ---------- Time Validation ----------
    fun validateTimes() {
        val s = parseTime(startInput)
        val e = parseTime(endInput)
        timeError = when {
            s == null || e == null -> "Time format galat hai. Sahi format: HH:MM:SS"
            e <= s -> "End time, Start time se bada hona chahiye"
            (e - s) > 120 -> "Clip 2 minute (120 sec) se choti honi chahiye!"
            else -> null
        }
    }

    // ---------- Reset frame when aspect changes ----------
    fun applyAspectToFrame() {
        val aspect = selectedAspect ?: return
        val vw = videoDisplaySize.width.toFloat()
        val vh = videoDisplaySize.height.toFloat()
        if (vw <= 0 || vh <= 0) return
        // Fit aspect frame inside video display area, centered
        val aspectF = aspect.w.toFloat() / aspect.h.toFloat()
        var w = vw * 0.7f
        var h = w / aspectF
        if (h > vh * 0.7f) {
            h = vh * 0.7f
            w = h * aspectF
        }
        frameWidth = w
        frameHeight = h
        frameOffsetX = (vw - w) / 2f
        frameOffsetY = (vh - h) / 2f
    }

    // ---------- Export ----------
    fun doExport() {
        validateTimes()
        val file = cachedFile
        val aspect = selectedAspect
        if (timeError != null) return
        if (file == null || aspect == null) {
            exportMessage = "Pehle video select karo aur aspect ratio choose karo"
            return
        }
        if (videoPixelSize.width <= 0) return

        exporting = true
        exportMessage = "Export ho raha hai..."
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                // Display coords -> video pixel coords conversion
                val scale = if (videoDisplaySize.width > 0)
                    videoPixelSize.width.toFloat() / videoDisplaySize.width.toFloat() else 1f

                val cropW = (frameWidth * scale).toInt().coerceAtMost(videoPixelSize.width)
                val cropH = (frameHeight * scale).toInt().coerceAtMost(videoPixelSize.height)
                // crop w,h ko even numbers me round karo (encoder requirement)
                val evenW = cropW - (cropW % 2)
                val evenH = cropH - (cropH % 2)
                val cropX = (frameOffsetX * scale).toInt().coerceIn(0, videoPixelSize.width - evenW)
                val cropY = (frameOffsetY * scale).toInt().coerceIn(0, videoPixelSize.height - evenH)

                FFmpegHelper.exportClip(
                    inputFile = file,
                    outputDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").apply { mkdirs() },
                    startSeconds = parseTime(startInput)!!,
                    endSeconds = parseTime(endInput)!!,
                    cropW = evenW, cropH = evenH, cropX = cropX, cropY = cropY
                ) { progress -> exportProgress = progress }
            }
            exporting = false
            exportMessage = result
            exportProgress = 1f
        }
    }

    // ---------- UI ----------
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Long to Short Clipper", fontSize = 24.sp, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        // Pick Video Button
        Button(onClick = { pickerLauncher.launch(arrayOf("video/*")) }) {
            Text(if (videoUri == null) "1. Video Choose Karo" else "Video Change Karo")
        }
        Spacer(Modifier.height(12.dp))

        // Player + Crop Frame Overlay
        if (videoUri != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { displaySize = it }
            ) {
                // Custom Player - ONLY Play/Pause button
                VideoPlayer(
                    uri = videoUri!!,
                    modifier = Modifier.fillMaxWidth(),
                    onVideoDisplaySize = { scaled -> videoDisplaySize = scaled }
                )

                // Crop Frame Overlay (draggable + resizable)
                if (selectedAspect != null && videoDisplaySize.width > 0) {
                    CropFrameOverlay(
                        offsetX = frameOffsetX,
                        offsetY = frameOffsetY,
                        width = frameWidth,
                        height = frameHeight,
                        maxW = videoDisplaySize.width.toFloat(),
                        maxH = videoDisplaySize.height.toFloat(),
                        onMove = { dx, dy ->
                            frameOffsetX = (frameOffsetX + dx).coerceIn(0f, videoDisplaySize.width - frameWidth)
                            frameOffsetY = (frameOffsetY + dy).coerceIn(0f, videoDisplaySize.height - frameHeight)
                        },
                        onResize = { nw, nh, anchorX, anchorY ->
                            val aspectF = selectedAspect!!.w.toFloat() / selectedAspect!!.h.toFloat()
                            var newW = nw.coerceIn(50f, videoDisplaySize.width.toFloat())
                            var newH = newW / aspectF
                            if (newH > videoDisplaySize.height.toFloat()) {
                                newH = videoDisplaySize.height.toFloat()
                                newW = newH * aspectF
                            }
                            frameWidth = newW
                            frameHeight = newH
                            if (anchorX < 0.5f) frameOffsetX = (frameOffsetX - (newW - nw)).coerceAtLeast(0f)
                            if (anchorY < 0.5f) frameOffsetY = (frameOffsetY - (newH - nh)).coerceAtLeast(0f)
                            frameOffsetX = frameOffsetX.coerceIn(0f, videoDisplaySize.width - newW)
                            frameOffsetY = frameOffsetY.coerceIn(0f, videoDisplaySize.height - newH)
                        }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Time Inputs
        Text("2. Clip Time (HH:MM:SS) — Max 2 minutes", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = startInput,
                onValueChange = { startInput = it; validateTimes() },
                label = { Text("Start") },
                placeholder = { Text("00:00:00") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = endInput,
                onValueChange = { endInput = it; validateTimes() },
                label = { Text("End") },
                placeholder = { Text("00:00:30") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        if (timeError != null) {
            Text(timeError!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))

        // Aspect Ratio Buttons
        Text("3. Aspect Ratio Chuno", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ASPECT_RATIOS.forEach { aspect ->
                FilterChip(
                    selected = selectedAspect == aspect,
                    onClick = {
                        selectedAspect = aspect
                        applyAspectToFrame()
                    },
                    label = {
                        Text("aspect.label\n{aspect.label}\naspect.label\n{aspect.w}x${aspect.h}", fontSize = 11.sp)
                    }
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // Export
        Button(
            onClick = { doExport() },
            enabled = videoUri != null && selectedAspect != null && timeError == null && !exporting
        ) {
            Text("4. Clip Export Karo")
        }
        Spacer(Modifier.height(8.dp))

        if (exporting) {
            LinearProgressIndicator(
                progress = { exportProgress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
        }
        exportMessage?.let {
            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(40.dp))
    }
}

// ---------- Time Parser ----------
fun parseTime(input: String): Int? {
    val parts = input.trim().split(":")
    if (parts.size != 3) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val s = parts[2].toIntOrNull() ?: return null
    if (h < 0 || m !in 0..59 || s !in 0..59) return null
    return h * 3600 + m * 60 + s
}

// ---------- Crop Frame Overlay ----------
@Composable
fun CropFrameOverlay(
    offsetX: Float, offsetY: Float,
    width: Float, height: Float,
    maxW: Float, maxH: Float,
    onMove: (Float, Float) -> Unit,
    onResize: (Float, Float, Float, Float) -> Unit
) {
    val handleSize = 30f

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Border frame - draggable from anywhere on the frame
            .offset(x = with(androidx.compose.ui.platform.LocalDensity) { offsetX.toDp() },
                    y = with(androidx.compose.ui.platform.LocalDensity) { offsetY.toDp() })
            .size(
                width = with(androidx.compose.ui.platform.LocalDensity) { width.toDp() },
                height = with(androidx.compose.ui.platform.LocalDensity) { height.toDp() }
            )
            .pointerInput(width, height) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onMove(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        // Frame border
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                color = Color.White,
                style = Stroke(width = 4f)
            )
            // Grid lines (rule of thirds)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(width / 3, 0f), Offset(width / 3, height), 2f)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(width * 2 / 3, 0f), Offset(width * 2 / 3, height), 2f)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(0f, height / 3), Offset(width, height / 3), 2f)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(0f, height * 2 / 3), Offset(width, height * 2 / 3), 2f)
        }

        // 4 Corner Resize Handles
        listOf(
            Triple(0f, 0f, 0f),          // top-left
            Triple(width - handleSize, 0f, 1f), // top-right
            Triple(0f, height - handleSize, 0f),// bottom-left
            Triple(width - handleSize, height - handleSize, 1f) // bottom-right
        ).forEach { (hx, hy, anchorX) ->
            val anchorY = if (hy <= 0f) 0f else 1f
            Box(
                modifier = Modifier
                    .offset(x = with(LocalDensity.current) { hx.toDp() },
                            y = with(LocalDensity.current) { hy.toDp() })
                    .size(with(LocalDensity.current) { handleSize.toDp() })
                    .background(Color.Yellow)
                    .pointerInput(width, height, anchorX, anchorY) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            // Resize: anchor ke opposite direction me size badhao
                            val dw = if (anchorX > 0.5f) dragAmount.x else -dragAmount.x
                            val dh = if (anchorY > 0.5f) dragAmount.y else -dragAmount.y
                            onResize(width + dw, height + dh, anchorX, anchorY)
                        }
                    }
            )
        }
    }
}
