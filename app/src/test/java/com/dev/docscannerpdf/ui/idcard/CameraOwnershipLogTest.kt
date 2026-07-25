package com.dev.docscannerpdf.ui.idcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera lifecycle logs must carry EXPLICIT owner attribution so the diagnostic ambiguity
 * (one controller a few seconds before Passport mounts, then another when it mounts) can be
 * resolved by evidence rather than by timing guesses: the first controller is owned by the ID
 * Cards entry preview, the second by Passport capture. These tests pin the exact, content-free
 * log contract that proves it.
 */
class CameraOwnershipLogTest {

    @Test
    fun createdLineMatchesTheOwnerTaggedContract() {
        assertEquals(
            "CAMERA_CONTROLLER owner=ID_CARDS_ENTRY created=c1",
            CameraOwnershipLog.created(CameraSurfaceOwner.ID_CARDS_ENTRY, "c1")
        )
    }

    @Test
    fun bindLineMatchesTheOwnerTaggedContract() {
        assertEquals(
            "CAMERA_BIND owner=PASSPORT_GUIDED_CAPTURE reason=initial controller=c2",
            CameraOwnershipLog.bind(CameraSurfaceOwner.PASSPORT_GUIDED_CAPTURE, "initial", "c2")
        )
    }

    @Test
    fun releasedLineMatchesTheOwnerTaggedContract() {
        assertEquals(
            "CAMERA_CONTROLLER owner=PASSPORT_GUIDED_CAPTURE released=c2",
            CameraOwnershipLog.released(CameraSurfaceOwner.PASSPORT_GUIDED_CAPTURE, "c2")
        )
    }

    @Test
    fun everyLifecycleLineCarriesItsOwnerAndControllerId() {
        // The core requirement: owner AND controller id appear on EVERY controller lifecycle line,
        // for every owner — so no line is ever ambiguous about which screen it belongs to.
        for (owner in CameraSurfaceOwner.entries) {
            val created = CameraOwnershipLog.created(owner, "cX")
            val bound = CameraOwnershipLog.bind(owner, "recovery", "cX")
            val released = CameraOwnershipLog.released(owner, "cX")
            for (line in listOf(created, bound, released)) {
                assertTrue("owner missing in: $line", line.contains("owner=${owner.name}"))
                assertTrue("controller id missing in: $line", line.contains("cX"))
            }
        }
    }

    @Test
    fun entryAndPassportProduceDistinctOwnerTags() {
        // The two controllers seen in every run must be separable purely by owner tag.
        val entry = CameraOwnershipLog.created(CameraSurfaceOwner.ID_CARDS_ENTRY, "c1")
        val passport = CameraOwnershipLog.created(CameraSurfaceOwner.PASSPORT_GUIDED_CAPTURE, "c2")

        assertTrue(entry.contains("owner=ID_CARDS_ENTRY"))
        assertTrue(passport.contains("owner=PASSPORT_GUIDED_CAPTURE"))
        assertFalse("entry must never be tagged as passport", entry.contains("PASSPORT"))
    }

    @Test
    fun theFourCameraOwnersAreDistinctAndComplete() {
        assertEquals(
            setOf(
                "ID_CARDS_ENTRY",
                "ID_CARD_GUIDED_CAPTURE",
                "PASSPORT_GUIDED_CAPTURE",
                "OTHER"
            ),
            CameraSurfaceOwner.entries.map { it.name }.toSet()
        )
    }

    @Test
    fun hostLineReportsEachCaptureSurfaceVisibilityIndependently() {
        // Replaces the misleading "surface=PASSPORT_CAPTURE captureVisible=false": passport is
        // visible, the ID-card guided surface is not, and BOTH are stated truthfully.
        assertEquals(
            "CAMERA_HOST surface=PASSPORT_CAPTURE " +
                "idCardCaptureVisible=false passportCaptureVisible=true",
            CameraOwnershipLog.host(
                surface = "PASSPORT_CAPTURE",
                idCardCaptureVisible = false,
                passportCaptureVisible = true
            )
        )
    }

    @Test
    fun logsAreContentFreeOfPathsAndDocumentData() {
        // A defensive guard: the formatters accept only an owner enum, a fixed reason and an id —
        // there is structurally no place for an image path or document data. Assert the output
        // contains none of the file-ish markers a leak would introduce.
        val lines = CameraSurfaceOwner.entries.flatMap { owner ->
            listOf(
                CameraOwnershipLog.created(owner, "c9"),
                CameraOwnershipLog.bind(owner, "initial", "c9"),
                CameraOwnershipLog.released(owner, "c9")
            )
        }
        for (line in lines) {
            assertFalse(line.contains("/"))
            assertFalse(line.contains("file:"))
            assertFalse(line.contains(".jpg"))
        }
    }
}
