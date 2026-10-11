package com.RooMCutNext.data

import android.content.Context
import com.RooMCutNext.core.model.Project
import com.RooMCutNext.core.util.IdGen
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Stack

/**
 * Project persistence: JSON files under filesDir/projects/{id}/.
 *
 * Layout:
 *   projects/{id}/project.json   — the timeline data (autosaved atomically)
 *   projects/{id}/media/         — imported media files (copied, not referenced)
 *
 * Writes are atomic (write to .tmp then rename) so a crash mid-save can never
 * corrupt the project file — combined with autosave-on-every-edit this gives
 * crash recovery for free.
 */
class ProjectRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val projectsRoot: File
        get() = File(context.filesDir, "projects").apply { mkdirs() }

    fun projectDir(projectId: String): File = File(projectsRoot, projectId)

    fun mediaDir(projectId: String): File =
        File(projectDir(projectId), "media").apply { mkdirs() }

    fun mediaFile(projectId: String, fileName: String): File =
        File(mediaDir(projectId), fileName)

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    fun createProject(name: String, fps: Int, width: Int, height: Int): Project {
        val id = IdGen.new("prj")
        val project = Project(
            id = id,
            name = name,
            fps = fps,
            width = width,
            height = height,
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
        )
        projectDir(id).mkdirs()
        save(project)
        return project
    }

    fun save(project: Project) {
        val dir = projectDir(project.id).apply { mkdirs() }
        val target = File(dir, "project.json")
        val tmp = File(dir, "project.json.tmp")
        tmp.writeText(json.encodeToString(project))
        if (!tmp.renameTo(target)) {
            // Rename can fail across odd filesystem states; fall back to copy.
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun load(projectId: String): Project? {
        val file = File(projectDir(projectId), "project.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString<Project>(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    fun delete(projectId: String) {
        projectDir(projectId).deleteRecursively()
    }

    fun duplicate(projectId: String, newName: String): Project? {
        val source = load(projectId) ?: return null
        val newId = IdGen.new("prj")
        val newProject = source.copy(
            id = newId,
            name = newName,
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
        )
        projectDir(newId).mkdirs()
        // Copy media files.
        source.assets.forEach { asset ->
            val from = mediaFile(projectId, asset.fileName)
            if (from.exists()) {
                from.copyTo(mediaFile(newId, asset.fileName), overwrite = true)
            }
        }
        save(newProject)
        return newProject
    }

    /** All projects, most recently updated first. Skips unreadable entries. */
    fun listProjects(): List<Project> {
        val dirs = projectsRoot.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            runCatching {
                val f = File(dir, "project.json")
                if (f.exists()) json.decodeFromString<Project>(f.readText()) else null
            }.getOrNull()
        }.sortedByDescending { it.updatedAtMs }
    }

    /** Remove media files that are no longer referenced by any clip. */
    fun cleanUnusedMedia(project: Project): Int {
        val used = project.clips.map { it.assetId }.toSet()
        val usedFiles = project.assets.filter { it.id in used }.map { it.fileName }.toSet()
        val dir = mediaDir(project.id)
        var removed = 0
        dir.listFiles()?.forEach { f ->
            if (f.name !in usedFiles && !f.name.endsWith(".jpg")) {
                if (f.delete()) removed++
            }
        }
        return removed
    }
}
