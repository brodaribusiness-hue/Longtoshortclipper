package com.example.longtoshortclipper.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.roundToInt

@Composable
fun VideoPlayer(
    uri: Uri,
    modifier: Modifier = Modifier,
    onVideoDisplaySize: (IntSize) -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = false
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var videoAspectRatio by remember { mutableStateOf(16f / 9f) }

    DisposableEffect(uri) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onVideoSizeChanged(width: Int, height: Int, unapplied: Int, par: Float) {
                if (width > 0 && height > 0) videoAspectRatio = width.toFloat() / height.toFloat()
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Column(modifier = modifier.onSizeChanged { containerSize = it }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false          // ❌ NO seekbar, NO controls
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        // Letterbox calc: real video display area nikaalo
                        val cw = size.width.toFloat()
                        val ch = size.height.toFloat()
                        if (cw > 0 && ch > 0) {
                            val scaledH = cw / videoAspectRatio
                            val scaledW = if (scaledH <= ch) cw else ch * videoAspectRatio
                            val scaledHeightFinal = if (scaledH <= ch) scaledH else ch
                            onVideoDisplaySize(
                                IntSize(scaledW.roundToInt(), scaledHeightFinal.roundToInt())
                            )
                        }
                    }
            )

            // ✅ ONLY Play/Pause button (centered)
            IconButton(
                onClick = {
                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.Play,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
    }
}
