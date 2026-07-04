package com.dev.docscannerpdf.domain.idscan

/**
 * Validates how many pages a single ID-card scan session may produce. An ID card only ever
 * has two sides — a required front and an optional back — so anything outside that range
 * means the scanner captured something other than a card (or the user scanned extra pages).
 * Kept pure/free of Android and ML Kit types so the rule can be unit tested directly.
 */
object IdCardScanRules {
    const val MIN_PAGE_COUNT = 1
    const val MAX_PAGE_COUNT = 2

    fun isValidPageCount(pageCount: Int): Boolean = pageCount in MIN_PAGE_COUNT..MAX_PAGE_COUNT
}
