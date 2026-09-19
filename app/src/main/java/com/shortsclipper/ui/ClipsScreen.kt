package com.shortsclipper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortsclipper.model.TargetDuration
import com.shortsclipper.ui.components.ClipCard
import com.shortsclipper.ui.theme.Accent
import com.shortsclipper.ui.theme.BgPrimary
import com.shortsclipper.ui.theme.TextPrimary
import com.shortsclipper.ui.theme.TextSecondary

/**
 * Candidate clips list with target duration chips, scores, reasons and
 * preview/select/reject actions. AI suggestions never overwrite manual edits:
 * selecting a candidate simply applies its range to the timeline.
 */
@Composable
fun ClipsScreen(
    viewModel: EditorViewModel,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Potential Clips", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Done", color = Accent, fontSize = 13.sp) }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (target in TargetDuration.entries) {
                FilterChip(
                    selected = state.targetDuration == target,
                    onClick = { viewModel.setTargetDuration(target) },
                    label = {
                        Text(
                            if (target == TargetDuration.AUTO) "Auto" else "${target.seconds}s",
                            fontSize = 12.sp,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Accent,
                        selectedLabelColor = Color.Black,
                        labelColor = TextPrimary,
                        containerColor = BgPrimary,
                    ),
                )
            }
        }
        Text(
            "Content Potential Score ranks local content signals (hook, context, engagement, completeness, density). It is not a viral prediction.",
            color = TextSecondary,
            fontSize = 10.sp,
            modifier = Modifier.padding(vertical = 6.dp),
        )

        val candidates = state.visibleCandidates
        if (candidates.isEmpty()) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (state.transcript == null) "Run AI Analysis first to generate candidate clips."
                    else "No candidates for this duration. Try another target.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                if (state.transcript == null) {
                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Text("Open AI Analysis", fontSize = 13.sp)
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(candidates, key = { it.id }) { candidate ->
                    ClipCard(
                        candidate = candidate,
                        videoUri = state.source?.uri ?: "",
                        isSelected = state.selectedCandidateId == candidate.id,
                        onSelect = {
                            viewModel.selectCandidate(candidate.id)
                            onClose()
                        },
                        onPreview = {
                            viewModel.selectCandidate(candidate.id)
                            viewModel.play()
                        },
                        onReject = { viewModel.rejectCandidate(candidate.id) },
                    )
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.regenerateCandidates() },
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BgPrimary, contentColor = TextPrimary),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Regenerate", fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
