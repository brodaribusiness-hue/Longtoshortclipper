package com.shortsclipper

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Minimal on-device coverage for the path a JVM test cannot exercise: APK
 * startup and Compose composition. It intentionally does not need an account,
 * network, or media fixture, so it remains deterministic and fully offline.
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
}
