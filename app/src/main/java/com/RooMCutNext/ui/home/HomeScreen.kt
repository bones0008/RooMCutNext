@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.RooMCutNext.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.RooMCutNext.R
import com.RooMCutNext.core.model.Project
import com.RooMCutNext.core.util.TimeCode
import com.RooMCutNext.ui.theme.AccentColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenProject: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as android.app.Application
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(app))
    HomeContent(vm = vm, onOpenProject = onOpenProject)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(vm: HomeViewModel, onOpenProject: (String) -> Unit) {
    val projects by vm.projects.collectAsState()
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renameFor by remember { mutableStateOf<String?>(null) }
    var deleteFor by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                actions = {
                    TextButton(onClick = { vm.toggleLanguage() }) {
                        Text(
                            stringResource(R.string.home_language_toggle),
                            color = AccentColor,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.createProject(onOpenProject) },
                containerColor = AccentColor,
                contentColor = MaterialTheme.colorScheme.primaryContainer,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.home_new_project)) },
            )
        },
    ) { padding ->
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.home_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        onClick = { onOpenProject(project.id) },
                        onMenu = { menuFor = project.id },
                    )
                }
            }
        }
    }

    // Per-project dropdown menu.
    menuFor?.let { id ->
        val project = projects.firstOrNull { it.id == id }
        if (project != null) {
            Box(Modifier.size(0.dp)) {
                DropdownMenu(expanded = true, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_rename)) },
                        leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                        onClick = { menuFor = null; renameFor = id },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_duplicate)) },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                        onClick = { menuFor = null; vm.duplicateProject(id) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menuFor = null; deleteFor = id },
                    )
                }
            }
        }
    }

    renameFor?.let { id ->
        val project = projects.firstOrNull { it.id == id }
        if (project != null) {
            RenameDialog(
                initial = project.name,
                onDismiss = { renameFor = null },
                onConfirm = { name ->
                    vm.renameProject(id, name)
                    renameFor = null
                },
            )
        }
    }

    deleteFor?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text(stringResource(R.string.home_delete)) },
            text = { Text(stringResource(R.string.home_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = { vm.deleteProject(id); deleteFor = null }) {
                    Text(stringResource(R.string.home_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteFor = null }) {
                    Text(stringResource(R.string.home_cancel))
                }
            },
        )
    }
}

@Composable
private fun ProjectCard(project: Project, onClick: () -> Unit, onMenu: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Movie, null,
                    tint = AccentColor,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(
                        R.string.home_clip_count,
                        project.clips.size,
                        TimeCode.formatShort(project.totalDurationUs),
                    ) + " · " + formatDate(project.updatedAtMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_rename_title)) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.dialog_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_cancel)) }
        },
    )
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(ms))
