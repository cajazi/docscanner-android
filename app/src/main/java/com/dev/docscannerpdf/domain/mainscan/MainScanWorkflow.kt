package com.dev.docscannerpdf.domain.mainscan

/**
 * The deterministic stages one captured page moves through, from the accepted shutter frame to a
 * saved document. Named states rather than a pile of booleans so an illegal combination — "cropping
 * and enhancing at once", "persisting without a confirmed filter" — is not representable.
 *
 * The order is fixed by the locked reference (`docs/main-scanner-reference.md`):
 *
 *     CameraReady -> Capturing -> CaptureAccepted -> CropPreparing -> CropEditing
 *       -> Cropping -> EnhancementPreparing -> EnhancementReview -> Confirming
 *       -> Persisting -> Completed
 *
 * with [Failed] and [Discarded] reachable from the working stages.
 */
enum class MainScanStage {
    /** Preview streaming, shutter armed. */
    CameraReady,

    /** A capture is in flight. The preview keeps streaming. */
    Capturing,

    /** The JPEG exists and belongs to this workflow. Nothing is persisted. */
    CaptureAccepted,

    /** Decoding/EXIF-normalising the capture and resolving the initial polygon. */
    CropPreparing,

    /** The user is editing the polygon. Corner and edge handles are live. */
    CropEditing,

    /** Perspective correction is running. The image stays on screen behind the progress. */
    Cropping,

    /** The cropped page exists; enhancement is starting. */
    EnhancementPreparing,

    /** Filter review. The user picks a filter; nothing is written yet. */
    EnhancementReview,

    /** Confirm accepted, single-flight. The final page image is being produced. */
    Confirming,

    /** The document and its page are being written. */
    Persisting,

    /** Exactly one document exists and the viewer may open. */
    Completed,

    /** A stage failed recoverably. The captured original is never destroyed by a failure. */
    Failed,

    /** The user discarded. Owned temporary files are swept. */
    Discarded
}

/**
 * Where the initial crop polygon came from. Recorded rather than inferred because the three sources
 * have materially different trust levels, and a later slice must be able to tell them apart when
 * deciding whether a crop may be applied without review.
 */
enum class MainScanPolygonSource {
    /** The frozen live-guide seed, proven to map onto the captured image. */
    FROZEN_SEED,

    /** Detection re-run on the captured still, because the seed could not be proven. */
    STILL_DETECTION,

    /** Neither was usable. The polygon is the full image and the user adjusts it. */
    FULL_FRAME
}

/**
 * Pure rules for the workflow stage machine.
 *
 * Every transition is explicit. The alternative — deriving the stage from whichever booleans happen
 * to be set — is what lets a UI show a progress overlay over a blank surface, or persist twice
 * because two flags briefly agree.
 */
object MainScanWorkflow {

    /** Stages during which the captured image MUST remain on screen. */
    private val IMAGE_VISIBLE_STAGES = setOf(
        MainScanStage.CropPreparing,
        MainScanStage.CropEditing,
        MainScanStage.Cropping,
        MainScanStage.EnhancementPreparing,
        MainScanStage.EnhancementReview,
        MainScanStage.Confirming,
        MainScanStage.Persisting
    )

    /** Stages that block user input behind a progress indication. */
    private val BUSY_STAGES = setOf(
        MainScanStage.Cropping,
        MainScanStage.EnhancementPreparing,
        MainScanStage.Confirming,
        MainScanStage.Persisting
    )

    /**
     * True when the surface for [stage] must be showing real pixels. The locked reference never
     * blanks the page: progress is drawn OVER the retained image, never instead of it.
     */
    fun requiresVisibleImage(stage: MainScanStage): Boolean = stage in IMAGE_VISIBLE_STAGES

    /** True when a progress indication is shown and interaction is blocked. */
    fun isBusy(stage: MainScanStage): Boolean = stage in BUSY_STAGES

    /** The polygon may only be edited in [MainScanStage.CropEditing]. */
    fun allowsPolygonEditing(stage: MainScanStage): Boolean = stage == MainScanStage.CropEditing

    /**
     * Stages from which crop preparation is a FIRST preparation rather than a repeat of one.
     *
     * Everything here is pre-pipeline: no working image has been decoded, no polygon resolved, no
     * derived preview or authoritative artifact exists, so re-running preparation from one of them
     * destroys nothing.
     */
    private val CROP_PREPARATION_STAGES = setOf(
        MainScanStage.CameraReady,
        MainScanStage.Capturing,
        MainScanStage.CaptureAccepted
    )

    /**
     * Whether crop preparation may run for a visit currently at [stage].
     *
     * Preparation is destructive by nature: it cancels the in-flight processing job, forces the
     * stage back to [MainScanStage.CropPreparing] and re-resolves the polygon from the seed. That is
     * exactly right the first time and catastrophic every time after, because the surface that asks
     * for it is a Compose effect keyed on the pending page — and a remount (Activity recreation, an
     * app-lock unlock, a composition re-entry) replays that effect with the SAME page.
     *
     * With the visit retained, replaying it would take a user who was editing their polygon, or
     * reviewing a rendered page, back to a decode they had already finished — discarding the edited
     * corners, releasing the derived previews and stranding the authoritative artifact against a
     * polygon that no longer matches it. So preparation is admitted only from the stages in
     * [CROP_PREPARATION_STAGES], where there is nothing yet to lose, and refused from every stage at
     * or past [MainScanStage.CropPreparing] — the working stages, the terminal ones, and
     * [MainScanStage.Failed], whose recovery is an explicit user action rather than a remount.
     *
     * A refusal is not an error: it means the visit already has what preparation would have produced.
     */
    fun allowsCropPreparation(stage: MainScanStage): Boolean = stage in CROP_PREPARATION_STAGES

    /**
     * Whether the crop may be advanced. Only from [MainScanStage.CropEditing], and only with a valid
     * polygon — the reference's Next is disabled on invalid geometry rather than failing later.
     */
    fun allowsAdvanceFromCrop(stage: MainScanStage, polygonValid: Boolean): Boolean =
        stage == MainScanStage.CropEditing && polygonValid

    /**
     * Whether a filter may be selected. Review only: switching filters mid-persist would change the
     * pixels out from under the write.
     */
    fun allowsFilterSelection(stage: MainScanStage): Boolean =
        stage == MainScanStage.EnhancementReview

    /**
     * Whether Confirm may be accepted. Review only and single-flight — [MainScanStage.Confirming]
     * and [MainScanStage.Persisting] both return false, so repeated taps cannot start a second
     * persist and therefore cannot create a duplicate document.
     */
    fun allowsConfirm(stage: MainScanStage): Boolean = stage == MainScanStage.EnhancementReview

    /**
     * Whether anything may be written to the database yet. False for every stage before
     * [MainScanStage.Persisting]: capture, crop, enhancement and review are all pre-persistence, and
     * abandoning at any of them must leave no trace in the library.
     */
    fun allowsPersistence(stage: MainScanStage): Boolean = stage == MainScanStage.Persisting

    /**
     * Whether leaving now needs a discard confirmation. Once a frame has been captured there is
     * always something to lose, so every post-capture working stage confirms; the camera itself
     * confirms only when it owns files (handled by the capture session).
     */
    fun backNeedsConfirmation(stage: MainScanStage): Boolean = when (stage) {
        MainScanStage.CameraReady,
        MainScanStage.Completed,
        MainScanStage.Discarded -> false
        else -> true
    }

    /**
     * The stage Back moves to, or null when Back should leave the workflow entirely. Review steps
     * back to crop editing so a filter change does not cost the user their polygon.
     */
    fun backTarget(stage: MainScanStage): MainScanStage? = when (stage) {
        MainScanStage.EnhancementReview -> MainScanStage.CropEditing
        MainScanStage.CropEditing, MainScanStage.CropPreparing -> null
        else -> null
    }

    /** Legal successor stages, used to assert the machine cannot skip a step. */
    fun canTransition(from: MainScanStage, to: MainScanStage): Boolean {
        if (to == MainScanStage.Discarded || to == MainScanStage.Failed) {
            return from != MainScanStage.Completed
        }
        return when (from) {
            MainScanStage.CameraReady -> to == MainScanStage.Capturing
            MainScanStage.Capturing ->
                to == MainScanStage.CaptureAccepted || to == MainScanStage.CameraReady
            MainScanStage.CaptureAccepted -> to == MainScanStage.CropPreparing
            MainScanStage.CropPreparing -> to == MainScanStage.CropEditing
            MainScanStage.CropEditing -> to == MainScanStage.Cropping
            MainScanStage.Cropping ->
                to == MainScanStage.EnhancementPreparing || to == MainScanStage.CropEditing
            MainScanStage.EnhancementPreparing -> to == MainScanStage.EnhancementReview
            MainScanStage.EnhancementReview -> to == MainScanStage.Confirming ||
                to == MainScanStage.CropEditing
            MainScanStage.Confirming ->
                to == MainScanStage.Persisting || to == MainScanStage.EnhancementReview
            MainScanStage.Persisting ->
                to == MainScanStage.Completed || to == MainScanStage.EnhancementReview
            MainScanStage.Completed -> false
            MainScanStage.Failed -> to == MainScanStage.CropEditing ||
                to == MainScanStage.EnhancementReview
            MainScanStage.Discarded -> false
        }
    }
}
