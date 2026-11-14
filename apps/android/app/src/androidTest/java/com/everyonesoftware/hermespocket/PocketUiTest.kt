package com.everyonesoftware.hermespocket

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PocketUiTest {
    @get:Rule val compose = createComposeRule()
    private val actions = mutableListOf<PocketAction>()
    private fun show(state: PocketState = PocketState()) {
        compose.setContent { PocketApp(state, actions::add) }
    }

    private fun capture(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "pocket-$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun bottomNavigationEmitsDestination() {
        show()
        capture("offline")
        compose.onNodeWithTag("nav-activity").performClick()
        compose.runOnIdle { assertEquals(PocketAction.Navigate(Screen.ACTIVITY), actions.last()) }
    }

    @Test fun offlineComposerCannotSend() {
        show(PocketState(screen = Screen.CHAT))
        compose.onNodeWithTag("send-message").assertIsNotEnabled()
        compose.onNodeWithTag("message-input").assertIsNotEnabled()
        compose.onNodeWithText("Explore demo").assertExists()
    }

    @Test fun demoIsExplicitAndSessionSearchFilters() {
        show(PocketState(mode = ConnectionMode.DEMO, sessions = listOf(
            AgentSession("a", "Backup report", "Read-only check"),
            AgentSession("b", "Weekly planning", "Monday tasks")
        )))
        compose.onNodeWithTag("mode-badge").assertTextEquals("DEMO")
        capture("sessions")
        compose.onNodeWithTag("session-search").performTextInput("backup")
        compose.onNodeWithText("Backup report").assertExists()
        compose.onNodeWithText("Weekly planning").assertDoesNotExist()
        compose.onNodeWithText("Backup report").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(PocketAction.SelectSession("a"), actions.last()) }
    }

    @Test fun connectRejectsHttpThenEmitsHttpsCredential() {
        show(PocketState(screen = Screen.CONNECT))
        capture("connect")
        compose.onNodeWithTag("gateway-endpoint").performTextInput("http://home.example")
        compose.onNodeWithTag("gateway-token").performTextInput("test-session-token")
        compose.onNodeWithTag("connect-submit").performScrollTo().performClick()
        compose.onNodeWithText("Enter a valid HTTPS gateway address.").assertExists()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithTag("gateway-endpoint").performTextReplacement("https://home.example")
        compose.onNodeWithTag("connect-submit").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(PocketAction.Connect("https://home.example", "test-session-token"), actions.last())
        }
    }

    @Test fun approvalDisplaysExactCommandAndAllowsOnce() {
        show(PocketState(screen = Screen.ACTIVITY, mode = ConnectionMode.DEMO,
            approval = Approval("approval-1", "sudo systemctl restart backup.service", "Restarts backup service")))
        compose.onNodeWithText("sudo systemctl restart backup.service").assertExists()
        capture("activity")
        compose.onNodeWithText("Allow once").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(PocketAction.DecideApproval("approval-1", true), actions.last()) }
    }

    @Test fun approvalCanBeDenied() {
        show(PocketState(screen = Screen.ACTIVITY, mode = ConnectionMode.DEMO,
            approval = Approval("approval-2", "restart service", "Review this command")))
        compose.onNodeWithText("Deny").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(PocketAction.DecideApproval("approval-2", false), actions.last()) }
    }

    @Test fun busyDisablesMutatingActions() {
        compose.mainClock.autoAdvance = false
        show(PocketState(screen = Screen.CHAT, mode = ConnectionMode.DEMO, busy = true))
        compose.onNodeWithTag("send-message").assertIsNotEnabled()
        compose.onNodeWithTag("new-session").assertIsNotEnabled()
    }

    @Test fun selectedSessionTitleIsShown() {
        show(PocketState(screen = Screen.CHAT, mode = ConnectionMode.DEMO,
            selectedSessionId = "b", sessionTitle = "Weekly planning"))
        compose.onNodeWithTag("screen-context").assertTextEquals("Weekly planning")
    }

    @Test fun liveApprovalIsUnsupportedNotActionable() {
        show(PocketState(screen = Screen.ACTIVITY, mode = ConnectionMode.CONNECTED,
            approval = Approval("a", "restart service", "Review command")))
        compose.onNodeWithText("Allow once").assertDoesNotExist()
        compose.onNodeWithText("Remote approvals are not supported in this app version.").assertExists()
    }
}
