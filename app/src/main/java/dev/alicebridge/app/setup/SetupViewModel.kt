package dev.alicebridge.app.setup

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.alicebridge.app.BridgeApplication
import dev.alicebridge.domain.ConnectionMethod

class SetupViewModel(handle: SavedStateHandle, initialKsu: String) : ViewModel() {
    @OptIn(SavedStateHandleSaveableApi::class)
    val pageState: MutableState<String> =
        handle.saveable(key = "page") { mutableStateOf("home") }

    @OptIn(SavedStateHandleSaveableApi::class)
    val selectedIdState: MutableState<String> =
        handle.saveable(key = "selectedId") { mutableStateOf("") }

    @OptIn(SavedStateHandleSaveableApi::class)
    val methodState: MutableState<ConnectionMethod?> =
        handle.saveable(key = "explicit-method-choice-v2") { mutableStateOf(null) }

    @OptIn(SavedStateHandleSaveableApi::class)
    val manualAdbAddressState: MutableState<String> =
        handle.saveable(key = "manualAdbAddress") { mutableStateOf("") }

    @OptIn(SavedStateHandleSaveableApi::class)
    val legacyState: MutableState<Boolean> =
        handle.saveable(key = "legacy") { mutableStateOf(false) }

    @OptIn(SavedStateHandleSaveableApi::class)
    val verboseJournalState: MutableState<Boolean> =
        handle.saveable(key = "verboseJournal") { mutableStateOf(false) }

    @OptIn(SavedStateHandleSaveableApi::class)
    val chosenKsuState: MutableState<String> =
        handle.saveable(key = "chosenKsu") { mutableStateOf(initialKsu) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BridgeApplication
                val prefs = app.getSharedPreferences("v2-config", Context.MODE_PRIVATE)
                SetupViewModel(createSavedStateHandle(), prefs.getString("ksu", "").orEmpty())
            }
        }
    }
}
