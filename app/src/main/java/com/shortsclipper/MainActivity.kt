package com.shortsclipper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shortsclipper.ui.AnalysisScreen
import com.shortsclipper.ui.ClipsScreen
import com.shortsclipper.ui.EditorScreen
import com.shortsclipper.ui.EditorViewModel
import com.shortsclipper.ui.ExportScreen
import com.shortsclipper.ui.HomeScreen
import com.shortsclipper.ui.theme.BgPrimary
import com.shortsclipper.ui.theme.ShortsClipperTheme

sealed interface Screen {
    data object Home : Screen
    data object Editor : Screen
    data object Analysis : Screen
    data object Clips : Screen
    data object Export : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShortsClipperTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = BgPrimary) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun AppRoot(viewModel: EditorViewModel = viewModel()) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.onEditorBackgrounded()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val nav = remember { mutableStateListOf<Screen>(Screen.Home) }
    fun go(screen: Screen) = nav.add(screen)
    fun back() {
        if (nav.size > 1) nav.removeAt(nav.lastIndex)
    }
    fun returnToEditor() {
        while (nav.size > 1 && nav.last() != Screen.Editor) nav.removeAt(nav.lastIndex)
    }

    BackHandler(enabled = nav.size > 1) {
        when (nav.last()) {
            Screen.Editor -> viewModel.onEditorBackgrounded()
            Screen.Analysis -> viewModel.cancelAnalysis()
            Screen.Export -> viewModel.cancelExport()
            else -> Unit
        }
        back()
    }

    when (nav.last()) {
        Screen.Home -> HomeScreen(
            viewModel = viewModel,
            onOpenEditor = { go(Screen.Editor) },
        )
        Screen.Editor -> EditorScreen(
            viewModel = viewModel,
            onOpenAnalysis = { viewModel.onEditorBackgrounded(); go(Screen.Analysis) },
            onOpenClips = { viewModel.onEditorBackgrounded(); go(Screen.Clips) },
            onOpenExport = { viewModel.onEditorBackgrounded(); go(Screen.Export) },
            onClose = { viewModel.onEditorBackgrounded(); back() },
        )
        Screen.Analysis -> AnalysisScreen(
            viewModel = viewModel,
            onOpenClips = { go(Screen.Clips) },
            onClose = { viewModel.cancelAnalysis(); back() },
        )
        Screen.Clips -> ClipsScreen(
            viewModel = viewModel,
            onOpenAnalysis = { returnToEditor(); go(Screen.Analysis) },
            onReturnToEditor = { returnToEditor() },
            onClose = { back() },
        )
        Screen.Export -> ExportScreen(
            viewModel = viewModel,
            onClose = { back() },
        )
    }
}
