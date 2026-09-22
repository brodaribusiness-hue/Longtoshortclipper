package com.shortsclipper.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortsclipper.model.QualityMode
import com.shortsclipper.ui.components.formatTime
import com.shortsclipper.ui.theme.Accent
import com.shortsclipper.ui.theme.BgSecondary
import com.shortsclipper.ui.theme.Error
import com.shortsclipper.ui.theme.Success
import com.shortsclipper.ui.theme.TextPrimary
import com.shortsclipper.ui.theme.TextSecondary

/**
 * Export screen: device-aware quality options, honest progress, cancel,
 * and gallery save via MediaStore.
 */
@Composable
fun ExportScreen(
    viewModel: EditorViewModel,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val context = LocalContext.current
    var permissionMessage by remember { mutableStateOf<String?>(null) }

    // Legacy devices (< Android 10) need WRITE_EXTERNAL_STORAGE for the gallery save.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            permissionMessage = null
            viewModel.startExport()
        } else {
            permissionMessage = "Storage permission is required to save exports on Android 9 and below."
        }
    }
    fun beginExport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            permissionMessage = null
            viewModel.startExport()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Export", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (!exportState.isBusy) {
                TextButton(onClick = onClose) { Text("Close", color = Accent, fontSize = 13.sp) }
            }
        }
        Text(
            "Clip: ${formatTime(state.timeline.selectionStartMs)} → ${formatTime(state.timeline.selectionEndMs)}" +
                if (state.silenceRemovals.isNotEmpty()) " · edited ${formatTime(state.editedDurationMs)}" else "",
            color = TextSecondary,
            fontSize = 12.sp,
        )

        if (permissionMessage != null) {
            Text(permissionMessage!!, color = Error, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }

        Spacer(Modifier.height(18.dp))
        Text("Quality", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            RadioButton(
                selected = state.exportQuality == QualityMode.SAME_AS_ORIGINAL,
                onClick = { viewModel.setExportQuality(QualityMode.SAME_AS_ORIGINAL) },
                enabled = !exportState.isBusy,
                colors = RadioButtonDefaults.colors(selectedColor = Accent),
            )
            Column {
                Text("Same as Original", color = TextPrimary, fontSize = 13.sp)
                Text("Uses source-detail resolution when supported; exports AVC for broad playback", color = TextSecondary, fontSize = 10.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = state.exportQuality == QualityMode.HIGH_QUALITY,
                onClick = { viewModel.setExportQuality(QualityMode.HIGH_QUALITY) },
                enabled = !exportState.isBusy,
                colors = RadioButtonDefaults.colors(selectedColor = Accent),
            )
            Column {
                Text("High Quality", color = TextPrimary, fontSize = 13.sp)
                Text("Caps at 1080×1920-class output for reliable quality", color = TextSecondary, fontSize = 10.sp)
            }
        }
        Text(
            "The 9:16 reframing is re-encoded (crop applied per frame), so output quality is \"as good as practical\", not bit-for-bit identical to the source.",
            color = TextSecondary,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(20.dp))

        when (exportState.phase) {
            com.shortsclipper.model.ExportPhase.DONE -> {
                Text("✓ Export complete", color = Success, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    exportState.message ?: "Saved to your gallery",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "Took ${formatTime(exportState.elapsedMs)}",
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
                Button(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth().height(46.dp).padding(top = 16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                ) {
                    Text("Back to editor", fontSize = 14.sp)
                }
            }
            com.shortsclipper.model.ExportPhase.FAILED -> {
                Text("Export failed", color = Error, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    exportState.message ?: "Unknown error",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(
                    onClick = {
                        beginExport()
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp).padding(top = 16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                ) {
                    Text("Try again", fontSize = 14.sp)
                }
            }
            com.shortsclipper.model.ExportPhase.CANCELLED -> {
                Text("Export cancelled", color = TextSecondary, fontSize = 14.sp)
                Button(
                    onClick = {
                        beginExport()
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp).padding(top = 16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                ) {
                    Text("Start export", fontSize = 14.sp)
                }
            }
            com.shortsclipper.model.ExportPhase.IDLE -> {
                Button(
                    onClick = {
                        beginExport()
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Export to Gallery", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            else -> {
                Text(
                    when (exportState.phase) {
                        com.shortsclipper.model.ExportPhase.PREPARING -> "Preparing…"
                        com.shortsclipper.model.ExportPhase.SAVING -> "Saving to gallery…"
                        else -> "Transforming… ${exportState.progressPercent}%"
                    },
                    color = TextPrimary,
                    fontSize = 14.sp,
                )
                LinearProgressIndicator(
                    progress = (exportState.progressPercent / 100f).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    color = Accent,
                )
                Text(
                    "Elapsed ${formatTime(exportState.elapsedMs)} · 9:16 crop + tracking applied",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Button(
                    onClick = { viewModel.cancelExport() },
                    modifier = Modifier.fillMaxWidth().height(46.dp).padding(top = 16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BgSecondary, contentColor = TextPrimary),
                ) {
                    Text("Cancel export", fontSize = 14.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Output is saved through Android's MediaStore and appears in your Gallery under Movies/ShortsClipper.",
            color = TextSecondary,
            fontSize = 10.sp,
        )
    }
}
