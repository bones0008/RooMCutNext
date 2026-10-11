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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ProjectRepository(application)
    private val settings = SettingsRepository(application)

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects = _projects.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _projects.value = repo.listProjects()
        }
    }

    fun createProject(onCreated: (String) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val name = getApplication<Application>().getString(
                com.RooMCutNext.R.string.home_new_project_name, _projects.value.size + 1
            )
            val project = repo.createProject(name, fps = 30, width = 1920, height = 1080)
            _projects.value = repo.listProjects()
            _busy.value = false
            kotlinx.coroutines.withContext(Dispatchers.Main) { onCreated(project.id) }
        }
    }

    fun renameProject(id: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.load(id)?.let { repo.save(it.copy(name = newName.trim().ifBlank { it.name })) }
            _projects.value = repo.listProjects()
        }
    }

    fun duplicateProject(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = repo.listProjects()
            val copy = repo.duplicate(id, "${existing.firstOrNull { it.id == id }?.name ?: "Project"} ②")
            _projects.value = repo.listProjects()
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.delete(id)
            _projects.value = repo.listProjects()
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
