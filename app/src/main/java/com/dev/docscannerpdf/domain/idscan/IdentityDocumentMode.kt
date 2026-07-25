package com.dev.docscannerpdf.domain.idscan

/**
 * The identity-document type chosen on the "ID Cards" entry screen. Only two modes have a
 * dedicated guided flow today: [ID_CARD] (the existing front-then-back card capture) and
 * [PASSPORT] (a single portrait information-page capture). [GENERAL], [DRIVER_LICENSE], and
 * [BANK_CARD] are card-shaped documents that reuse the ID-card guided flow — no unfinished fake
 * flows are introduced for them. Passport is deliberately kept OFF the ID-card Front/Back state
 * machine so its single-page workflow can never regress the working two-sided card workflow.
 */
enum class IdentityDocumentMode {
    GENERAL,
    ID_CARD,
    DRIVER_LICENSE,
    PASSPORT,
    BANK_CARD;

    /** True when this mode captures ONE portrait page (passport) rather than front+back sides. */
    val isSinglePagePortrait: Boolean get() = this == PASSPORT

    companion object {
        /**
         * Maps an entry-screen chip label to its mode. The labels are the exact strings the
         * [com.dev.docscannerpdf.ui.IdCardFeatureScreen] chip row renders; an unknown/blank
         * label falls back to [ID_CARD] so "Make it now" always has a valid flow.
         */
        fun fromEntryLabel(label: String?): IdentityDocumentMode = when (label?.trim()) {
            "General" -> GENERAL
            "Driver License" -> DRIVER_LICENSE
            "Passport" -> PASSPORT
            "Bank Card" -> BANK_CARD
            "ID Card" -> ID_CARD
            else -> ID_CARD
        }
    }
}
