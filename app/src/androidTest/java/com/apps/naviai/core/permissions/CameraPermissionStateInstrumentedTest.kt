package com.apps.naviai.core.permissions

import android.Manifest
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CameraPermissionStateInstrumentedTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reflectsAlreadyGrantedPermissionOnFirstComposition() {
        var observedGranted = false

        composeRule.setContent {
            val state = rememberCameraPermissionState()
            observedGranted = state.isGranted
            Text(if (state.isGranted) "granted" else "not granted")
        }

        composeRule.onNodeWithText("granted").assertExists()
        assertEquals(true, observedGranted)
    }
}
