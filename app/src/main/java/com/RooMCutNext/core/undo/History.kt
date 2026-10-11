package com.RooMCutNext.core.undo

/**
 * Snapshot-based undo/redo history.
 *
 * Every committed edit records (label, stateBefore, stateAfter); undo restores
 * the previous snapshot and pushes the current state onto the redo stack.
 *
 * Snapshot (memento) history was chosen over granular commands deliberately:
 * the whole editor state is a small immutable tree (list of clips / texts), so
 * full snapshots are cheap, every operation is trivially reversible, and there
 * is no way for a command's inverse to drift out of sync with the model.
 * (Same conclusion reached independently by LibreCuts; OpenCut converges on
 * per-command full tracks snapshots as well.)
 */
class SnapshotHistory<T>(private val capacity: Int = 100) {

    data class Entry<T>(val label: String, val before: T, val after: T)

    private val undoEntries = ArrayDeque<Entry<T>>()
    private val redoEntries = ArrayDeque<Entry<T>>()

    val canUndo: Boolean get() = undoEntries.isNotEmpty()
    val canRedo: Boolean get() = redoEntries.isNotEmpty()
    val undoLabel: String? get() = undoEntries.lastOrNull()?.label
    val redoLabel: String? get() = redoEntries.lastOrNull()?.label
    val undoDepth: Int get() = undoEntries.size

    /** Record a committed change and clear the redo stack. */
    fun record(label: String, before: T, after: T) {
        undoEntries.addLast(Entry(label, before, after))
        if (undoEntries.size > capacity) undoEntries.removeFirst()
        redoEntries.clear()
    }

    /**
     * Undo the latest change. Returns the state to restore, or null when the
     * stack is empty. [current] is the live state (unused by this value-based
     * stack; kept for interface symmetry).
     */
    fun undo(current: T): T? {
        val entry = undoEntries.removeLastOrNull() ?: return null
        // The redo entry is identical: redoing re-applies before -> after.
        redoEntries.addLast(entry)
        return entry.before
    }

    /** Redo the most recently undone change. Returns the state to restore, or null. */
    fun redo(current: T): T? {
        val entry = redoEntries.removeLastOrNull() ?: return null
        undoEntries.addLast(entry)
        return entry.after
    }

    fun clear() {
        undoEntries.clear()
        redoEntries.clear()
    }
}
