package com.shortsclipper

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shortsclipper.ai.WhisperNative
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Minimal on-device coverage for the paths a JVM test cannot exercise: APK
 * startup/Compose composition and dynamic loading of the packaged JNI library.
 * It intentionally does not need a model download, account, network, or media
 * fixture, so it remains deterministic and fully offline.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesToTheOfflineImportScreen() {
        // Compose content does not create Android TextView instances, so use
        // Compose semantics rather than Espresso's View matchers.
        composeRule.onNodeWithText("Import Long Video").assertIsDisplayed()
    }

    @Test
    fun packagedWhisperLibraryLoadsOnTheDeviceAbi() {
        assertTrue(
            "Whisper JNI library failed to load: ${WhisperNative.unavailableReason}",
            WhisperNative.isAvailable,
        )
    }
}
