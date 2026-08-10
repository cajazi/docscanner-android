package com.dev.docscannerpdf.ui.mainscan

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import com.dev.docscannerpdf.domain.mainscan.MainScanCropEditor
import com.dev.docscannerpdf.domain.mainscan.MainScanCropHandle
import com.dev.docscannerpdf.domain.mainscan.MainScanCropState
import com.dev.docscannerpdf.domain.mainscan.MainScanPolygonSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Regression cover for the crop editor's DRAG WIRING, as opposed to its geometry.
 *
 * [MainScanCropEditor] is thoroughly covered by pure JVM tests, and every one of them passed while
 * the editor was unusable on a device: the defect lived in the gesture layer, where the pointer
 * input block held the polygon captured at first composition. These tests assert the one thing a
 * pure test cannot reach — that a real touch sequence dispatched at a handle reaches the reducer and
 * that the result survives the release.
 *
 * ## Two things the synthetic gesture must respect, both measured on a Samsung SM-A165F
 *
 * 1. **Touch slop, near the handle.** `detectDragGestures` fires `onDragStart` at the position where
 *    slop is CROSSED, not at the down position. At density 450 slop is ~22.5px, while the handle hit
 *    radius is `HANDLE_TOUCH_RADIUS_FRACTION` (0.075) of the shorter rendered edge — 81px for this
 *    fixture. A first step below slop defers activation to the following, much more distant, event,
 *    where the hit test correctly finds nothing. [SLOP_CROSSING_STEP_PX] sits deliberately between
 *    the two bounds.
 *
 * 2. **A frame between events.** The active handle is hoisted: `onDragStart` publishes it upward and
 *    `onDrag` reads it back after recomposition. Real touch satisfies this for free, because pointer
 *    events arrive across frames. Injected events do not — `advanceEventTime` moves the event clock,
 *    not the composition clock — so each event is dispatched in its own block with an idle sync
 *    between, which is what models a real drag.
 */
@RunWith(AndroidJUnit4::class)
class MainScanCropDragTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Above the ~22.5px platform touch slop, well inside the 81px handle radius for this fixture. */
    private val slopCrossingStepPx = 40f

    /** Normalized tolerance. The mapping is exact float arithmetic, so this stays tight. */
    private val tolerance = 0.01f

    private fun workingImage(): MainScanWorkingImage {
        val bitmap = createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        return MainScanWorkingImage(bitmap, bitmap.width, bitmap.height)
    }

    /** Mounts the real editor with state hoisted exactly as the activity hoists it. */
    private fun mountEditor(image: MainScanWorkingImage): () -> MainScanCropState {
        var latest: MainScanCropState = MainScanCropEditor.initial(
            MainScanCropEditor.fullFrame(),
            MainScanPolygonSource.FULL_FRAME
        )
        composeRule.setContent {
            var state by remember {
                mutableStateOf(
                    MainScanCropEditor.initial(
                        MainScanCropEditor.fullFrame(),
                        MainScanPolygonSource.FULL_FRAME
                    )
                )
            }
            latest = state
            MainScanCropEditorScreen(
                image = image,
                cropState = state,
                onCropStateChange = { state = it },
                onRotate = {},
                onResetAll = {},
                onNext = {},
                onBack = {}
            )
        }
        composeRule.waitForIdle()
        return { latest }
    }

    /**
     * Mounts the editor with the hoisted crop state held OUTSIDE composition, so a test can both read
     * it and replace it externally — modelling an unrelated crop-state change (e.g. Reset All) that
     * lands while the pointer coroutine is still alive. Returns a reader and an external setter.
     */
    private fun mountEditorControllable(
        image: MainScanWorkingImage
    ): Pair<() -> MainScanCropState, (MainScanCropState) -> Unit> {
        val holder = mutableStateOf(
            MainScanCropEditor.initial(MainScanCropEditor.fullFrame(), MainScanPolygonSource.FULL_FRAME)
        )
        composeRule.setContent {
            MainScanCropEditorScreen(
                image = image,
                cropState = holder.value,
                onCropStateChange = { holder.value = it },
                onRotate = {},
                onResetAll = {},
                onNext = {},
                onBack = {}
            )
        }
        composeRule.waitForIdle()
        return ({ holder.value }) to ({ next: MainScanCropState -> holder.value = next })
    }

    /** The letterboxed rectangle the image occupies in the crop surface, in surface-local pixels. */
    private data class Fitted(val left: Float, val top: Float, val width: Float, val height: Float)

    private fun fittedRect(image: MainScanWorkingImage): Fitted {
        val node = composeRule.onNodeWithContentDescription("Captured page").fetchSemanticsNode()
        val surfaceWidth = node.size.width.toFloat()
        val surfaceHeight = node.size.height.toFloat()
        val scale = minOf(surfaceWidth / image.width, surfaceHeight / image.height)
        val width = image.width * scale
        val height = image.height * scale
        return Fitted((surfaceWidth - width) / 2f, (surfaceHeight - height) / 2f, width, height)
    }

    private fun Fitted.at(x: Float, y: Float) = Offset(left + x * width, top + y * height)

    /**
     * Dispatches a drag the way a finger produces one: a down, a first step that clears touch slop
     * while still on the handle, then the caller's waypoints, then a release — each event followed
     * by an idle sync so composition keeps up, as it does between real frames.
     */
    private fun synchronizedDrag(start: Offset, waypoints: List<Offset>) {
        val node = composeRule.onNodeWithContentDescription("Captured page")
        val direction = waypoints.first() - start
        val length = hypot(direction.x, direction.y)
        val slopStep = if (length <= 0f) {
            Offset(slopCrossingStepPx, 0f)
        } else {
            direction / length * slopCrossingStepPx
        }

        node.performTouchInput { down(start) }
        composeRule.waitForIdle()
        node.performTouchInput { moveTo(start + slopStep) }
        composeRule.waitForIdle()
        for (waypoint in waypoints) {
            node.performTouchInput { moveTo(waypoint) }
            composeRule.waitForIdle()
        }
        node.performTouchInput { up() }
        composeRule.waitForIdle()
    }

    private fun assertClose(message: String, expected: Float, actual: Float) {
        assertTrue(
            "$message — expected≈$expected, was $actual",
            abs(expected - actual) <= tolerance
        )
    }

    // --- corner handles ---------------------------------------------------------------------------

    @Test
    fun aSlowMultiStepDragMovesTheCornerAndTheMoveSurvivesRelease() {
        val image = workingImage()
        val readState = mountEditor(image)
        val before: PerspectiveQuad = readState().quad
        val fitted = fittedRect(image)

        synchronizedDrag(
            start = fitted.at(0f, 0f),
            waypoints = listOf(
                fitted.at(0.10f, 0.07f),
                fitted.at(0.20f, 0.14f),
                fitted.at(0.30f, 0.20f)
            )
        )

        val after = readState().quad
        assertClose("top-left x must land on the release point", 0.30f, after.topLeft.x)
        assertClose("top-left y must land on the release point", 0.20f, after.topLeft.y)

        // A corner handle moves exactly one corner.
        assertEquals("top-right must not move", before.topRight, after.topRight)
        assertEquals("bottom-right must not move", before.bottomRight, after.bottomRight)
        assertEquals("bottom-left must not move", before.bottomLeft, after.bottomLeft)

        // The original defect's signature was a polygon that snapped back on release.
        assertNull("the drag must be finished once the finger lifts", readState().activeHandle)
    }

    /**
     * The short-flick case from the behaviour reference: the finger clears slop and stops almost
     * immediately. This is the case a frame-latency regression would break first, because there are
     * only a couple of move events for the active handle to be picked up in.
     */
    @Test
    fun aShortDragJustPastTouchSlopStillMovesTheCorner() {
        val image = workingImage()
        val readState = mountEditor(image)
        val before: PerspectiveQuad = readState().quad
        val fitted = fittedRect(image)

        val start = fitted.at(0f, 0f)
        val shortTarget = start + Offset(60f, 60f)
        synchronizedDrag(start = start, waypoints = listOf(shortTarget))

        val after = readState().quad
        val expectedX = 60f / fitted.width
        val expectedY = 60f / fitted.height

        assertTrue(
            "a short drag must still move the corner, not be swallowed as a tap",
            after.topLeft.x > 0f && after.topLeft.y > 0f
        )
        assertClose("short-drag x must land on the release point", expectedX, after.topLeft.x)
        assertClose("short-drag y must land on the release point", expectedY, after.topLeft.y)
        assertEquals("bottom-right must not move", before.bottomRight, after.bottomRight)
        assertNull("the drag must be finished once the finger lifts", readState().activeHandle)
    }

    /**
     * The timing condition the other tests deliberately avoid: a rapid drag whose DOWN, MOVEs and UP
     * are dispatched in ONE continuous [performTouchInput] block with NO [ComposeTestRule.waitForIdle]
     * between events, so no recomposition can occur mid-gesture. This is what a real quick flick does.
     *
     * With the hoisted state alone, every [onDrag] in the burst read `currentCropState.activeHandle`
     * as null (recomposition had not yet advanced `rememberUpdatedState` past `onDragStart`), the
     * `?: return` fired, and the whole drag was lost. The local per-gesture state makes the handle and
     * the working quad authoritative inside the pointer coroutine, so the burst moves the corner and
     * the move survives release without depending on frame timing.
     */
    @Test
    fun aBurstDragWithoutIntermediateIdleMovesTheCornerAndSurvivesRelease() {
        val image = workingImage()
        val readState = mountEditor(image)
        val before: PerspectiveQuad = readState().quad
        val fitted = fittedRect(image)

        val start = fitted.at(0f, 0f)
        // One uninterrupted gesture: down, a first step that clears touch slop while still on the
        // handle, a fast burst of moves, then release — all in a single injection, no idle between.
        composeRule.onNodeWithContentDescription("Captured page").performTouchInput {
            down(start)
            moveTo(start + Offset(slopCrossingStepPx, slopCrossingStepPx))
            moveTo(fitted.at(0.12f, 0.09f))
            moveTo(fitted.at(0.22f, 0.16f))
            moveTo(fitted.at(0.32f, 0.22f))
            up()
        }
        composeRule.waitForIdle()

        val after = readState().quad
        // The corner moved materially — neither swallowed as a tap nor frozen at the origin.
        assertTrue(
            "a quick burst drag must move the corner materially — was (${after.topLeft.x}, ${after.topLeft.y})",
            after.topLeft.x > 0.15f && after.topLeft.y > 0.10f
        )
        assertClose("burst-drag x must land on the release point", 0.32f, after.topLeft.x)
        assertClose("burst-drag y must land on the release point", 0.22f, after.topLeft.y)

        // A corner handle moves exactly one corner; the move survives the lift and the drag is over.
        assertEquals("top-right must not move", before.topRight, after.topRight)
        assertEquals("bottom-right must not move", before.bottomRight, after.bottomRight)
        assertEquals("bottom-left must not move", before.bottomLeft, after.bottomLeft)
        assertNull("the drag must be finished once the finger lifts", readState().activeHandle)
    }

    @Test
    fun downOnHandleThenCrossSlopOutsideTargetPreservesOriginalHandleIntent() {
        val image = workingImage()
        val readState = mountEditor(image)
        val before = readState().quad
        val fitted = fittedRect(image)

        composeRule.onNodeWithContentDescription("Captured page").performTouchInput {
            down(fitted.at(0f, 0f))
            moveTo(fitted.at(0.20f, 0.18f))
            moveTo(fitted.at(0.32f, 0.24f))
            up()
        }
        composeRule.waitForIdle()

        val after = readState().quad
        assertClose("the original top-left handle must remain acquired", 0.32f, after.topLeft.x)
        assertClose("the first recognized drag must not be lost", 0.24f, after.topLeft.y)
        assertEquals(before.topRight, after.topRight)
        assertEquals(before.bottomRight, after.bottomRight)
        assertEquals(before.bottomLeft, after.bottomLeft)
        assertNull(readState().activeHandle)
    }

    @Test
    fun downOffHandleThenCrossSlopOverHandleDoesNotAcquireLate() {
        val image = workingImage()
        val readState = mountEditor(image)
        val before = readState()
        val fitted = fittedRect(image)

        composeRule.onNodeWithContentDescription("Captured page").performTouchInput {
            down(fitted.at(0.5f, 0.5f))
            moveTo(fitted.at(0f, 0f))
            moveTo(fitted.at(0.20f, 0.18f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals("an off-handle down must keep the crop unchanged", before.quad, readState().quad)
        assertNull("an off-handle down must never acquire a handle later", readState().activeHandle)
    }

    // --- edge handles -----------------------------------------------------------------------------

    @Test
    fun draggingAnEdgeHandleMovesBothOfItsCornersAndLeavesTheOppositeEdgeAlone() {
        val image = workingImage()
        val readState = mountEditor(image)
        val before: PerspectiveQuad = readState().quad
        val fitted = fittedRect(image)

        val topHandle = MainScanCropEditor.handlePosition(before, MainScanCropHandle.TOP)
        synchronizedDrag(
            start = fitted.at(topHandle.x, topHandle.y),
            waypoints = listOf(
                fitted.at(topHandle.x, 0.12f),
                fitted.at(topHandle.x, 0.25f)
            )
        )

        val after = readState().quad
        // The edge translates along its normal, so both of its corners land on the release row.
        assertClose("top-left must travel to the release row", 0.25f, after.topLeft.y)
        assertClose("top-right must travel to the release row", 0.25f, after.topRight.y)
        assertClose("the top edge must stay level", after.topLeft.y, after.topRight.y)
        assertClose("top-left x must not drift", before.topLeft.x, after.topLeft.x)
        assertClose("top-right x must not drift", before.topRight.x, after.topRight.x)

        assertEquals("the bottom edge must not move", before.bottomLeft, after.bottomLeft)
        assertEquals("the bottom edge must not move", before.bottomRight, after.bottomRight)
        assertNull("the drag must be finished once the finger lifts", readState().activeHandle)
    }

    // --- off-handle lifecycle -------------------------------------------------------------------------

    /**
     * The reviewed lifecycle gap: the pointer coroutine outlives unrelated crop-state changes, so a
     * drag that never acquires a handle must NOT republish the coroutine's stale local polygon over a
     * crop state that was replaced externally (e.g. Reset All). Before the guard, onDragEnd published
     * `working` unconditionally and clobbered the reset; after it, an off-handle gesture is inert.
     */
    @Test
    fun anOffHandleDragDoesNotRepublishStaleStateOverAnExternalReset() {
        val image = workingImage()
        val (readState, setState) = mountEditorControllable(image)
        val fitted = fittedRect(image)

        // 1. A real handle drag, so the coroutine's local working polygon diverges from full-frame.
        synchronizedDrag(
            start = fitted.at(0f, 0f),
            waypoints = listOf(fitted.at(0.15f, 0.12f), fitted.at(0.30f, 0.22f))
        )
        assertClose("precondition: the corner drag moved the polygon", 0.30f, readState().quad.topLeft.x)

        // 2. Externally replace the hoisted state with a known DIFFERENT one (a fresh full-frame reset).
        val external = MainScanCropEditor.initial(
            MainScanCropEditor.fullFrame(), MainScanPolygonSource.FULL_FRAME
        )
        composeRule.runOnIdle { setState(external) }
        composeRule.waitForIdle()

        // 3. A drag that begins away from every corner and edge handle (image centre).
        synchronizedDrag(
            start = fitted.at(0.5f, 0.5f),
            waypoints = listOf(fitted.at(0.62f, 0.60f), fitted.at(0.70f, 0.66f))
        )

        // 4. The external state must be untouched, and no drag may be active.
        val after = readState()
        assertEquals(
            "an off-handle drag must not overwrite the externally reset crop state",
            external.quad, after.quad
        )
        assertNull("no handle may be active after an off-handle drag", after.activeHandle)
    }
}
