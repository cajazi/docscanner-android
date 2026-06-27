package com.dev.docscannerpdf.domain.annotation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnnotationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun points(vararg pairs: Pair<Float, Float>) =
        pairs.map { AnnotationPoint(it.first, it.second) }

    private fun stroke(id: String, type: AnnotationType = AnnotationType.DRAW) = AnnotationStroke(
        id = id,
        type = type,
        points = points(0f to 0f, 0.5f to 0.5f, 1f to 1f),
        color = 0xFFE53935L,
        thickness = 6f
    )

    // --- Stroke creation correctness ----------------------------------------

    @Test
    fun strokeFactoryCapturesGeometryAndStyle() {
        val created = AnnotationFactory.stroke(
            id = "s1",
            type = AnnotationType.DRAW,
            points = points(0.1f to 0.2f, 0.3f to 0.4f),
            color = 0xFF112233L,
            thickness = 4f
        )

        assertNotNull(created)
        assertEquals(2, created!!.points.size)
        assertEquals(AnnotationType.DRAW, created.type)
        assertEquals(0xFF112233L, created.color)
        assertEquals(4f, created.thickness, 0f)
    }

    @Test
    fun strokeFactoryRejectsDegenerateInput() {
        // A single point (a tap) is not a stroke.
        assertNull(
            AnnotationFactory.stroke("s", AnnotationType.DRAW, points(0.5f to 0.5f), 0L, 6f)
        )
        // TEXT coerces to DRAW for a freehand stroke; thickness is floored.
        val coerced = AnnotationFactory.stroke(
            "s", AnnotationType.TEXT, points(0f to 0f, 1f to 1f), 0L, -3f
        )
        assertEquals(AnnotationType.DRAW, coerced!!.type)
        assertTrue(coerced.thickness >= 0.1f)
    }

    @Test
    fun textFactoryTrimsAndRejectsBlank() {
        assertNull(AnnotationFactory.text("t", AnnotationPoint(0f, 0f), "   ", 0L, 12f))
        val text = AnnotationFactory.text("t", AnnotationPoint(0.2f, 0.3f), "  note  ", 0xFFL, 12f)
        assertEquals("note", text!!.value)
    }

    // --- Undo / redo stack behavior -----------------------------------------

    @Test
    fun undoRedoTracksHistoryDeterministically() {
        val base = PageAnnotationState(pageId = "p1")
        assertFalse(base.canUndo)
        assertFalse(base.canRedo)

        val afterA = PageAnnotationReducer.add(base, stroke("a"))
        val afterB = PageAnnotationReducer.add(afterA, stroke("b"))
        assertEquals(listOf("a", "b"), afterB.annotations.map { it.id })
        assertTrue(afterB.canUndo)

        val undone = PageAnnotationReducer.undo(afterB)
        assertEquals(listOf("a"), undone.annotations.map { it.id })
        assertTrue(undone.canRedo)

        val redone = PageAnnotationReducer.redo(undone)
        assertEquals(listOf("a", "b"), redone.annotations.map { it.id })
        assertFalse(redone.canRedo)
    }

    @Test
    fun addAfterUndoClearsRedoStack() {
        val base = PageAnnotationState(pageId = "p1")
        val afterA = PageAnnotationReducer.add(base, stroke("a"))
        val afterB = PageAnnotationReducer.add(afterA, stroke("b"))
        val undone = PageAnnotationReducer.undo(afterB)

        val afterC = PageAnnotationReducer.add(undone, stroke("c"))
        assertEquals(listOf("a", "c"), afterC.annotations.map { it.id })
        assertFalse(afterC.canRedo)
    }

    @Test
    fun undoRedoOnEmptyHistoryAreNoOps() {
        val base = PageAnnotationState(pageId = "p1", annotations = listOf(stroke("a")))
        assertEquals(base, PageAnnotationReducer.undo(base))
        assertEquals(base, PageAnnotationReducer.redo(base))
    }

    // --- Persistence round-trip ---------------------------------------------

    @Test
    fun persistenceRoundTripsMixedAnnotations() {
        val repository = AnnotationRepository(tempFolder.newFolder("annotations"))
        val annotations = listOf(
            stroke("pen", AnnotationType.DRAW),
            stroke("hl", AnnotationType.HIGHLIGHT),
            AnnotationText("t1", AnnotationPoint(0.4f, 0.6f), "Approved", 0xFF00FF00L, 14f)
        )

        repository.savePage("doc-7", "page-1", annotations)
        val restored = repository.loadPage("doc-7", "page-1")

        assertEquals(annotations, restored)
        // Other pages are unaffected.
        assertTrue(repository.loadPage("doc-7", "page-2").isEmpty())
    }

    @Test
    fun savingEmptyListRemovesPage() {
        val repository = AnnotationRepository(tempFolder.newFolder("annotations"))
        repository.savePage("doc-1", "page-1", listOf(stroke("a")))
        repository.savePage("doc-1", "page-1", emptyList())
        assertTrue(repository.loadPage("doc-1", "page-1").isEmpty())
    }

    // --- Mode switching does not reset state ---------------------------------

    @Test
    fun switchingModePreservesAnnotationsAndHistory() {
        val page = PageAnnotationReducer.add(
            PageAnnotationReducer.add(PageAnnotationState("p1"), stroke("a")),
            stroke("b")
        )
        val editor = AnnotationEditorState(page = page, mode = AnnotationMode.ANNOTATE)

        val toView = AnnotationEditorReducer.toggleMode(editor)
        assertEquals(AnnotationMode.VIEW, toView.mode)
        // Page data, including undo history, is untouched by the mode change.
        assertEquals(page, toView.page)

        val backToAnnotate = AnnotationEditorReducer.setMode(toView, AnnotationMode.ANNOTATE)
        assertEquals(page, backToAnnotate.page)

        val toolChanged = AnnotationEditorReducer.setTool(backToAnnotate, AnnotationTool.HIGHLIGHT)
        assertEquals(AnnotationTool.HIGHLIGHT, toolChanged.tool)
        assertEquals(page, toolChanged.page)
    }

    @Test
    fun editorAddUndoRedoDelegateToPageReducer() {
        val editor = AnnotationEditorState(page = PageAnnotationState("p1"))
        val withStroke = AnnotationEditorReducer.addAnnotation(editor, stroke("a"))
        assertEquals(listOf("a"), withStroke.page.annotations.map { it.id })

        val undone = AnnotationEditorReducer.undo(withStroke)
        assertTrue(undone.page.annotations.isEmpty())

        val redone = AnnotationEditorReducer.redo(undone)
        assertEquals(listOf("a"), redone.page.annotations.map { it.id })
    }
}
