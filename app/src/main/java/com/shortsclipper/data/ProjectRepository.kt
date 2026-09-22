package com.shortsclipper.data

import com.shortsclipper.model.ProjectState
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Local, crash-safe JSON persistence for offline editor projects. */
class ProjectRepository(private val baseDir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val fileLock = Any()
    // Prevent a cancelled autosave already queued on an IO thread from
    // recreating a project that the user just deleted in this process.
    private val deletedIds = mutableSetOf<String>()

    private val projectsDir: File
        get() = File(baseDir, "projects").apply { mkdirs() }

    fun createProject(name: String, nowMs: Long = System.currentTimeMillis()): ProjectState =
        ProjectState(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Untitled project" },
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
        )

    fun save(state: ProjectState): Boolean = synchronized(fileLock) {
        if (!isSafeId(state.id) || state.id in deletedIds) return@synchronized false
        recoverInterruptedSavesLocked()
        val target = File(projectsDir, "${state.id}.json")
        // A canceled older autosave may finish its blocking disk write after a
        // newer edit. The persisted revision breaks same-millisecond timestamp
        // ties, so an older snapshot can never roll a project backward.
        val onDisk = target.takeIf { it.isFile }?.let(::decode)
        if (onDisk != null &&
            (onDisk.updatedAtMs > state.updatedAtMs ||
                (onDisk.updatedAtMs == state.updatedAtMs && onDisk.revision > state.revision))
        ) {
            return@synchronized true
        }
        val temporary = File(projectsDir, ".${state.id}.${System.nanoTime()}.tmp")
        val backup = File(projectsDir, "${state.id}.json.previous")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.encodeToString(ProjectState.serializer(), state).toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            backup.delete()
            val hadTarget = target.exists()
            if (hadTarget && !target.renameTo(backup)) throw IllegalStateException("Could not preserve existing project")
            if (!temporary.renameTo(target)) {
                if (hadTarget) backup.renameTo(target)
                throw IllegalStateException("Could not finalize project save")
            }
            backup.delete()
            true
        } catch (_: Throwable) {
            temporary.delete()
            false
        }
    }

    fun list(): List<ProjectState> = synchronized(fileLock) {
        recoverInterruptedSavesLocked()
        projectsDir
            .listFiles { file -> file.name.endsWith(".json") }
            ?.mapNotNull { file -> decode(file) }
            ?.sortedByDescending { it.updatedAtMs }
            ?: emptyList()
    }

    fun load(id: String): ProjectState? = synchronized(fileLock) {
        if (!isSafeId(id)) return@synchronized null
        recoverInterruptedSavesLocked()
        val file = File(projectsDir, "$id.json")
        if (!file.isFile) null else decode(file)
    }

    fun delete(id: String): Boolean = synchronized(fileLock) {
        if (!isSafeId(id)) return@synchronized false
        recoverInterruptedSavesLocked()
        deletedIds.add(id)
        val file = File(projectsDir, "$id.json")
        val deleted = file.exists() && file.delete()
        File(projectsDir, "$id.json.previous").delete()
        deleted
    }

    /** Restores the last complete file if a process died between rename steps. */
    private fun recoverInterruptedSavesLocked() {
        val directory = projectsDir
        directory.listFiles()?.forEach { file ->
            when {
                file.name.endsWith(".json.previous") -> {
                    val targetName = file.name.removeSuffix(".previous")
                    val id = targetName.removeSuffix(".json")
                    if (isSafeId(id)) {
                        val target = File(directory, targetName)
                        if (!target.exists()) {
                            file.renameTo(target)
                        } else {
                            file.delete()
                        }
                    } else {
                        file.delete()
                    }
                }
                file.name.startsWith(".") && file.name.endsWith(".tmp") -> {
                    // No writer exists while fileLock is held, so these can
                    // only be abandoned temp snapshots from an interrupted save.
                    file.delete()
                }
            }
        }
    }

    private fun decode(file: File): ProjectState? = try {
        json.decodeFromString(ProjectState.serializer(), file.readText(Charsets.UTF_8))
    } catch (_: Throwable) {
        null
    }

    private fun isSafeId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9_-]{1,80}"))
}
