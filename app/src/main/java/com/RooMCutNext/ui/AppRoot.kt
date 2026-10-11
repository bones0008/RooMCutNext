package com.RooMCutNext.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.RooMCutNext.ui.editor.EditorScreen
import com.RooMCutNext.ui.home.HomeScreen

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
        EditorScreen(
            projectId = projectId,
            onExit = { route = "home" },
        )
    }
}
