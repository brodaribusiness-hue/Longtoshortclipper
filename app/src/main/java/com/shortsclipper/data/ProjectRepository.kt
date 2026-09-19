package com.shortsclipper.data

import com.shortsclipper.model.ProjectState
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Local JSON persistence for editor projects (autosaved). Fully offline;
 * nothing is uploaded anywhere.
 */
class ProjectRepository(private val baseDir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val projectsDir: File
        get() = File(baseDir, "projects").apply { mkdirs() }

    fun createProject(name: String, nowMs: Long = System.currentTimeMillis()): ProjectState =
        ProjectState(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Untitled project" },
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
        )

    fun save(state: ProjectState): Boolean {
        val target = File(projectsDir, state.id + ".json")
        val tmp = File(projectsDir, state.id + ".json.tmp")
        return try {
            tmp.writeText(json.encodeToString(ProjectState.serializer(), state))
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        } catch (t: Throwable) {
            tmp.delete()
            false
        }
    }

    fun list(): List<ProjectState> = projectsDir
        .listFiles { f -> f.name.endsWith(".json") }
        ?.mapNotNull { f ->
            try {
                json.decodeFromString(ProjectState.serializer(), f.readText())
            } catch (t: Throwable) {
                null
            }
        }
        ?.sortedByDescending { it.updatedAtMs }
        ?: emptyList()

    fun load(id: String): ProjectState? {
        val f = File(projectsDir, "$id.json")
        if (!f.exists()) return null
        return try {
            json.decodeFromString(ProjectState.serializer(), f.readText())
        } catch (t: Throwable) {
            null
        }
    }

    fun delete(id: String): Boolean = File(projectsDir, "$id.json").let { it.exists() && it.delete() }
}
