package com.shortsclipper.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortsclipper.model.AnalysisPhase
import com.shortsclipper.model.AnalysisStep
import com.shortsclipper.model.StepState
import com.shortsclipper.ui.components.ModelManagerCard
import com.shortsclipper.ui.theme.Accent
import com.shortsclipper.ui.theme.BgPrimary
import com.shortsclipper.ui.theme.BgSecondary
import com.shortsclipper.ui.theme.Error
import com.shortsclipper.ui.theme.Success
import com.shortsclipper.ui.theme.TextPrimary
import com.shortsclipper.ui.theme.TextSecondary

/**
 * AI analysis pipeline progress: real per-step status with honest progress,
 * cancellable at any point. Everything runs locally on-device.
 */
@Composable
fun AnalysisScreen(
    viewModel: EditorViewModel,
    onOpenClips: () -> Unit,
    onClose: () -> Unit,
) {
    val analysis by viewModel.analysisState.collectAsState()
    val state by viewModel.state.collectAsState()
    val isModelInstalled = viewModel.isModelReady()

    // Auto-start when opened without an active run, provided a model is ready.
    LaunchedEffect(state.id, isModelInstalled) {
        if (isModelInstalled && !analysis.isRunning && analysis.phase != AnalysisPhase.DONE) {
            viewModel.runAnalysis()
        }
    }

    if (!isModelInstalled) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text("Analyze Video", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Speech recognition requires an on-device Whisper model. Download an official open-source model once (~78 MB Tiny, ~148 MB Base) or import a local .bin model to enable AI clipping.",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            ModelManagerCard(viewModel)
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("Back to editor", color = TextSecondary, fontSize = 12.sp)
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("Analyze Video", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Local transcription (whisper.cpp) + deterministic content analysis. Long videos can take several minutes.",
            color = TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 18.dp),
        )

        for (step in AnalysisStep.entries) {
            val stepState = analysis.stepStates[step] ?: StepState.PENDING
            val progress = analysis.stepProgress[step]
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(22.dp).background(
                        when (stepState) {
                            StepState.DONE -> Success
                            StepState.RUNNING -> Accent
                            StepState.FAILED -> Error
                            StepState.PENDING -> BgSecondary
                        },
                        CircleShape,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (stepState) {
                            StepState.DONE -> "✓"
                            StepState.FAILED -> "!"
                            else -> ""
                        },
                        color = Color.Black,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        step.label,
                        color = if (stepState == StepState.PENDING) TextSecondary else TextPrimary,
                        fontSize = 13.sp,
                    )
                    if (stepState == StepState.RUNNING && progress != null && progress > 0f) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp),
                            color = Accent,
                        )
                    }
                }
                if (stepState == StepState.RUNNING && progress != null && progress > 0f) {
                    Text("${(progress * 100).toInt()}%", color = TextSecondary, fontSize = 11.sp)
                }
            }
        }

        if (analysis.error != null) {
            Text(
                analysis.error!!,
                color = Error,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 12.dp).background(BgSecondary, RoundedCornerShape(8.dp)).padding(10.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        if (analysis.phase == AnalysisPhase.DONE) {
            Button(
                onClick = onOpenClips,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
            ) {
                Text("View potential clips", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        } else if (analysis.phase == AnalysisPhase.FAILED) {
            Button(
                onClick = { viewModel.runAnalysis() },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
            ) {
                Text("Retry analysis", fontSize = 14.sp)
            }
        } else if (analysis.isRunning) {
            Button(
                onClick = { viewModel.cancelAnalysis() },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BgSecondary, contentColor = TextPrimary),
            ) {
                Text("Cancel analysis", fontSize = 14.sp)
            }
        }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Back to editor", color = TextSecondary, fontSize = 12.sp)
        }
    }
}
