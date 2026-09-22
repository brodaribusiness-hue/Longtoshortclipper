package com.shortsclipper

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.shortsclipper.ai.WhisperNative
import org.junit.Assert.assertTrue
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

    @Test
    fun appLaunchesToTheOfflineImportScreen() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            onView(withText("Import Long Video")).check(matches(isDisplayed()))
        } finally {
            scenario.close()
        }
    }

    @Test
    fun packagedWhisperLibraryLoadsOnTheDeviceAbi() {
        assertTrue(
            "Whisper JNI library failed to load: ${WhisperNative.unavailableReason}",
            WhisperNative.isAvailable,
        )
    }
}
