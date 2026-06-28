package com.dev.docscannerpdf.domain.annotation

/**
 * In-memory annotation state for a single page, including snapshot-based undo/redo stacks.
 * Each entry in [undoStack]/[redoStack] is a full snapshot of [annotations], which keeps the
 * reducer trivial and deterministic to test.
 */
data class PageAnnotationState(
    val pageId: String,
    val annotations: List<Annotation> = emptyList(),
    val undoStack: List<List<Annotation>> = emptyList(),
    val redoStack: List<List<Annotation>> = emptyList()
) {
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val isEmpty: Boolean get() = annotations.isEmpty()
}

/**
 * Pure transitions over [PageAnnotationState]. Every mutating op snapshots the current
 * annotations onto the undo stack and clears the redo stack; [undo]/[redo] move snapshots
 * between the stacks. No Android dependencies, so behavior is fully unit-testable.
 */
object PageAnnotationReducer {

    fun add(state: PageAnnotationState, annotation: Annotation): PageAnnotationState =
        state.copy(
            annotations = state.annotations + annotation,
            undoStack = state.undoStack + listOf(state.annotations),
            redoStack = emptyList()
        )

    fun remove(state: PageAnnotationState, annotationId: String): PageAnnotationState {
        if (state.annotations.none { it.id == annotationId }) return state
        return state.copy(
            annotations = state.annotations.filterNot { it.id == annotationId },
            undoStack = state.undoStack + listOf(state.annotations),
            redoStack = emptyList()
        )
    }

    fun clear(state: PageAnnotationState): PageAnnotationState {
        if (state.annotations.isEmpty()) return state
        return state.copy(
            annotations = emptyList(),
            undoStack = state.undoStack + listOf(state.annotations),
            redoStack = emptyList()
        )
    }

    fun undo(state: PageAnnotationState): PageAnnotationState {
        val previous = state.undoStack.lastOrNull() ?: return state
        return state.copy(
            annotations = previous,
            undoStack = state.undoStack.dropLast(1),
            redoStack = state.redoStack + listOf(state.annotations)
        )
    }

    fun redo(state: PageAnnotationState): PageAnnotationState {
        val next = state.redoStack.lastOrNull() ?: return state
        return state.copy(
            annotations = next,
            undoStack = state.undoStack + listOf(state.annotations),
            redoStack = state.redoStack.dropLast(1)
        )
    }

    /** Replaces annotations (e.g. after loading from disk) without touching the history. */
    fun replaceAll(state: PageAnnotationState, annotations: List<Annotation>): PageAnnotationState =
        state.copy(annotations = annotations, undoStack = emptyList(), redoStack = emptyList())
}
