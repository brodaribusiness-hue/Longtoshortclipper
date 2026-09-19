package com.shortsclipper.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortsclipper.model.ClipCandidate
import com.shortsclipper.ui.theme.Accent
import com.shortsclipper.ui.theme.BgControl
import com.shortsclipper.ui.theme.BgSecondary
import com.shortsclipper.ui.theme.Success
import com.shortsclipper.ui.theme.TextPrimary
import com.shortsclipper.ui.theme.TextSecondary
import com.shortsclipper.video.VideoManager

/**
 * Candidate clip card: thumbnail, duration, range, Content Potential Score,
 * primary reason and actions (preview / select / reject).
 */
@Composable
fun ClipCard(
    candidate: ClipCandidate,
    videoUri: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, candidate.id) {
        value = VideoManager.extractFrame(context, android.net.Uri.parse(videoUri), candidate.startMs + 200, maxDim = 360, accurate = false)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Accent.copy(alpha = 0.12f) else BgSecondary,
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(76.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgControl),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = thumbnail
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(76.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(formatTime(candidate.durationMs), color = TextPrimary, fontSize = 10.sp)
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${formatTime(candidate.startMs)} → ${formatTime(candidate.endMs)}",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .background(
                                if (candidate.potential >= 6f) Success.copy(alpha = 0.18f) else Accent.copy(alpha = 0.18f),
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            String.format("%.1f", candidate.potential),
                            color = if (candidate.potential >= 6f) Success else Accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    candidate.title,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    candidate.reasons.firstOrNull() ?: "",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onSelect,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text("Select", fontSize = 12.sp)
                    }
                    TextButton(onClick = onPreview, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                        Text("Preview", color = TextPrimary, fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onReject, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                        Text("Reject", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
