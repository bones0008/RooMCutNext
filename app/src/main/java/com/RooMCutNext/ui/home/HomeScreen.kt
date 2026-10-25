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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.RooMCutNext.R
import com.RooMCutNext.core.model.Project
import com.RooMCutNext.core.util.TimeCode
import com.RooMCutNext.ui.settings.AiSettingsDialog
import com.RooMCutNext.ui.theme.AccentColor
import java.io.File
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
    // v1.0.11: timeline-frame-20 posters keyed by project id.
    val posters by vm.posters.collectAsState()
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renameFor by remember { mutableStateOf<String?>(null) }
    var deleteFor by remember { mutableStateOf<String?>(null) }
    var showOrientationDialog by remember { mutableStateOf(false) }
    // v1.6.7: 首页顶栏齿轮 — AI 模型设置入口（一次配置好，编辑器 AI 面板零打扰）。
    var showAiSettings by remember { mutableStateOf(false) }
    // v1.0.11: orientation picked, waiting for the name dialog to confirm.
    var pendingPortrait by remember { mutableStateOf<Boolean?>(null) }

    // The HomeViewModel is Activity-scoped and outlives the home screen, so
    // its init{} refresh does NOT re-run when the user returns from the
    // editor. Re-refresh on every entry into composition: this is what keeps
    // clip counts, dates AND posters in sync after edits.
    LaunchedEffect(Unit) { vm.refresh() }

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
                    // v1.6.7: AI 模型设置齿轮 — 首页就能配好/改配置，不必进编辑器找。
                    IconButton(onClick = { showAiSettings = true }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.home_ai_settings),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
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
                onClick = { showOrientationDialog = true },
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
                        posterPath = posters[project.id],
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

    // New project, step 1: pick the canvas orientation (landscape 16:9 vs
    // portrait 9:16) so portrait projects get a tall preview area from the
    // start instead of a letterboxed sliver.
    if (showOrientationDialog) {
        OrientationDialog(
            onDismiss = { showOrientationDialog = false },
            onPick = { portrait ->
                showOrientationDialog = false
                pendingPortrait = portrait
            },
        )
    }

    // New project, step 2 (v1.0.11): the name — pre-filled with the default
    // "项目 N". The user may edit it, or confirm as-is / leave it blank to
    // keep the default.
    pendingPortrait?.let { portrait ->
        ProjectNameDialog(
            initial = vm.defaultProjectName(),
            onDismiss = { pendingPortrait = null },
            onConfirm = { name ->
                pendingPortrait = null
                vm.createProject(onOpenProject, portrait = portrait, customName = name)
            },
        )
    }

    // v1.6.7: AI 模型设置 — 首页一次配置（保存即本机持久化），之后进
    // 任何项目的 AI 面板都不再询问；要换模型/换 Key 随时点齿轮回来改。
    if (showAiSettings) {
        AiSettingsDialog(
            initial = vm.aiSettings(),
            onDismiss = { showAiSettings = false },
            onSave = { settings ->
                vm.setAiSettings(settings)
                showAiSettings = false
            },
        )
    }
}

@Composable
private fun OrientationDialog(onDismiss: () -> Unit, onPick: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_orientation_title)) },
        text = {
            Column {
                OrientationOption(
                    wide = true,
                    title = stringResource(R.string.home_orientation_landscape),
                    desc = stringResource(R.string.home_orientation_landscape_desc),
                    onClick = { onPick(false) },
                )
                Spacer(Modifier.height(10.dp))
                OrientationOption(
                    wide = false,
                    title = stringResource(R.string.home_orientation_portrait),
                    desc = stringResource(R.string.home_orientation_portrait_desc),
                    onClick = { onPick(true) },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_cancel))
            }
        },
    )
}

@Composable
private fun OrientationOption(wide: Boolean, title: String, desc: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Mini canvas preview showing the actual aspect.
            Box(
                Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .then(
                            if (wide) Modifier.size(width = 34.dp, height = 20.dp)
                            else Modifier.size(width = 20.dp, height = 34.dp)
                        )
                        .background(AccentColor, RoundedCornerShape(3.dp))
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    posterPath: String?,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
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
            // v1.0.11: live poster — the timeline's 20th frame, refreshed
            // automatically after edits; the movie icon is now the fallback
            // for empty / audio-only / extraction-failed projects.
            Box(
                Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (posterPath != null) {
                    AsyncImage(
                        model = File(posterPath),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape),
                    )
                } else {
                    Icon(
                        Icons.Filled.Movie, null,
                        tint = AccentColor,
                    )
                }
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

/** v1.0.11: step 2 of project creation — name it, or keep the default. */
@Composable
private fun ProjectNameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_name_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.home_name_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.home_name_dialog_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_cancel)) }
        },
    )
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(ms))
