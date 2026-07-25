package com.dev.docscannerpdf.domain.idscan

import com.dev.docscannerpdf.domain.filter.DocumentFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the generation discipline of the in-memory preview pipeline: the downscaled base is
 * decoded once per base generation, the LATEST preview/final generation always wins, stale
 * completions can never publish, and ending the review releases every cached reference.
 * Pure JVM — no Bitmap method is ever invoked, only the session's token/ownership logic.
 */
class PassportPreviewPipelineTest {

    // --- base generation / decode-once discipline ---

    @Test
    fun installingABaseBumpsTheGenerationAndClearsTheCachedBitmaps() {
        val session = PassportReviewSession()
        val before = session.baseGeneration

        session.installBase()

        assertEquals(before + 1, session.baseGeneration)
        assertNull("the OLD base's downscaled bitmap must never survive a base swap", session.downscaledBase)
        assertNull(session.currentPreview)
    }

    @Test
    fun aCropSettlementInvalidatesAndRebuildsTheCache() {
        val session = PassportReviewSession()
        session.installBase()
        val reviewOpenGeneration = session.baseGeneration

        // A crop settles: the new full-resolution base is installed and the cache must rebuild.
        session.installBase()

        assertEquals(reviewOpenGeneration + 1, session.baseGeneration)
        assertNull("the pre-crop base bitmap must not be reused", session.downscaledBase)
    }

    // --- preview generation: latest wins ---

    @Test
    fun previewGenerationsAreMonotonicAndOnlyTheLatestIsCurrent() {
        val session = PassportReviewSession()

        val g12 = session.beginPreview()
        val g13 = session.beginPreview()

        assertEquals(g12 + 1, g13)
        assertFalse("generation 12 cannot publish once 13 started", session.isPreviewCurrent(g12))
        assertTrue("generation 13 remains publishable", session.isPreviewCurrent(g13))
    }

    @Test
    fun rapidRotationTapsLeaveExactlyTheFinalGenerationPublishable() {
        val session = PassportReviewSession()
        val generations = (1..4).map { session.beginPreview() }

        val stale = generations.dropLast(1).filter { session.isPreviewCurrent(it) }

        assertTrue("no superseded generation may publish", stale.isEmpty())
        assertTrue(session.isPreviewCurrent(generations.last()))
    }

    @Test
    fun finalGenerationsFollowTheSameLatestWinsRule() {
        val session = PassportReviewSession()

        val first = session.beginFinal()
        val second = session.beginFinal()

        assertFalse("a superseded authoritative render must be rejected", session.isFinalCurrent(first))
        assertTrue(session.isFinalCurrent(second))
    }

    @Test
    fun previewAndFinalGenerationsAreIndependentTracks() {
        val session = PassportReviewSession()
        val preview = session.beginPreview()

        session.beginFinal()

        assertTrue("a final render starting must not invalidate the live preview", session.isPreviewCurrent(preview))
    }

    // --- teardown ---

    @Test
    fun leavingTheReviewClearsEveryCachedReference() {
        val session = PassportReviewSession()
        session.installBase()
        session.beginPreview()

        session.clear()

        assertNull(session.downscaledBase)
        assertNull(session.currentPreview)
    }

    // --- effect chain: rotation math and identity ---

    @Test
    fun fourQuarterTurnRotationsReturnExactlyToTheOriginalOrientation() {
        var degrees = 0
        repeat(4) { degrees = (degrees + 90) % 360 }

        assertEquals(0, PassportEffectChain.quarterTurns(degrees))
    }

    @Test
    fun quarterTurnsMapEveryReviewAngleToItsPixelRotation() {
        assertEquals(0, PassportEffectChain.quarterTurns(0))
        assertEquals(1, PassportEffectChain.quarterTurns(90))
        assertEquals(2, PassportEffectChain.quarterTurns(180))
        assertEquals(3, PassportEffectChain.quarterTurns(270))
        assertEquals(0, PassportEffectChain.quarterTurns(360))
    }

    @Test
    fun defaultChainIsTheIdentityAndAnyEffectBreaksIt() {
        assertTrue(PassportEffectChain().isIdentity)
        assertFalse(PassportEffectChain(rotationQuarterTurns = 1).isIdentity)
        assertFalse(PassportEffectChain(filter = DocumentFilter.ENHANCE).isIdentity)
        assertFalse(PassportEffectChain(watermarkText = "CONFIDENTIAL").isIdentity)
        assertFalse(
            PassportEffectChain(crop = PassportCropRect(0.1f, 0.1f, 0.9f, 0.9f)).isIdentity
        )
    }

    @Test
    fun requestedChainTracksTheLatestOperation() {
        val session = PassportReviewSession()
        val chain = PassportEffectChain(
            rotationQuarterTurns = 1,
            filter = DocumentFilter.BW,
            watermarkText = "COPY"
        )

        session.updateRequestedChain(chain)

        assertEquals(chain, session.requestedChain)
    }
}
