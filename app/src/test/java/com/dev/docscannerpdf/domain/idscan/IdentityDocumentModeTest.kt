package com.dev.docscannerpdf.domain.idscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityDocumentModeTest {

    @Test
    fun entryLabelsMapToModes() {
        assertEquals(IdentityDocumentMode.GENERAL, IdentityDocumentMode.fromEntryLabel("General"))
        assertEquals(IdentityDocumentMode.ID_CARD, IdentityDocumentMode.fromEntryLabel("ID Card"))
        assertEquals(IdentityDocumentMode.DRIVER_LICENSE, IdentityDocumentMode.fromEntryLabel("Driver License"))
        assertEquals(IdentityDocumentMode.PASSPORT, IdentityDocumentMode.fromEntryLabel("Passport"))
        assertEquals(IdentityDocumentMode.BANK_CARD, IdentityDocumentMode.fromEntryLabel("Bank Card"))
    }

    @Test
    fun unknownOrBlankLabelFallsBackToIdCard() {
        assertEquals(IdentityDocumentMode.ID_CARD, IdentityDocumentMode.fromEntryLabel(null))
        assertEquals(IdentityDocumentMode.ID_CARD, IdentityDocumentMode.fromEntryLabel(""))
        assertEquals(IdentityDocumentMode.ID_CARD, IdentityDocumentMode.fromEntryLabel("Nonsense"))
    }

    @Test
    fun labelMappingTrimsWhitespace() {
        assertEquals(IdentityDocumentMode.PASSPORT, IdentityDocumentMode.fromEntryLabel("  Passport  "))
    }

    @Test
    fun onlyPassportIsSinglePagePortrait() {
        assertTrue(IdentityDocumentMode.PASSPORT.isSinglePagePortrait)
        IdentityDocumentMode.entries.filter { it != IdentityDocumentMode.PASSPORT }.forEach {
            assertFalse("${it.name} must not be single-page portrait", it.isSinglePagePortrait)
        }
    }
}
