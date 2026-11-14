package com.everyonesoftware.hermespocket

import android.graphics.Bitmap
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Real Activity + ViewModel + controller flow, not just stateless UI callbacks. */
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun offlineToDemoConversationAndActivitySurvivesRotation() {
        compose.onNodeWithTag("mode-badge").assertTextEquals("OFFLINE")
        compose.onNodeWithText("Explore demo").performScrollTo().performClick()
        compose.onNodeWithTag("mode-badge").assertTextEquals("DEMO")
        compose.onNodeWithText("Home server check-in").performScrollTo().performClick()
        compose.onNodeWithTag("screen-context").assertTextEquals("Home server check-in")
        compose.onNodeWithTag("message-input").performTextInput("A local test message")
        compose.activityRule.scenario.onActivity { activity ->
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.onNodeWithTag("send-message").assertIsEnabled().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Local demo response: your message was not sent to a server. Connect to your Hermes gateway to have a real conversation.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("mode-badge").assertTextEquals("DEMO")
        compose.onNodeWithTag("nav-activity").performClick()
        compose.onNodeWithText("Allow once").performScrollTo().performClick()
        compose.onNodeWithText("Allowed once — demo only. No command was executed.").assertExists()
        compose.onNodeWithTag("nav-connect").performClick()
        compose.onNodeWithText("Leave demo").performScrollTo().performClick()
        compose.onNodeWithTag("mode-badge").assertTextEquals("OFFLINE")
    }
}
