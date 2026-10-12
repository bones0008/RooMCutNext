package com.RooMCutNext.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.RooMCutNext.ui.editor.EditorScreen
import com.RooMCutNext.ui.home.HomeScreen

/**
 * A ViewModel store whose lifetime is bound to the EDITOR SCREEN, not the
 * Activity.
 *
 * v1.2.6 bug: `viewModel()` inside EditorScreen used to resolve to the
 * Activity-scoped default store, so returning to the project list merely
 * switched the Compose UI away — the EditorViewModel (and with it the
 * CompositionPlayer plus every overlay ExoPlayer) stayed alive and playback
 * kept RUNNING in the background. Clearing this store the moment the editor
 * leaves composition runs EditorViewModel.onCleared(), which saves the
 * project and releases both player stacks.
 *
 * Safe against config-change regressions: MainActivity is portrait-locked
 * and handles orientation|screenSize|keyboardHidden|uiMode itself, so this
 * branch's onDispose only fires for real navigation (or Activity teardown,
 * where the Activity-scoped store would have been cleared anyway).
 */
private class EditorStoreOwner : ViewModelStoreOwner {
    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store
    fun clear() = store.clear()
}

/** Minimal state-based navigation: home <-> editor(:projectId). */
@Composable
fun AppRoot() {
    var route by rememberSaveable { mutableStateOf("home") }

    if (route == "home") {
        HomeScreen(
            onOpenProject = { id -> route = "editor:$id" },
        )
    } else {
        val projectId = route.removePrefix("editor:").ifBlank { "home" }
        BackHandler { route = "home" }
        val editorStore = remember { EditorStoreOwner() }
        // Leaving the editor (back gesture / arrow) disposes this branch:
        // clear the store so the editor ViewModel — and every player it
        // owns — is torn down instead of playing on in the background.
        DisposableEffect(editorStore) {
            onDispose { editorStore.clear() }
        }
        CompositionLocalProvider(LocalViewModelStoreOwner provides editorStore) {
            EditorScreen(
                projectId = projectId,
                onExit = { route = "home" },
            )
        }
    }
}
