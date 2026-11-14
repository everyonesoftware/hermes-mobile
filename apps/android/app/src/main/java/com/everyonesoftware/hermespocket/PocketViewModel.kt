package com.everyonesoftware.hermespocket

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PocketViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("gateway", Application.MODE_PRIVATE)
    private val gateway = HermesGateway()
    private val store = PocketStore(gateway, preferences.getString("origin", "").orEmpty())
    val state = store.state

    init {
        viewModelScope.launch {
            state.map { it.endpoint }.distinctUntilChanged().collect { origin ->
                // Origin only: tokens and transcripts must never be persisted here.
                preferences.edit().putString("origin", origin).apply()
            }
        }
    }

    fun dispatch(action: PocketAction) {
        viewModelScope.launch { store.dispatch(action) }
    }

    override fun onCleared() {
        gateway.close() // Detaches this viewer; it must not interrupt server-side work.
        super.onCleared()
    }
}
