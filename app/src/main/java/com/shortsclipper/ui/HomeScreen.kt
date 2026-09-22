package com.shortsclipper.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.shortsclipper.ui.components.formatTime
import com.shortsclipper.ui.theme.Accent
import com.shortsclipper.ui.theme.BgControl
import com.shortsclipper.ui.theme.BgSecondary
import com.shortsclipper.ui.theme.Error
import com.shortsclipper.ui.theme.TextPrimary
import com.shortsclipper.ui.theme.TextSecondary

/** Home: video import and recent projects. */
@Composable
fun HomeScreen(
    viewModel: EditorViewModel,
    onOpenEditor: () -> Unit,
) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    val projects by androidx.compose.runtime.produceState(initialValue = viewModel.recentProjects()) {
        value = viewModel.recentProjects()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            var name = "video"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) name = cursor.getString(idx) ?: name
            }
            viewModel.importVideo(uri, name) { ok, error ->
                message = error
                if (ok) onOpenEditor()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(38.dp).background(Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("SC", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("ShortsClipper", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Long videos → perfectly framed shorts, 100% on-device", color = TextSecondary, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { importLauncher.launch(arrayOf("video/*")) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black)
            Spacer(Modifier.width(8.dp))
            Text("Import Long Video", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }

        if (message != null) {
            Text(message!!, color = Error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(24.dp))
        Text("Recent projects", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        if (projects.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSecondary, RoundedCornerShape(12.dp))
                    .padding(18.dp),
            ) {
                Text("No projects yet. Import a long video to start.", color = TextSecondary, fontSize = 12.sp)
            }
        } else {
            for (project in projects) {
                val source = project.source
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(BgSecondary, RoundedCornerShape(12.dp))
                        .clickable {
                            if (viewModel.loadProject(project.id)) onOpenEditor()
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(project.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            buildString {
                                if (source != null) append(formatTime(source.durationMs))
                                if (source != null && source.hasAudio) append("  ·  audio")
                                append("  ·  ")
                                append(java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(project.updatedAtMs)))
                            },
                            color = TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    IconButton(onClick = {
                        viewModel.deleteProject(project.id)
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Delete", tint = TextSecondary)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Private by design: videos and exports stay on your device. Editing, reframing, face tracking and silence processing run locally.",
            color = TextSecondary,
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(40.dp))
    }
}
