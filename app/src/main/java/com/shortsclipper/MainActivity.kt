package com.shortsclipper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shortsclipper.ui.EditorScreen
import com.shortsclipper.ui.EditorViewModel
import com.shortsclipper.ui.ExportScreen
import com.shortsclipper.ui.HomeScreen
import com.shortsclipper.ui.theme.BgPrimary
import com.shortsclipper.ui.theme.ShortsClipperTheme

sealed interface Screen {
    data object Home : Screen
    data object Editor : Screen
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
    val nav = remember { mutableStateListOf<Screen>(Screen.Home) }
    fun go(screen: Screen) = nav.add(screen)
    fun back() {
        if (nav.size > 1) nav.removeAt(nav.lastIndex)
    }

    BackHandler(enabled = nav.size > 1) { back() }

    when (nav.last()) {
        Screen.Home -> HomeScreen(
            viewModel = viewModel,
            onOpenEditor = { go(Screen.Editor) },
        )
        Screen.Editor -> EditorScreen(
            viewModel = viewModel,
            onOpenExport = {
                viewModel.prepareExportScreen()
                go(Screen.Export)
            },
            onClose = { back() },
        )
        Screen.Export -> ExportScreen(
            viewModel = viewModel,
            onClose = { back() },
        )
    }
}
