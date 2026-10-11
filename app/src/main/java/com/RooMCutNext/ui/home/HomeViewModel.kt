package com.RooMCutNext.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.RooMCutNext.core.model.Project
import com.RooMCutNext.core.util.TimeCode
import com.RooMCutNext.data.ProjectRepository
import com.RooMCutNext.data.SettingsRepository
import com.RooMCutNext.media.ProjectPoster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ProjectRepository(application)
    private val settings = SettingsRepository(application)

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects = _projects.asStateFlow()

    /** Project id -> poster file path (timeline frame 20), see [ProjectPoster]. */
    private val _posters = MutableStateFlow<Map<String, String>>(emptyMap())
    val posters = _posters.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = repo.listProjects()
            _projects.value = list
            ensurePosters(list)
        }
    }

    /**
     * Regenerate/collect home-screen posters; the map updates as each poster
     * lands so cards fill in progressively. Poster files are keyed by timeline
     * content fingerprint, so this is a cheap no-op unless content changed.
     */
    private fun ensurePosters(list: List<Project>) {
        val ids = list.map { it.id }.toHashSet()
        for (p in list) {
            val dir = repo.projectDir(p.id)
            val poster = ProjectPoster.ensurePoster(dir, repo.mediaDir(p.id), p)
            if (poster != null) _posters.update { it + (p.id to poster.absolutePath) }
            else _posters.update { it - p.id }
        }
        // Drop entries for projects that vanished from the list (deleted).
        _posters.update { m -> m.filterKeys { it in ids } }
    }

    fun createProject(
        onCreated: (String) -> Unit,
        portrait: Boolean = false,
        customName: String? = null,
    ) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            // Blank input ("默认没名字") falls back to the numbered default.
            val name = customName?.trim()?.takeIf { it.isNotEmpty() }
                ?: getApplication<Application>().getString(
                    com.RooMCutNext.R.string.home_new_project_name, _projects.value.size + 1
                )
            val project = repo.createProject(
                name,
                fps = 30,
                width = if (portrait) 1080 else 1920,
                height = if (portrait) 1920 else 1080,
            )
            _projects.value = repo.listProjects()
            _busy.value = false
            kotlinx.coroutines.withContext(Dispatchers.Main) { onCreated(project.id) }
        }
    }

    /** Pre-filled suggestion shown in the name dialog (count + 1). */
    fun defaultProjectName(): String =
        getApplication<Application>().getString(
            com.RooMCutNext.R.string.home_new_project_name, _projects.value.size + 1
        )

    fun renameProject(id: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.load(id)?.let { repo.save(it.copy(name = newName.trim().ifBlank { it.name })) }
            refresh()
        }
    }

    fun duplicateProject(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = repo.listProjects()
            repo.duplicate(id, "${existing.firstOrNull { it.id == id }?.name ?: "Project"} ②")
            refresh()
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.delete(id)
            refresh()
        }
    }

    fun toggleLanguage(): String = settings.toggleLanguage()

    fun languageMode(): String = settings.languageMode()

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(app) as T
                }
            }
    }
}

/** Formats the small meta line under a project name. */
fun projectMetaLine(project: Project): String {
    val duration = TimeCode.formatShort(project.totalDurationUs)
    return duration
}
