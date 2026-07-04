package com.dev.docscannerpdf.domain.idscan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdCardScanRulesTest {

    @Test
    fun onePageIsAcceptedAsFrontOnly() {
        assertTrue(IdCardScanRules.isValidPageCount(1))
    }

    @Test
    fun twoPagesIsAcceptedAsFrontAndBack() {
        assertTrue(IdCardScanRules.isValidPageCount(2))
    }

    @Test
    fun threePagesIsRejected() {
        assertFalse(IdCardScanRules.isValidPageCount(3))
    }

    @Test
    fun zeroPagesIsRejected() {
        assertFalse(IdCardScanRules.isValidPageCount(0))
    }
}
