package com.everyonesoftware.hermespocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            val model: PocketViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            BackHandler(enabled = state.screen != Screen.SESSIONS) {
                model.dispatch(PocketAction.Navigate(Screen.SESSIONS))
            }
            PocketApp(state = state, onAction = model::dispatch)
        }
    }
}
