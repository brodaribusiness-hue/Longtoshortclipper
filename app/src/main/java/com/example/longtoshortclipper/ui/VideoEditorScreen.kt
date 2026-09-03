package com.example.longtoshortclipper.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.longtoshortclipper.ffmpeg.FFmpegHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AspectRatio(val label: String, val w: Int, val h: Int)

val ASPECT_RATIOS = listOf(
    AspectRatio("9:16", 1080, 1920),
    AspectRatio("16:9", 1920, 1080),
    AspectRatio("1:1", 1080, 1080)
)

fun parseTime(input: String): Int? {
    val parts = input.trim().split(":")
    if (parts.size != 3) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val s = parts[2].toIntOrNull() ?: return null
    if (h < 0 || m !in 0..59 || s !in 0..59) return null
    return h * 3600 + m * 60 + s
}

@Composable
fun VideoEditorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var cachedFile by remember { mutableStateOf<File?>(null) }
    var selectedAspect by remember { mutableStateOf<AspectRatio?>(null) }

    var startInput by remember { mutableStateOf("00:00:00") }
    var endInput by remember { mutableStateOf("00:00:30") }
    var timeError by remember { mutableStateOf<String?>(null) }

    var busy by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0f) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    var frameOffsetX by remember { mutableStateOf(0f) }
    var frameOffsetY by remember { mutableStateOf(0f) }
    var frameWidth by remember { mutableStateOf(300f) }
    var frameHeight by remember { mutableStateOf(300f) }

    var videoDisplaySize by remember { mutableStateOf(IntSize.Zero) }
    var videoPixelSize by remember { mutableStateOf(IntSize.Zero) }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                busy = true
                exportMessage = "Video cache me copy ho raha hai..."
                withContext(Dispatchers.IO) {
                    val f = File(context.cacheDir, "input_video_${System.currentTimeMillis()}.mp4")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { output -> input.copyTo(output) }
                    }
                    cachedFile = f
                }
                withContext(Dispatchers.Default) {
                    val dims = FFmpegHelper.getVideoDimensions(context, uri)
                    videoPixelSize = IntSize(dims.first, dims.second)
                }
                videoUri = uri
                busy = false
                exportMessage = null
            }
        }
    }

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

    fun applyAspectToFrame() {
        val aspect = selectedAspect ?: return
        val vw = videoDisplaySize.width.toFloat()
        val vh = videoDisplaySize.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
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

    fun doExport() {
        validateTimes()
        val file = cachedFile
        val aspect = selectedAspect
        if (timeError != null) return
        if (file == null || aspect == null) {
            exportMessage = "Pehle video select karo aur aspect ratio choose karo"
            return
        }
        if (videoPixelSize.width <= 0 || videoDisplaySize.width <= 0) return

        busy = true
        exportProgress = 0f
        exportMessage = "Export ho raha hai..."
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                val scale = videoPixelSize.width.toFloat() / videoDisplaySize.width.toFloat()

                val cropW = (frameWidth * scale).toInt().coerceIn(2, videoPixelSize.width)
                val cropH = (frameHeight * scale).toInt().coerceIn(2, videoPixelSize.height)
                val evenW = cropW - (cropW % 2)
                val evenH = cropH - (cropH % 2)
                val cropX = (frameOffsetX * scale).toInt().coerceIn(0, videoPixelSize.width - evenW)
                val cropY = (frameOffsetY * scale).toInt().coerceIn(0, videoPixelSize.height - evenH)

                FFmpegHelper.exportClipWithProgress(
                    inputFile = file,
                    outputDir = File(
                        context.getExternalFilesDir(null) ?: context.filesDir,
                        "exports"
                    ).apply { mkdirs() },
                    startSeconds = parseTime(startInput)!!,
                    endSeconds = parseTime(endInput)!!,
                    cropW = evenW, cropH = evenH, cropX = cropX, cropY = cropY
                ) { p -> exportProgress = p }
            }
            busy = false
            exportMessage = result
            exportProgress = 1f
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Long to Short Clipper", fontSize = 24.sp, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        Button(onClick = { pickerLauncher.launch(arrayOf("video/*")) }, enabled = !busy) {
            Text(if (videoUri == null) "1. Video Choose Karo" else "Video Change Karo")
        }
        Spacer(Modifier.height(12.dp))

        if (videoUri != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            ) {
                VideoPlayer(
                    uri = videoUri!!,
                    modifier = Modifier.fillMaxSize(),
                    onVideoDisplaySize = { scaled -> videoDisplaySize = scaled }
                )

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
                        onResize = { nw, _, anchorX, anchorY ->
                            val aspectF = selectedAspect!!.w.toFloat() / selectedAspect!!.h.toFloat()
                            var newW = nw.coerceIn(50f, videoDisplaySize.width.toFloat())
                            var newH = newW / aspectF
                            if (newH > videoDisplaySize.height.toFloat()) {
                                newH = videoDisplaySize.height.toFloat()
                                newW = newH * aspectF
                            }
                            val oldW = frameWidth
                            val oldH = frameHeight
                            frameWidth = newW
                            frameHeight = newH
                            if (anchorX < 0.5f) frameOffsetX += (oldW - newW)
                            if (anchorY < 0.5f) frameOffsetY += (oldH - newH)
                            frameOffsetX = frameOffsetX.coerceIn(0f, videoDisplaySize.width - newW)
                            frameOffsetY = frameOffsetY.coerceIn(0f, videoDisplaySize.height - newH)
                        }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

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
                        Column {
                            Text(aspect.label, fontSize = 14.sp)
                            Text("aspect.wx{aspect.w}xaspect.wx{aspect.h}", fontSize = 10.sp)
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { doExport() },
            enabled = videoUri != null && selectedAspect != null && timeError == null && !busy
        ) {
            Text("4. Clip Export Karo")
        }
        Spacer(Modifier.height(8.dp))

        if (busy) {
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
            .offset(x = with(LocalDensity.current) { offsetX.toDp() },
                    y = with(LocalDensity.current) { offsetY.toDp() })
            .size(
                width = with(LocalDensity.current) { width.toDp() },
                height = with(LocalDensity.current) { height.toDp() }
            )
            .pointerInput(width, height) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onMove(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = Color.White, style = Stroke(width = 4f))
            drawLine(Color.White.copy(alpha = 0.5f), Offset(width / 3, 0f), Offset(width / 3, height), 2f)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(width * 2 / 3, 0f), Offset(width * 2 / 3, height), 2f)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(0f, height / 3), Offset(width, height / 3), 2f)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(0f, height * 2 / 3), Offset(width, height * 2 / 3), 2f)
        }

        val corners = listOf(
            Pair(0f, 0f),
            Pair(width - handleSize, 0f),
            Pair(0f, height - handleSize),
            Pair(width - handleSize, height - handleSize)
        )
        corners.forEach { (hx, hy) ->
            val anchorX = if (hx <= 0f) 0f else 1f
            val anchorY = if (hy <= 0f) 0f else 1f
            Box(
                modifier = Modifier
                    .offset(x = with(LocalDensity.current) { hx.toDp() },
                            y = with(LocalDensity.current) { hy.toDp() })
                    .size(with(LocalDensity.current) { handleSize.toDp() })
                    .background(Color.Yellow, CircleShape)
                    .pointerInput(width, height, anchorX, anchorY) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val dw = if (anchorX > 0.5f) dragAmount.x else -dragAmount.x
                            val dh = if (anchorY > 0.5f) dragAmount.y else -dragAmount.y
                            onResize(width + dw, height + dh, anchorX, anchorY)
                        }
                    }
            )
        }
    }
}
