package com.shortsclipper.model

/**
 * Real state-based undo/redo stack. Consecutive pushes with the same tag and
 * inside the coalesce window replace the top entry (needed for slider/drag
 * interactions so one gesture = one undo step).
 */
class HistoryStack<T>(private val maxSize: Int = 100) {

    private val entries = ArrayList<T>()
    private var index = -1
    private var lastTag: String? = null
    private var lastTagTimeMs: Long = 0L

    val canUndo: Boolean get() = index > 0
    val canRedo: Boolean get() = index < entries.size - 1
    val size: Int get() = entries.size

    fun current(): T? = entries.getOrNull(index)

    fun push(item: T, tag: String? = null, coalesceMs: Long = 0L, nowMs: Long = System.currentTimeMillis()) {
        // Truncate redo branch.
        while (entries.size > index + 1) entries.removeAt(entries.size - 1)

        val coalesce = tag != null && tag == lastTag && coalesceMs > 0 && nowMs - lastTagTimeMs <= coalesceMs
        if (coalesce && entries.isNotEmpty()) {
            entries[index] = item
        } else {
            entries.add(item)
            if (entries.size > maxSize) entries.removeAt(0)
            index = entries.size - 1
        }
        lastTag = tag
        lastTagTimeMs = nowMs
    }

    /** Moves back one step and returns the new current state, or null if impossible. */
    fun undo(): T? {
        if (!canUndo) return null
        index--
        lastTag = null
        return entries[index]
    }

    /** Moves forward one step and returns the new current state, or null if impossible. */
    fun redo(): T? {
        if (!canRedo) return null
        index++
        lastTag = null
        return entries[index]
    }

    fun clear() {
        entries.clear()
        index = -1
        lastTag = null
    }
}
