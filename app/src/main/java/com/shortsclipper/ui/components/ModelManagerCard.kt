package com.shortsclipper.ui.components

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortsclipper.ai.ModelInfo
import com.shortsclipper.ai.ModelManager
import com.shortsclipper.ui.EditorViewModel
import com.shortsclipper.ui.theme.Accent
import com.shortsclipper.ui.theme.BgSecondary
import com.shortsclipper.ui.theme.Error
import com.shortsclipper.ui.theme.Success
import com.shortsclipper.ui.theme.TextPrimary
import com.shortsclipper.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit, cancellable management for official and locally imported Whisper
 * GGML models. The callback passed to ModelManager is tied to a real Job and
 * cancellation signal; it is never a permanently-false placeholder.
 */
@Composable
fun ModelManagerCard(viewModel: EditorViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val preferred by viewModel.preferredModel.collectAsState()
    val progressFlow = remember { MutableStateFlow(0f) }
    val progress by progressFlow.collectAsState()
    var activeOperationId by remember { mutableStateOf<String?>(null) }
    var activeLabel by remember { mutableStateOf<String?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var cancelSignal by remember { mutableStateOf<AtomicBoolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    fun cancelActiveOperation() {
        cancelSignal?.set(true)
        activeJob?.cancel()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || activeOperationId != null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
        } ?: "imported-model.bin"
        val signal = AtomicBoolean(false)
        cancelSignal = signal
        activeOperationId = "import"
        activeLabel = "Importing $displayName"
        progressFlow.value = 0f
        error = null
        activeJob = scope.launch {
            try {
                val imported = viewModel.modelManager.importFromFile(
                    uri = uri,
                    displayName = displayName,
                    onProgress = { bytes -> progressFlow.value = (bytes / (1024f * 1024f)).coerceAtLeast(0f) },
                    isCancelled = { signal.get() },
                )
                viewModel.preferredModel.value = imported
            } catch (t: Throwable) {
                if (!signal.get()) error = t.message ?: "Model import failed"
            } finally {
                activeOperationId = null
                activeLabel = null
                activeJob = null
                cancelSignal = null
                refresh++
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BgSecondary),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Transcription models", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Speech-to-text runs fully on-device. Official downloads are verified before installation; you can also import a compatible local GGML file.",
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )

            key(refresh) {
                val models = viewModel.modelManager.availableModels()
                for (model in models) {
                    key(model.id) {
                        // Header/vocabulary inspection may seek through a large
                        // file, so never perform it during Compose layout.
                        val installed by produceState(false, model.id, refresh) {
                            value = withContext(Dispatchers.IO) { viewModel.modelManager.isInstalled(model) }
                        }
                        ModelRow(
                            model = model,
                            installed = installed,
                            isPreferred = preferred.id == model.id,
                            isActive = activeOperationId == model.id,
                            progress = progress,
                            onUse = { viewModel.preferredModel.value = model },
                            onDelete = {
                                scope.launch {
                                    if (preferred.id == model.id) {
                                        val fallback = withContext(Dispatchers.IO) {
                                            viewModel.modelManager.availableModels()
                                                .firstOrNull { it.id != model.id && viewModel.modelManager.isInstalled(it) }
                                        }
                                        viewModel.preferredModel.value = fallback ?: ModelManager.CATALOG[1]
                                    }
                                    viewModel.modelManager.delete(model)
                                    refresh++
                                }
                            },
                            onDownload = if (model.imported) null else {
                                {
                                    if (activeOperationId == null) {
                                        val signal = AtomicBoolean(false)
                                        cancelSignal = signal
                                        activeOperationId = model.id
                                        activeLabel = "Downloading ${model.label}"
                                        progressFlow.value = 0f
                                        error = null
                                        activeJob = scope.launch {
                                            try {
                                                viewModel.modelManager.download(
                                                    info = model,
                                                    onProgress = { downloaded, total ->
                                                        progressFlow.value = if (total > 0L) downloaded.toFloat() / total else 0f
                                                    },
                                                    isCancelled = { signal.get() },
                                                )
                                                val preferredInstalled = withContext(Dispatchers.IO) {
                                                    viewModel.modelManager.isInstalled(preferred)
                                                }
                                                if (!preferredInstalled || preferred.approxSizeMB > model.approxSizeMB) {
                                                    viewModel.preferredModel.value = model
                                                }
                                            } catch (t: Throwable) {
                                                if (!signal.get()) error = t.message ?: "Download failed"
                                            } finally {
                                                activeOperationId = null
                                                activeLabel = null
                                                activeJob = null
                                                cancelSignal = null
                                                refresh++
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }

            if (activeOperationId != null) {
                Text(activeLabel.orEmpty(), color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                if (activeOperationId != "import") {
                    LinearProgressIndicator(
                        progress = progress.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                        color = Accent,
                    )
                }
                TextButton(onClick = ::cancelActiveOperation) {
                    Text("Cancel", color = TextSecondary, fontSize = 12.sp)
                }
            } else {
                TextButton(onClick = { importLauncher.launch(arrayOf("application/octet-stream", "application/bin", "*/*")) }) {
                    Text("Import local GGML model", color = Accent, fontSize = 12.sp)
                }
            }

            if (error != null) Text(error!!, color = Error, fontSize = 11.sp)
            Text(
                "Free space: ${viewModel.modelManager.freeSpaceMB()} MB",
                color = TextSecondary,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ModelRow(
    model: ModelInfo,
    installed: Boolean,
    isPreferred: Boolean,
    isActive: Boolean,
    progress: Float,
    onUse: () -> Unit,
    onDelete: () -> Unit,
    onDownload: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (installed) "~${model.approxSizeMB} MB installed" else "~${model.approxSizeMB} MB",
                    color = if (installed) Success else TextSecondary,
                    fontSize = 11.sp,
                )
                if (isPreferred) {
                    Spacer(Modifier.width(6.dp))
                    Text("ACTIVE", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(model.description, color = TextSecondary, fontSize = 10.sp)
        }
        when {
            isActive -> LinearProgressIndicator(
                progress = progress.coerceIn(0f, 1f),
                modifier = Modifier.width(72.dp),
                color = Accent,
            )
            installed -> {
                if (!isPreferred) TextButton(onClick = onUse) { Text("Use", color = Accent, fontSize = 12.sp) }
                TextButton(onClick = onDelete) { Text("Delete", color = TextSecondary, fontSize = 12.sp) }
            }
            onDownload != null -> Button(
                onClick = onDownload,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
            ) { Text("Download", fontSize = 12.sp) }
        }
    }
}

