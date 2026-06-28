package com.dev.docscannerpdf.domain.annotation

/** Whether the result screen is viewing the page or actively annotating it. */
enum class AnnotationMode { VIEW, ANNOTATE }

/** Active annotation tool while in [AnnotationMode.ANNOTATE]. */
enum class AnnotationTool { PEN, HIGHLIGHT, TEXT }

/**
 * UI-facing editor state combining the current [mode] and [tool] with the page's
 * [PageAnnotationState]. Mode/tool are presentation concerns kept separate from the page data
 * so that switching modes provably never mutates the annotations (see [AnnotationEditorReducer]).
 */
data class AnnotationEditorState(
    val page: PageAnnotationState,
    val mode: AnnotationMode = AnnotationMode.VIEW,
    val tool: AnnotationTool = AnnotationTool.PEN
) {
    val isAnnotating: Boolean get() = mode == AnnotationMode.ANNOTATE
}

/**
 * Pure transitions for the editor. Mode/tool changes deliberately leave [AnnotationEditorState.page]
 * untouched, and annotation edits delegate to [PageAnnotationReducer], so the page's annotations
 * and undo/redo history survive mode switching.
 */
object AnnotationEditorReducer {

    fun setMode(state: AnnotationEditorState, mode: AnnotationMode): AnnotationEditorState =
        state.copy(mode = mode)

    fun toggleMode(state: AnnotationEditorState): AnnotationEditorState =
        state.copy(
            mode = if (state.mode == AnnotationMode.ANNOTATE) AnnotationMode.VIEW else AnnotationMode.ANNOTATE
        )

    fun setTool(state: AnnotationEditorState, tool: AnnotationTool): AnnotationEditorState =
        state.copy(tool = tool)

    fun addAnnotation(state: AnnotationEditorState, annotation: Annotation): AnnotationEditorState =
        state.copy(page = PageAnnotationReducer.add(state.page, annotation))

    fun undo(state: AnnotationEditorState): AnnotationEditorState =
        state.copy(page = PageAnnotationReducer.undo(state.page))

    fun redo(state: AnnotationEditorState): AnnotationEditorState =
        state.copy(page = PageAnnotationReducer.redo(state.page))

    fun clear(state: AnnotationEditorState): AnnotationEditorState =
        state.copy(page = PageAnnotationReducer.clear(state.page))
}
