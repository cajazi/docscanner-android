package com.dev.docscannerpdf.domain.mainscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the Main Scanner temp-file ownership guard. The security-relevant assertions
 * are the rejection ones: a gallery `content://` original must be structurally undeletable.
 */
class MainScanFileOwnershipTest {

    private val filesDir = "/data/user/0/com.dev.docscannerpdf/files"

    private fun owned(name: String) = "file://$filesDir/main_scan_capture/$name"

    private fun stateWith(
        ownedUris: Set<String> = emptySet(),
        pendingUri: String? = null
    ) = MainScanCaptureState(
        ownedUris = ownedUris,
        pendingPage = pendingUri?.let {
            MainScanPendingPage(uri = it, source = MainScanPageSource.CAMERA, captureGeneration = 1L)
        }
    )

    // --- referencedUris -------------------------------------------------------------------------

    @Test
    fun nullStateReferencesNothing() {
        assertTrue(MainScanFileOwnership.referencedUris(null).isEmpty())
    }

    @Test
    fun referencesLedgerAndPendingPage() {
        val state = stateWith(ownedUris = setOf(owned("a.jpg")), pendingUri = owned("b.jpg"))
        assertEquals(setOf(owned("a.jpg"), owned("b.jpg")), MainScanFileOwnership.referencedUris(state))
    }

    @Test
    fun blankEntriesAreIgnored() {
        val state = stateWith(ownedUris = setOf("", "   ", owned("a.jpg")))
        assertEquals(setOf(owned("a.jpg")), MainScanFileOwnership.referencedUris(state))
    }

    // --- supersededUris -------------------------------------------------------------------------

    @Test
    fun supersededReturnsOnlyWhatTheNewStateDropped() {
        val before = stateWith(ownedUris = setOf(owned("old.jpg"), owned("kept.jpg")))
        val after = stateWith(ownedUris = setOf(owned("kept.jpg")))
        assertEquals(
            setOf(owned("old.jpg")),
            MainScanFileOwnership.supersededUris(before, after)
        )
    }

    @Test
    fun aUriTheNewStateStillReferencesIsNeverSuperseded() {
        val before = stateWith(ownedUris = setOf(owned("a.jpg")))
        val after = stateWith(ownedUris = setOf(owned("a.jpg")))
        assertTrue(MainScanFileOwnership.supersededUris(before, after).isEmpty())
    }

    @Test
    fun protectedUrisAreNeverSuperseded() {
        val before = stateWith(ownedUris = setOf(owned("handing-forward.jpg")))
        val after = stateWith()
        assertTrue(
            MainScanFileOwnership.supersededUris(
                before = before,
                after = after,
                protectedUris = setOf(owned("handing-forward.jpg"))
            ).isEmpty()
        )
    }

    // --- visitOrphans ---------------------------------------------------------------------------

    @Test
    fun visitOrphansCoversLedgerAndPendingPageMinusRetained() {
        val state = stateWith(
            ownedUris = setOf(owned("a.jpg"), owned("b.jpg")),
            pendingUri = owned("page.jpg")
        )
        assertEquals(
            setOf(owned("a.jpg"), owned("b.jpg")),
            MainScanFileOwnership.visitOrphans(state, retainUris = setOf(owned("page.jpg")))
        )
    }

    @Test
    fun visitOrphansOfNothingIsEmpty() {
        assertTrue(MainScanFileOwnership.visitOrphans(null).isEmpty())
        assertTrue(MainScanFileOwnership.visitOrphans(stateWith()).isEmpty())
    }

    // --- isOwnedFileUri: the deletion guard -----------------------------------------------------

    @Test
    fun appPrivateFileUriIsOwned() {
        assertTrue(MainScanFileOwnership.isOwnedFileUri(owned("a.jpg"), filesDir))
        assertTrue(MainScanFileOwnership.isOwnedFileUri("file://$filesDir", filesDir))
    }

    @Test
    fun trailingSlashOnFilesDirIsTolerated() {
        assertTrue(MainScanFileOwnership.isOwnedFileUri(owned("a.jpg"), "$filesDir/"))
    }

    @Test
    fun galleryContentUriIsNeverOwned() {
        assertFalse(
            "a user's gallery original must be structurally undeletable",
            MainScanFileOwnership.isOwnedFileUri(
                "content://media/external/images/media/12345",
                filesDir
            )
        )
    }

    @Test
    fun pathsOutsidePrivateStorageAreNeverOwned() {
        assertFalse(MainScanFileOwnership.isOwnedFileUri("file:///sdcard/DCIM/photo.jpg", filesDir))
        assertFalse(MainScanFileOwnership.isOwnedFileUri("file:///data/user/0/other.app/files/x.jpg", filesDir))
        // A sibling directory that merely shares the prefix must not match.
        assertFalse(MainScanFileOwnership.isOwnedFileUri("file://${filesDir}x/evil.jpg", filesDir))
    }

    @Test
    fun traversalIsRejected() {
        assertFalse(
            MainScanFileOwnership.isOwnedFileUri("file://$filesDir/../../evil.jpg", filesDir)
        )
    }

    // --- visit-start orphan sweep bound ---------------------------------------------------------

    @Test
    fun onlyFilesPredatingTheVisitAreReclaimable() {
        val visitOpenedAt = 1_000_000L
        assertTrue(
            "a file from a dead earlier visit is an orphan",
            MainScanFileOwnership.isReclaimableOrphan(visitOpenedAt - 1, visitOpenedAt)
        )
        assertFalse(
            "a file this visit captured must NEVER be swept",
            MainScanFileOwnership.isReclaimableOrphan(visitOpenedAt + 1, visitOpenedAt)
        )
    }

    @Test
    fun aFileWrittenInTheSameMillisecondAsVisitOpeningIsNotReclaimed() {
        // Filesystem timestamp granularity is coarse. On a tie the file must be treated as this
        // visit's, so the sweep can never race a capture and delete its own pending page.
        val visitOpenedAt = 1_000_000L
        assertFalse(MainScanFileOwnership.isReclaimableOrphan(visitOpenedAt, visitOpenedAt))
    }

    @Test
    fun theSweepBoundHoldsAcrossAWholeCaptureTimeline() {
        // Two orphans from a killed visit, then this visit opens, then it captures twice.
        val visitOpenedAt = 500L
        val timeline = listOf(100L to true, 499L to true, 500L to false, 501L to false, 900L to false)
        for ((modified, expected) in timeline) {
            assertEquals(
                "lastModified=$modified vs visitOpenedAt=$visitOpenedAt",
                expected,
                MainScanFileOwnership.isReclaimableOrphan(modified, visitOpenedAt)
            )
        }
    }

    @Test
    fun otherSchemesAndBlanksAreRejected() {
        assertFalse(MainScanFileOwnership.isOwnedFileUri("https://example.com/a.jpg", filesDir))
        assertFalse(MainScanFileOwnership.isOwnedFileUri(null, filesDir))
        assertFalse(MainScanFileOwnership.isOwnedFileUri("", filesDir))
        assertFalse(MainScanFileOwnership.isOwnedFileUri(owned("a.jpg"), ""))
    }
}
