package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Crop polygon editing. The central property under test is that the polygon can never be dragged
 * into a shape the warp engine cannot use — a bow-tie on screen reads to the user as a broken app,
 * so movement itself is constrained rather than validated later at Next.
 */
class MainScanCropEditorTest {

    private fun state(quad: PerspectiveQuad = PerspectiveQuad.inset(0.15f)) =
        MainScanCropEditor.initial(quad, MainScanPolygonSource.FROZEN_SEED)

    private fun moveHandle(
        state: MainScanCropState,
        handle: MainScanCropHandle,
        x: Float,
        y: Float,
        imageWidth: Int = 1_000,
        imageHeight: Int = 1_000
    ) = MainScanCropEditor.moveHandle(
        state = state,
        handle = handle,
        x = x,
        y = y,
        imageWidth = imageWidth,
        imageHeight = imageHeight
    )

    // --- initial state ---------------------------------------------------------------------------

    @Test
    fun theInitialPolygonIsOrderedConvexAndApplicable() {
        val s = state()
        assertTrue(PerspectiveGeometry.isConvex(s.quad))
        assertTrue(MainScanCropEditor.isApplicable(s))
        assertEquals(MainScanPolygonSource.FROZEN_SEED, s.source)
        assertNull(s.activeHandle)
        assertEquals(0, s.rotationQuarterTurns)
    }

    @Test
    fun aMisorderedSeedIsCorrectedRatherThanDiscarded() {
        // Corners supplied clockwise-from-bottom-right; normalisation must re-derive the roles.
        val scrambled = PerspectiveQuad(
            topLeft = CropPoint(0.8f, 0.8f),
            topRight = CropPoint(0.2f, 0.8f),
            bottomRight = CropPoint(0.2f, 0.2f),
            bottomLeft = CropPoint(0.8f, 0.2f)
        )
        val s = MainScanCropEditor.initial(scrambled, MainScanPolygonSource.STILL_DETECTION)
        assertTrue(PerspectiveGeometry.isConvex(s.quad))
        assertTrue(s.quad.topLeft.x < s.quad.topRight.x)
        assertTrue(s.quad.topLeft.y < s.quad.bottomLeft.y)
        assertEquals(MainScanPolygonSource.STILL_DETECTION, s.source)
    }

    @Test
    fun anUnusableSeedFallsBackToFullFrameAndSaysSo() {
        val degenerate = PerspectiveQuad(
            topLeft = CropPoint(0.5f, 0.5f),
            topRight = CropPoint(0.5f, 0.5f),
            bottomRight = CropPoint(0.5f, 0.5f),
            bottomLeft = CropPoint(0.5f, 0.5f)
        )
        val s = MainScanCropEditor.initial(degenerate, MainScanPolygonSource.FROZEN_SEED)
        assertEquals(MainScanCropEditor.fullFrame(), s.quad)
        assertEquals(
            "the source must not still claim a seed it could not use",
            MainScanPolygonSource.FULL_FRAME,
            s.source
        )
    }

    // --- corner dragging -------------------------------------------------------------------------

    @Test
    fun draggingACornerMovesOnlyThatCorner() {
        val s = state()
        val moved = moveHandle(s, MainScanCropHandle.TOP_LEFT, 0.05f, 0.05f)
        assertEquals(CropPoint(0.05f, 0.05f), moved.quad.topLeft)
        assertEquals("other corners untouched", s.quad.topRight, moved.quad.topRight)
        assertEquals(s.quad.bottomRight, moved.quad.bottomRight)
        assertEquals(s.quad.bottomLeft, moved.quad.bottomLeft)
    }

    @Test
    fun aDragThatWouldInvertThePolygonIsRefused() {
        val s = state()
        // Pull the top-left corner far past the bottom-right one — a classic bow-tie.
        val refused = moveHandle(s, MainScanCropHandle.TOP_LEFT, 0.99f, 0.99f)
        assertSame("the polygon must not fold over", s.quad, refused.quad)
        assertTrue(MainScanCropEditor.isApplicable(refused))
    }

    @Test
    fun everyCornerIsDraggableAndStaysApplicable() {
        var s = state()
        val targets = mapOf(
            MainScanCropHandle.TOP_LEFT to CropPoint(0.10f, 0.12f),
            MainScanCropHandle.TOP_RIGHT to CropPoint(0.92f, 0.08f),
            MainScanCropHandle.BOTTOM_RIGHT to CropPoint(0.88f, 0.94f),
            MainScanCropHandle.BOTTOM_LEFT to CropPoint(0.06f, 0.90f)
        )
        for ((handle, target) in targets) {
            s = moveHandle(s, handle, target.x, target.y)
            assertTrue("still applicable after moving $handle", MainScanCropEditor.isApplicable(s))
        }
        assertEquals(CropPoint(0.10f, 0.12f), s.quad.topLeft)
        assertEquals(CropPoint(0.92f, 0.08f), s.quad.topRight)
        assertEquals(CropPoint(0.88f, 0.94f), s.quad.bottomRight)
        assertEquals(CropPoint(0.06f, 0.90f), s.quad.bottomLeft)
    }

    @Test
    fun cornersAreClampedIntoTheImage() {
        val s = state()
        val moved = moveHandle(s, MainScanCropHandle.TOP_LEFT, -5f, -5f)
        assertTrue(moved.quad.topLeft.x >= 0f)
        assertTrue(moved.quad.topLeft.y >= 0f)
    }

    // --- edge dragging ---------------------------------------------------------------------------

    @Test
    fun draggingAnEdgeMovesBothOfItsCornersAndLeavesTheOppositeEdgeAlone() {
        val s = state()
        val topBefore = s.quad.topLeft.y
        val moved = moveHandle(s, MainScanCropHandle.TOP, 0.5f, 0.30f)

        assertTrue("top edge moved down", moved.quad.topLeft.y > topBefore)
        assertEquals(
            "both top corners move together",
            moved.quad.topLeft.y,
            moved.quad.topRight.y,
            1e-4f
        )
        assertEquals("bottom edge untouched", s.quad.bottomLeft, moved.quad.bottomLeft)
        assertEquals(s.quad.bottomRight, moved.quad.bottomRight)
    }

    @Test
    fun anEdgeCannotBeDraggedThroughItsOpposite() {
        val s = state()
        val refused = moveHandle(s, MainScanCropHandle.TOP, 0.5f, 0.99f)
        assertSame(s.quad, refused.quad)
    }

    @Test
    fun fastEdgeOvershootIsClampedByOneSharedTranslation() {
        val s = state(slantedQuad())
        val moved = moveHandle(s, MainScanCropHandle.TOP, -100f, -100f)
        val beforeA = s.quad.topLeft
        val beforeB = s.quad.topRight
        val afterA = moved.quad.topLeft
        val afterB = moved.quad.topRight

        assertTrue("the bounded edge movement must be accepted", moved.quad != s.quad)
        assertEquals(0f, afterA.y, 1e-6f)
        assertPointInUnit(afterA)
        assertPointInUnit(afterB)
        assertEquals(afterA.x - beforeA.x, afterB.x - beforeB.x, 1e-6f)
        assertEquals(afterA.y - beforeA.y, afterB.y - beforeB.y, 1e-6f)
        assertEquals(beforeB.x - beforeA.x, afterB.x - afterA.x, 1e-6f)
        assertEquals(beforeB.y - beforeA.y, afterB.y - afterA.y, 1e-6f)
        assertEquals(s.quad.bottomRight, moved.quad.bottomRight)
        assertEquals(s.quad.bottomLeft, moved.quad.bottomLeft)
        assertTrue(MainScanCropEditor.isApplicable(moved))
    }

    @Test
    fun everyEdgeOvershootKeepsBothEndpointsInsideTheImage() {
        val requests = mapOf(
            MainScanCropHandle.TOP to CropPoint(-100f, -100f),
            MainScanCropHandle.RIGHT to CropPoint(100f, -100f),
            MainScanCropHandle.BOTTOM to CropPoint(100f, 100f),
            MainScanCropHandle.LEFT to CropPoint(-100f, 100f)
        )

        for ((handle, request) in requests) {
            val original = state(slantedQuad())
            val (start, end) = MainScanCropEditor.edgeCorners(handle)
            val beforeStart = MainScanCropEditor.handlePosition(original.quad, start)
            val beforeEnd = MainScanCropEditor.handlePosition(original.quad, end)
            val moved = moveHandle(
                original,
                handle,
                request.x,
                request.y
            )
            val afterStart = MainScanCropEditor.handlePosition(moved.quad, start)
            val afterEnd = MainScanCropEditor.handlePosition(moved.quad, end)
            val dx = afterStart.x - beforeStart.x
            val dy = afterStart.y - beforeStart.y

            assertTrue("$handle overshoot must move the edge", moved.quad != original.quad)
            assertEquals("$handle shared dx", dx, afterEnd.x - beforeEnd.x, 1e-6f)
            assertEquals("$handle shared dy", dy, afterEnd.y - beforeEnd.y, 1e-6f)
            assertEquals(beforeEnd.x - beforeStart.x, afterEnd.x - afterStart.x, 1e-6f)
            assertEquals(beforeEnd.y - beforeStart.y, afterEnd.y - afterStart.y, 1e-6f)
            assertPointInUnit(afterStart)
            assertPointInUnit(afterEnd)
            assertTrue(
                "$handle must stop with an endpoint on the image boundary",
                isOnBoundary(afterStart) || isOnBoundary(afterEnd)
            )

            when (handle) {
                MainScanCropHandle.TOP -> {
                    assertEquals(original.quad.bottomRight, moved.quad.bottomRight)
                    assertEquals(original.quad.bottomLeft, moved.quad.bottomLeft)
                }
                MainScanCropHandle.RIGHT -> {
                    assertEquals(original.quad.bottomLeft, moved.quad.bottomLeft)
                    assertEquals(original.quad.topLeft, moved.quad.topLeft)
                }
                MainScanCropHandle.BOTTOM -> {
                    assertEquals(original.quad.topLeft, moved.quad.topLeft)
                    assertEquals(original.quad.topRight, moved.quad.topRight)
                }
                MainScanCropHandle.LEFT -> {
                    assertEquals(original.quad.topRight, moved.quad.topRight)
                    assertEquals(original.quad.bottomRight, moved.quad.bottomRight)
                }
                else -> error("expected an edge handle")
            }
        }
    }

    @Test
    fun repeatedEdgeOvershootDoesNotAccumulatePastTheBoundary() {
        val original = state(slantedQuad())
        var moved = moveHandle(
            original,
            MainScanCropHandle.TOP,
            -100f,
            -100f
        )
        val firstBoundedQuad = moved.quad

        assertTrue("the first overshoot must move the edge", firstBoundedQuad != original.quad)
        assertEquals(0f, firstBoundedQuad.topLeft.y, 1e-6f)
        repeat(20) {
            moved = moveHandle(
                moved,
                MainScanCropHandle.TOP,
                -100f,
                -100f
            )
            assertPointInUnit(moved.quad.topLeft)
            assertPointInUnit(moved.quad.topRight)
        }
        assertEquals(firstBoundedQuad, moved.quad)
    }

    @Test
    fun anEdgeCanMoveInwardAfterReachingTheBoundary() {
        val original = state(slantedQuad())
        val bounded = moveHandle(
            original,
            MainScanCropHandle.TOP,
            -100f,
            -100f
        )

        assertTrue("the outward move must be accepted", bounded.quad != original.quad)
        assertEquals(0f, bounded.quad.topLeft.y, 1e-6f)
        val midpoint = MainScanCropEditor.handlePosition(bounded.quad, MainScanCropHandle.TOP)
        val moved = moveHandle(
            bounded,
            MainScanCropHandle.TOP,
            midpoint.x + 0.10f,
            midpoint.y + 0.10f
        )
        val dxA = moved.quad.topLeft.x - bounded.quad.topLeft.x
        val dyA = moved.quad.topLeft.y - bounded.quad.topLeft.y

        assertTrue(dxA < 0f)
        assertTrue(dyA > 0f)
        assertEquals(dxA, moved.quad.topRight.x - bounded.quad.topRight.x, 1e-6f)
        assertEquals(dyA, moved.quad.topRight.y - bounded.quad.topRight.y, 1e-6f)
        assertPointInUnit(moved.quad.topLeft)
        assertPointInUnit(moved.quad.topRight)
    }

    @Test
    fun invalidGeometryStillPreservesThePreviousQuad() {
        val s = state()
        val refused = moveHandle(s, MainScanCropHandle.TOP, 0.5f, 0.99f)

        assertEquals(s.quad, refused.quad)
    }

    @Test
    fun purePerpendicularDragMovesTheEdgeAlongItsPixelSpaceNormal() {
        val original = state()
        val moved = moveHandle(original, MainScanCropHandle.TOP, 0.5f, 0.30f)

        assertEquals(0f, moved.quad.topLeft.x - original.quad.topLeft.x, 1e-6f)
        assertEquals(0.15f, moved.quad.topLeft.y - original.quad.topLeft.y, 1e-6f)
        assertEquals(0f, moved.quad.topRight.x - original.quad.topRight.x, 1e-6f)
        assertEquals(0.15f, moved.quad.topRight.y - original.quad.topRight.y, 1e-6f)
    }

    @Test
    fun pureTangentialDragProducesNoQuadMovement() {
        val original = state()
        val moved = moveHandle(original, MainScanCropHandle.TOP, 0.75f, 0.15f)

        assertEquals(original.quad, moved.quad)
    }

    @Test
    fun diagonalDragRetainsOnlyItsNormalComponent() {
        val original = state()
        val perpendicular = moveHandle(original, MainScanCropHandle.TOP, 0.5f, 0.30f)
        val diagonal = moveHandle(original, MainScanCropHandle.TOP, 0.75f, 0.30f)

        assertEquals(perpendicular.quad, diagonal.quad)
    }

    @Test
    fun slantedTopEdgeObeysNormalOnlyMovement() {
        assertSlantedEdgeNormalOnly(MainScanCropHandle.TOP)
    }

    @Test
    fun slantedRightEdgeObeysNormalOnlyMovement() {
        assertSlantedEdgeNormalOnly(MainScanCropHandle.RIGHT)
    }

    @Test
    fun slantedBottomEdgeObeysNormalOnlyMovement() {
        assertSlantedEdgeNormalOnly(MainScanCropHandle.BOTTOM)
    }

    @Test
    fun slantedLeftEdgeObeysNormalOnlyMovement() {
        assertSlantedEdgeNormalOnly(MainScanCropHandle.LEFT)
    }

    @Test
    fun nonSquareImageUsesPixelMetricProjection() {
        val original = state(
            PerspectiveQuad(
                topLeft = CropPoint(0.2f, 0.2f),
                topRight = CropPoint(0.8f, 0.4f),
                bottomRight = CropPoint(0.85f, 0.85f),
                bottomLeft = CropPoint(0.15f, 0.8f)
            )
        )
        val moved = moveHandle(
            state = original,
            handle = MainScanCropHandle.TOP,
            x = 0.6f,
            y = 0.4f,
            imageWidth = 200,
            imageHeight = 100
        )
        val dx = moved.quad.topLeft.x - original.quad.topLeft.x
        val dy = moved.quad.topLeft.y - original.quad.topLeft.y

        assertEquals(-0.005405405f, dx, 1e-6f)
        assertEquals(0.06486486f, dy, 1e-6f)
        assertEquals(dx, moved.quad.topRight.x - original.quad.topRight.x, 1e-6f)
        assertEquals(dy, moved.quad.topRight.y - original.quad.topRight.y, 1e-6f)
        assertTrue("naive normalized projection would produce dx=-0.02", kotlin.math.abs(dx + 0.02f) > 0.01f)
    }

    @Test
    fun edgeProjectionFailsClosedForInvalidInputs() {
        val original = state()

        assertSame(
            original,
            moveHandle(original, MainScanCropHandle.TOP, Float.NaN, 0.2f)
        )
        assertSame(
            original,
            moveHandle(original, MainScanCropHandle.TOP, 0.5f, 0.2f, imageWidth = 0)
        )
        assertSame(
            original,
            moveHandle(original, MainScanCropHandle.TOP, 0.5f, 0.2f, imageHeight = -1)
        )

        val degenerateQuad = original.quad.copy(topRight = original.quad.topLeft)
        val degenerate = original.copy(quad = degenerateQuad)
        assertSame(
            degenerate,
            moveHandle(degenerate, MainScanCropHandle.TOP, 0.5f, 0.2f)
        )
    }

    @Test
    fun everyEdgeHandleSitsAtItsMidpoint() {
        val s = state()
        for (handle in listOf(
            MainScanCropHandle.TOP,
            MainScanCropHandle.RIGHT,
            MainScanCropHandle.BOTTOM,
            MainScanCropHandle.LEFT
        )) {
            val (a, b) = MainScanCropEditor.edgeCorners(handle)
            val pa = MainScanCropEditor.handlePosition(s.quad, a)
            val pb = MainScanCropEditor.handlePosition(s.quad, b)
            val mid = MainScanCropEditor.handlePosition(s.quad, handle)
            assertEquals((pa.x + pb.x) / 2f, mid.x, 1e-4f)
            assertEquals((pa.y + pb.y) / 2f, mid.y, 1e-4f)
        }
    }

    // --- hit testing -----------------------------------------------------------------------------

    @Test
    fun allEightHandlesAreSelectableAtTheirDisplayedPositions() {
        val s = state()
        for (handle in MainScanCropHandle.entries) {
            val position = MainScanCropEditor.handlePosition(s.quad, handle)
            assertEquals(
                handle,
                MainScanCropEditor.handleAt(s.quad, position.x, position.y, 800f, 1_200f, 1f)
            )
        }
    }

    @Test
    fun aPointerOutsideThePixelRadiusSelectsNothing() {
        val s = state()
        assertNull(
            "a touch far from every handle grabs nothing",
            MainScanCropEditor.handleAt(s.quad, 0.5f, 0.5f, 800f, 1_200f, 100f)
        )
    }

    @Test
    fun nonSquareRenderedGeometryUsesDisplayPixelDistance() {
        val selected = MainScanCropEditor.handleAt(
            quad = state().quad,
            xNormalized = 0.40f,
            yNormalized = 0.45f,
            renderedWidthPx = 1_000f,
            renderedHeightPx = 100f,
            radiusPx = 300f
        )

        assertEquals(
            "normalized Euclidean distance favors LEFT here, but display pixels favor TOP",
            MainScanCropHandle.TOP,
            selected
        )
    }

    @Test
    fun measurablyNearestOverlappingHandleWinsEvenWhenItIsAnEdge() {
        val tight = PerspectiveQuad(
            topLeft = CropPoint(0.4f, 0.4f),
            topRight = CropPoint(0.6f, 0.4f),
            bottomRight = CropPoint(0.6f, 0.6f),
            bottomLeft = CropPoint(0.4f, 0.6f)
        )

        assertEquals(
            MainScanCropHandle.TOP,
            MainScanCropEditor.handleAt(tight, 0.49f, 0.4f, 1_000f, 1_000f, 100f)
        )
    }

    @Test
    fun aCornerWinsAGenuineEqualDistanceTieWithAnEdge() {
        val tight = PerspectiveQuad(
            topLeft = CropPoint(0.4f, 0.4f),
            topRight = CropPoint(0.6f, 0.4f),
            bottomRight = CropPoint(0.6f, 0.6f),
            bottomLeft = CropPoint(0.4f, 0.6f)
        )

        assertEquals(
            MainScanCropHandle.TOP_LEFT,
            MainScanCropEditor.handleAt(tight, 0.45f, 0.4f, 1_000f, 1_000f, 60f)
        )
    }

    @Test
    fun sameClassTieUsesTheExplicitStableRank() {
        val s = state()

        assertEquals(
            "all four edge handles are equally distant; TOP has the documented stable rank",
            MainScanCropHandle.TOP,
            MainScanCropEditor.handleAt(s.quad, 0.5f, 0.5f, 1_000f, 1_000f, 400f)
        )
    }

    // --- drag lifecycle --------------------------------------------------------------------------

    @Test
    fun dragStateTracksTheActiveHandleForTheMagnifier() {
        var s = state()
        assertFalse(s.isDragging)
        s = MainScanCropEditor.beginDrag(s, MainScanCropHandle.BOTTOM_RIGHT)
        assertTrue(s.isDragging)
        assertEquals(MainScanCropHandle.BOTTOM_RIGHT, s.activeHandle)
        s = MainScanCropEditor.endDrag(s)
        assertFalse("the magnifier must vanish the instant the drag ends", s.isDragging)
        assertNull(s.activeHandle)
    }

    // --- rotation ---------------------------------------------------------------------------------

    @Test
    fun rotatingRightTurnsThePolygonWithTheImage() {
        val s = MainScanCropEditor.initial(
            PerspectiveQuad(
                topLeft = CropPoint(0.1f, 0.4f),
                topRight = CropPoint(0.9f, 0.4f),
                bottomRight = CropPoint(0.9f, 0.6f),
                bottomLeft = CropPoint(0.1f, 0.6f)
            ),
            MainScanPolygonSource.FROZEN_SEED
        )
        val rotated = MainScanCropEditor.rotate(s, MainScanRotation.RIGHT)
        val width = rotated.quad.topRight.x - rotated.quad.topLeft.x
        val height = rotated.quad.bottomLeft.y - rotated.quad.topLeft.y
        assertTrue("a wide crop becomes tall", height > width)
        assertEquals(1, rotated.rotationQuarterTurns)
        assertTrue(MainScanCropEditor.isApplicable(rotated))
    }

    @Test
    fun rotatingLeftIsTheInverseOfRotatingRight() {
        val s = state()
        val roundTrip = MainScanCropEditor.rotate(
            MainScanCropEditor.rotate(s, MainScanRotation.RIGHT),
            MainScanRotation.LEFT
        )
        assertEquals(0, roundTrip.rotationQuarterTurns)
        s.quad.corners().forEachIndexed { index, expected ->
            val actual = roundTrip.quad.corners()[index]
            assertEquals("corner $index x", expected.x, actual.x, 1e-4f)
            assertEquals("corner $index y", expected.y, actual.y, 1e-4f)
        }
    }

    @Test
    fun fourRotationsReturnToTheStartingOrientation() {
        var s = state()
        repeat(4) { s = MainScanCropEditor.rotate(s, MainScanRotation.RIGHT) }
        assertEquals(0, s.rotationQuarterTurns)
    }

    @Test
    fun rotationDoesNotResetTheUsersCrop() {
        // Rotation must not be destructive — only All resets.
        val s = moveHandle(state(), MainScanCropHandle.TOP_LEFT, 0.25f, 0.30f)
        val rotated = MainScanCropEditor.rotate(s, MainScanRotation.RIGHT)
        assertFalse(
            "the crop must survive a rotation",
            rotated.quad == MainScanCropEditor.fullFrame()
        )
    }

    @Test
    fun rotationEndsAnyActiveDrag() {
        val dragging = MainScanCropEditor.beginDrag(state(), MainScanCropHandle.TOP)
        assertFalse(MainScanCropEditor.rotate(dragging, MainScanRotation.LEFT).isDragging)
    }

    // --- All / Next --------------------------------------------------------------------------------

    @Test
    fun allResetsToTheCompleteImageBounds() {
        val s = moveHandle(state(), MainScanCropHandle.TOP_LEFT, 0.3f, 0.3f)
        val reset = MainScanCropEditor.resetToFullFrame(s)
        assertEquals(MainScanCropEditor.fullFrame(), reset.quad)
        assertEquals(MainScanPolygonSource.FULL_FRAME, reset.source)
        assertTrue(MainScanCropEditor.isApplicable(reset))
    }

    @Test
    fun allPreservesRotation() {
        val rotated = MainScanCropEditor.rotate(state(), MainScanRotation.RIGHT)
        assertEquals(1, MainScanCropEditor.resetToFullFrame(rotated).rotationQuarterTurns)
    }

    @Test
    fun nextRejectsInvalidGeometryAndAcceptsValidGeometry() {
        assertTrue(MainScanCropEditor.isApplicable(MainScanCropEditor.fullFrame()))

        val slivered = PerspectiveQuad(
            topLeft = CropPoint(0.10f, 0.50f),
            topRight = CropPoint(0.90f, 0.50f),
            bottomRight = CropPoint(0.90f, 0.51f),
            bottomLeft = CropPoint(0.10f, 0.51f)
        )
        assertFalse("a sliver has nothing to crop to", MainScanCropEditor.isApplicable(slivered))

        val crossed = PerspectiveQuad(
            topLeft = CropPoint(0.1f, 0.1f),
            topRight = CropPoint(0.9f, 0.9f),
            bottomRight = CropPoint(0.9f, 0.1f),
            bottomLeft = CropPoint(0.1f, 0.9f)
        )
        assertFalse(MainScanCropEditor.isApplicable(crossed))
    }

    @Test
    fun theWorkflowGatesNextOnPolygonValidity() {
        assertTrue(
            MainScanWorkflow.allowsAdvanceFromCrop(MainScanStage.CropEditing, polygonValid = true)
        )
        assertFalse(
            MainScanWorkflow.allowsAdvanceFromCrop(MainScanStage.CropEditing, polygonValid = false)
        )
        assertFalse(
            "and never from a stage that is already processing",
            MainScanWorkflow.allowsAdvanceFromCrop(MainScanStage.Cropping, polygonValid = true)
        )
    }

    @Test
    fun handlePositionsAreDefinedForAllEightHandles() {
        val s = state()
        for (handle in MainScanCropHandle.entries) {
            assertNotNull(MainScanCropEditor.handlePosition(s.quad, handle))
        }
        assertEquals(8, MainScanCropHandle.entries.size)
    }

    private fun assertSlantedEdgeNormalOnly(handle: MainScanCropHandle) {
        val original = state(slantedQuad())
        val (startHandle, endHandle) = MainScanCropEditor.edgeCorners(handle)
        val start = MainScanCropEditor.handlePosition(original.quad, startHandle)
        val end = MainScanCropEditor.handlePosition(original.quad, endHandle)
        val midpoint = MainScanCropEditor.handlePosition(original.quad, handle)
        val edgePixelX = (end.x - start.x) * 1_000f
        val edgePixelY = (end.y - start.y) * 1_000f
        val edgeLength = hypot(edgePixelX, edgePixelY)
        val expectedDx = 40f * (-edgePixelY / edgeLength) / 1_000f
        val expectedDy = 40f * (edgePixelX / edgeLength) / 1_000f
        val moved = moveHandle(
            original,
            handle,
            midpoint.x + expectedDx,
            midpoint.y + expectedDy
        )
        val movedStart = MainScanCropEditor.handlePosition(moved.quad, startHandle)
        val movedEnd = MainScanCropEditor.handlePosition(moved.quad, endHandle)
        val actualDx = movedStart.x - start.x
        val actualDy = movedStart.y - start.y

        assertEquals("$handle pixel-normal dx", expectedDx, actualDx, 1e-6f)
        assertEquals("$handle pixel-normal dy", expectedDy, actualDy, 1e-6f)
        assertEquals("$handle shared dx", actualDx, movedEnd.x - end.x, 1e-6f)
        assertEquals("$handle shared dy", actualDy, movedEnd.y - end.y, 1e-6f)
        assertEquals("$handle preserved edge x", end.x - start.x, movedEnd.x - movedStart.x, 1e-6f)
        assertEquals("$handle preserved edge y", end.y - start.y, movedEnd.y - movedStart.y, 1e-6f)
        assertOppositeCornersUnchanged(handle, original.quad, moved.quad)
    }

    private fun assertOppositeCornersUnchanged(
        handle: MainScanCropHandle,
        before: PerspectiveQuad,
        after: PerspectiveQuad
    ) {
        when (handle) {
            MainScanCropHandle.TOP -> {
                assertEquals(before.bottomRight, after.bottomRight)
                assertEquals(before.bottomLeft, after.bottomLeft)
            }
            MainScanCropHandle.RIGHT -> {
                assertEquals(before.bottomLeft, after.bottomLeft)
                assertEquals(before.topLeft, after.topLeft)
            }
            MainScanCropHandle.BOTTOM -> {
                assertEquals(before.topLeft, after.topLeft)
                assertEquals(before.topRight, after.topRight)
            }
            MainScanCropHandle.LEFT -> {
                assertEquals(before.topRight, after.topRight)
                assertEquals(before.bottomRight, after.bottomRight)
            }
            else -> error("expected an edge handle")
        }
    }

    private fun isOnBoundary(point: CropPoint): Boolean =
        kotlin.math.abs(point.x) < 1e-6f ||
            kotlin.math.abs(point.x - 1f) < 1e-6f ||
            kotlin.math.abs(point.y) < 1e-6f ||
            kotlin.math.abs(point.y - 1f) < 1e-6f

    private fun slantedQuad() = PerspectiveQuad(
        topLeft = CropPoint(0.25f, 0.20f),
        topRight = CropPoint(0.80f, 0.35f),
        bottomRight = CropPoint(0.85f, 0.85f),
        bottomLeft = CropPoint(0.15f, 0.80f)
    )

    private fun assertPointInUnit(point: CropPoint) {
        assertTrue("x=${point.x} is outside [0, 1]", point.x in 0f..1f)
        assertTrue("y=${point.y} is outside [0, 1]", point.y in 0f..1f)
    }
}
