package com.shortsclipper.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortsclipper.ai.ModelManager
import com.shortsclipper.ui.EditorViewModel
import com.shortsclipper.ui.theme.Accent
import com.shortsclipper.ui.theme.BgSecondary
import com.shortsclipper.ui.theme.Error
import com.shortsclipper.ui.theme.Success
import com.shortsclipper.ui.theme.TextPrimary
import com.shortsclipper.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Whisper model manager: explicit, user-controlled downloads from the
 * official open-source whisper.cpp model repository, or local import.
 */
@Composable
fun ModelManagerCard(viewModel: EditorViewModel, modifier: Modifier = Modifier) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var downloading by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BgSecondary),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Transcription models", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Speech-to-text runs fully on-device. Download a model once (official whisper.cpp models); after that no internet is needed.",
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )

            key(refresh) {
                for (model in ModelManager.CATALOG) {
                    val installed = viewModel.modelManager.isInstalled(model)
                    val isPreferred = viewModel.preferredModel.collectAsState().value.id == model.id
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(model.label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.width(6.dp))
                                if (installed) {
                                    Text("~${viewModel.modelManager.installedSizeMB(model)} MB installed", color = Success, fontSize = 11.sp)
                                } else {
                                    Text("~${model.approxSizeMB} MB", color = TextSecondary, fontSize = 11.sp)
                                }
                                if (isPreferred) {
                                    Spacer(Modifier.width(6.dp))
                                    Text("ACTIVE", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Text(model.description, color = TextSecondary, fontSize = 10.sp)
                        }
                        if (downloading == model.id) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.width(80.dp),
                                color = Accent,
                            )
                        } else if (installed) {
                            if (!isPreferred) {
                                TextButton(onClick = { viewModel.preferredModel.value = model }) {
                                    Text("Use", color = Accent, fontSize = 12.sp)
                                }
                            }
                            TextButton(onClick = {
                                viewModel.modelManager.delete(model)
                                refresh++
                            }) {
                                Text("Delete", color = TextSecondary, fontSize = 12.sp)
                            }
                        } else {
                            Button(
                                onClick = {
                                    downloading = model.id
                                    progress = 0f
                                    error = null
                                    scope.launch {
                                        try {
                                            viewModel.modelManager.download(
                                                model,
                                                onProgress = { downloaded, total ->
                                                    progress = if (total > 0) downloaded.toFloat() / total else 0.5f
                                                },
                                                isCancelled = { false },
                                            )
                                            if (viewModel.preferredModel.value.approxSizeMB > model.approxSizeMB || !viewModel.modelManager.isInstalled(viewModel.preferredModel.value)) {
                                                viewModel.preferredModel.value = model
                                            }
                                        } catch (t: Throwable) {
                                            error = t.message ?: "Download failed"
                                        } finally {
                                            downloading = null
                                            refresh++
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                            ) {
                                Text("Download", fontSize = 12.sp)
                            }
                        }
                    }
                    if (downloading == model.id && progress > 0f) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            color = Accent,
                        )
                    }
                }
            }

            if (error != null) {
                Text(error!!, color = Error, fontSize = 11.sp)
            }
            Text(
                "Free space: ${viewModel.modelManager.freeSpaceMB()} MB",
                color = TextSecondary,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun key(refresh: Int, content: @Composable () -> Unit) {
    androidx.compose.runtime.saveable.rememberSaveable(refresh) { 0 }
    content()
}
