package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.PerspectiveQuad

/**
 * Where a pending Main Scanner page came from. Kept explicit (not a boolean) so the crop surface
 * can tell a camera capture from a gallery import without inspecting the URI, and so a future
 * source can be added without re-reading every call site.
 */
enum class MainScanPageSource { CAMERA, IMPORT }

/**
 * One captured-but-not-yet-persisted Main Scanner page, handed from the capture surface to the
 * dedicated crop workflow.
 *
 * [uri] is an app-private `file://` JPEG the capture surface owns — never a gallery `content://`
 * original. [captureGeneration] is the token of the capture that produced it, so a result from a
 * superseded capture can never install itself over a newer one.
 *
 * This carries NO document id and NO Room row: a pending page is deliberately not a document.
 * Persistence happens once, at explicit user Confirm, several slices downstream.
 */
data class MainScanPendingPage(
    val uri: String,
    val source: MainScanPageSource,
    val captureGeneration: Long,
    /**
     * The corners the crop surface should open on, in ROTATED normalized space. Defaults to the full
     * frame, which is what an undetected or unstable capture correctly resolves to — a crop is never
     * seeded with corners that were not proven to belong to this capture.
     */
    val cropSeedQuad: PerspectiveQuad = PerspectiveQuad.full()
)

/**
 * The identity a capture must present to publish its result: the visit it belongs to plus that
 * visit's capture generation. BOTH must still match for a result to be adopted.
 *
 * The visit component is what makes leaving capture genuinely invalidate the session. Generations
 * restart from zero in a fresh visit, so a generation alone would collide: a slow capture from visit
 * N could match the identical generation number in visit N+1 and navigate that visit to crop with
 * the previous visit's pixels. [sessionId] is monotonic across visits, so no such collision exists.
 */
data class MainScanCaptureTicket(
    val sessionId: Long,
    val generation: Long
)

/**
 * Lifecycle of the capture surface. Deliberately coarse — the camera itself owns the CameraX
 * state; this only tracks what the UI must gate on.
 *
 * - [IDLE]: preview streaming, shutter armed.
 * - [CAPTURING]: a capture is in flight. The shutter is disarmed (single-flight) but **the preview
 *   keeps streaming** — the reference never blanks the preview on shutter, so nothing in this
 *   state may hide or tear down the preview.
 * - [HANDING_OFF]: a page exists and the crop surface is being routed to. Still not persisted.
 */
enum class MainScanCaptureStage { IDLE, CAPTURING, HANDING_OFF }

/**
 * Pure state of one Main Scanner capture visit. Framework-free (no Uri/Bitmap/coroutines) so every
 * transition below is JVM-testable, exactly like the passport flow's reducer.
 *
 * Invariants, each mapped to a locked-reference requirement:
 *
 * - [stage] never blanks the preview. There is no state in which the capture surface is expected
 *   to show a placeholder instead of live frames (reference invariant 3).
 * - [pendingPage] is set only by a capture whose generation still matches [captureGeneration], so
 *   a slow result from a superseded tap can never become the page the user sees (invariant 5 —
 *   no stale frame).
 * - [ownedUris] is the running ledger of every app-private file this visit produced. Discard and
 *   teardown delete exactly this set minus whatever is being handed forward (invariant 8).
 * - Nothing here references a document id: capture cannot persist (invariant 7).
 */
data class MainScanCaptureState(
    /**
     * Monotonic id of the visit this state belongs to, assigned when the capture surface opens.
     * Never reset — leaving capture moves to a NEW id, which is what invalidates every capture
     * still in flight from the visit being left.
     */
    val sessionId: Long = 0L,
    val stage: MainScanCaptureStage = MainScanCaptureStage.IDLE,
    val captureGeneration: Long = 0L,
    val pendingPage: MainScanPendingPage? = null,
    val ownedUris: Set<String> = emptySet(),
    val discardConfirmVisible: Boolean = false,
    val importInFlight: Boolean = false,
    /**
     * The document corners frozen when the CURRENT capture was accepted, or null when the detection
     * was not eligible. Immutable for the life of that capture — the live detector keeps running
     * during the exposure, so anything read later would describe a frame the user never saw.
     */
    val frozenCropSeed: MainScanCropSeed? = null
) {
    /**
     * True while a capture/import occupies the single-flight slot, or while a captured page is
     * being handed to the crop surface. [MainScanCaptureStage.HANDING_OFF] counts as busy on
     * purpose: routing is not instantaneous, and a shutter tap landing in that window would race
     * the navigation and could overwrite the very page the user is about to crop.
     */
    val isBusy: Boolean
        get() = stage == MainScanCaptureStage.CAPTURING ||
            stage == MainScanCaptureStage.HANDING_OFF ||
            importInFlight

    /**
     * Whether the manual shutter may fire. Manual capture is first-class: it is gated ONLY by an
     * in-flight capture/import and by an open discard dialog — never by detection confidence,
     * never by stability, never by a "document not found" state. The reference offers no
     * auto-capture and never withholds the shutter.
     */
    val canCapture: Boolean get() = !isBusy && !discardConfirmVisible

    /** Whether a gallery import may start. Same single-flight rules as the shutter. */
    val canImport: Boolean get() = !isBusy && !discardConfirmVisible

    /**
     * True once a page is ready to hand to the crop workflow. The host routes on this; the crop
     * surface renders [pendingPage] IMMEDIATELY and runs detection behind it (invariant 4).
     */
    val hasPendingPage: Boolean get() = pendingPage != null
}

/**
 * Pure reducer for [MainScanCaptureState]. The screen and host own all I/O (CameraX, files,
 * navigation) and consult these functions for every state change, so the capture contract is
 * verifiable without a device.
 */
object MainScanCaptureFlow {

    /**
     * Acquires the single-flight capture slot and returns the new state paired with the generation
     * token the caller must carry through its async capture. Returns null when a capture/import is
     * already running or the discard dialog is open, so a rapid double-tap is a silent no-op.
     *
     * The stage moves to [MainScanCaptureStage.CAPTURING] but the preview is untouched — callers
     * must not blank, freeze, or unbind the preview here.
     *
     * [seedCandidate] is the newest analysis result at the instant of the tap and [guideVisible] is
     * whether the guide was actually on screen. Both are frozen HERE, against the ticket this call
     * issues, and never consulted again — see [MainScanCropSeeding.freeze], which requires the guide
     * to have been visible so the crop can only ever open on corners the user was shown.
     *
     * Passing null, an ineligible result, or `guideVisible = false` is entirely normal and simply
     * means the crop opens full-frame. None of it gates the capture itself.
     */
    fun beginCapture(
        state: MainScanCaptureState,
        seedCandidate: MainScanAnalysisResult? = null,
        guideVisible: Boolean = false,
        timestampMs: Long = 0L
    ): Pair<MainScanCaptureState, MainScanCaptureTicket>? {
        if (!state.canCapture) return null
        val generation = state.captureGeneration + 1
        val ticket = MainScanCaptureTicket(sessionId = state.sessionId, generation = generation)
        return state.copy(
            stage = MainScanCaptureStage.CAPTURING,
            captureGeneration = generation,
            frozenCropSeed = MainScanCropSeeding.freeze(
                ticket = ticket,
                result = seedCandidate,
                guideVisible = guideVisible,
                timestampMs = timestampMs
            )
        ) to ticket
    }

    /** Whether [ticket] still identifies the live visit AND its current capture. */
    fun isCurrent(state: MainScanCaptureState, ticket: MainScanCaptureTicket): Boolean =
        ticket.sessionId == state.sessionId && ticket.generation == state.captureGeneration

    /**
     * Opens a brand-new visit with the next monotonic [MainScanCaptureState.sessionId]. Every field
     * is reset, so no pending page, ledger entry or generation survives — and because the id only
     * ever increases, a capture from the previous visit can never satisfy [isCurrent] here.
     */
    fun beginVisit(previous: MainScanCaptureState?): MainScanCaptureState =
        MainScanCaptureState(sessionId = (previous?.sessionId ?: 0L) + 1L)

    /**
     * Records a file this visit now owns, whether or not it ends up being used. Called as soon as
     * a path is created so a failure between creation and publication still leaves the file
     * deletable by [MainScanFileOwnership.visitOrphans].
     */
    fun withOwnedUri(state: MainScanCaptureState, uri: String): MainScanCaptureState =
        if (uri.isBlank()) state else state.copy(ownedUris = state.ownedUris + uri)

    /**
     * Publishes a successful capture as the pending page — only when [generation] is still the
     * current one. A stale result is REJECTED (state returned unchanged) so the caller can see it
     * was not adopted and delete the orphan it produced.
     *
     * The stage becomes [MainScanCaptureStage.HANDING_OFF], not IDLE: the visit is now routing to
     * crop, and re-arming the shutter would let a second capture race the navigation.
     */
    fun captureSucceeded(
        state: MainScanCaptureState,
        ticket: MainScanCaptureTicket,
        uri: String,
        source: MainScanPageSource
    ): MainScanCaptureState {
        if (!isCurrent(state, ticket)) return state
        if (uri.isBlank()) return state
        return state.copy(
            stage = MainScanCaptureStage.HANDING_OFF,
            pendingPage = MainScanPendingPage(
                uri = uri,
                source = source,
                captureGeneration = ticket.generation,
                // Resolves to the frozen corners only when the seed belongs to exactly this ticket;
                // a seed from an earlier visit or a superseded capture yields the full frame.
                cropSeedQuad = MainScanCropSeeding.resolveQuad(state.frozenCropSeed, ticket)
            ),
            ownedUris = state.ownedUris + uri,
            importInFlight = false
        )
    }

    /**
     * Releases the capture slot after a FAILED capture so the shutter re-arms for a retry. Stale
     * failures (a newer capture already started) are ignored. Never produces a pending page — a
     * failed capture must not route forward with raw or missing pixels.
     */
    fun captureFailed(
        state: MainScanCaptureState,
        ticket: MainScanCaptureTicket
    ): MainScanCaptureState {
        if (!isCurrent(state, ticket)) return state
        return state.copy(stage = MainScanCaptureStage.IDLE, importInFlight = false)
    }

    /**
     * Abandons a capture that produced NO callback within the watchdog bound.
     *
     * [MainScanCaptureStage.CAPTURING] must never be terminal: if the camera never calls back, the
     * shutter would stay disabled forever with no error and no way forward — the surface simply
     * stops responding. This transition re-arms the shutter AND advances the generation, so the
     * abandoned capture is now stale: should its result arrive late it is rejected by [isCurrent],
     * publishes nothing, and its output is deleted by the caller. Stale timeouts are ignored.
     */
    fun captureTimedOut(
        state: MainScanCaptureState,
        ticket: MainScanCaptureTicket
    ): MainScanCaptureState {
        if (!isCurrent(state, ticket)) return state
        // Only a capture that is STILL IN FLIGHT may be abandoned. A ticket keeps matching after its
        // result publishes (the generation does not advance on success), so without this guard a
        // watchdog firing just after a successful publication would flip HANDING_OFF back to IDLE and
        // re-arm the shutter while a page was already routing to Crop — letting a second capture race
        // the navigation. Success and abandonment are mutually exclusive outcomes.
        val inFlight = state.stage == MainScanCaptureStage.CAPTURING || state.importInFlight
        if (!inFlight) return state
        return state.copy(
            stage = MainScanCaptureStage.IDLE,
            captureGeneration = state.captureGeneration + 1,
            importInFlight = false
        )
    }

    /** Marks a gallery import in flight, sharing the shutter's single-flight slot. */
    fun beginImport(state: MainScanCaptureState): Pair<MainScanCaptureState, MainScanCaptureTicket>? {
        if (!state.canImport) return null
        val generation = state.captureGeneration + 1
        return state.copy(
            captureGeneration = generation,
            importInFlight = true
        ) to MainScanCaptureTicket(sessionId = state.sessionId, generation = generation)
    }

    /**
     * Clears the pending page once the crop surface has taken ownership of it, re-arming the
     * capture surface for the next page. The URI stays in [ownedUris]: the file still exists and
     * the crop workflow, not this state, now decides its fate.
     */
    fun pendingPageConsumed(state: MainScanCaptureState): MainScanCaptureState =
        state.copy(stage = MainScanCaptureStage.IDLE, pendingPage = null)

    /** Opens the discard confirmation. Disarms the shutter so nothing captures behind the dialog. */
    fun requestDiscard(state: MainScanCaptureState): MainScanCaptureState =
        state.copy(discardConfirmVisible = true)

    /** Cancel on the discard dialog — returns to capture with everything intact. */
    fun cancelDiscard(state: MainScanCaptureState): MainScanCaptureState =
        state.copy(discardConfirmVisible = false)

    /**
     * Confirmed discard: the visit is over. Returns a state on a NEW [MainScanCaptureState.sessionId]
     * so no pending page, ledger entry or in-flight capture from the discarded visit can ever be
     * adopted, and the caller deletes the orphans it captured from the OLD state via
     * [MainScanFileOwnership.visitOrphans].
     */
    fun confirmDiscard(previous: MainScanCaptureState?): MainScanCaptureState = beginVisit(previous)

    /**
     * Whether Back should raise the discard confirmation rather than leaving immediately. The
     * reference confirms only when there is something to lose; a pristine capture surface with
     * nothing captured and nothing owned exits directly.
     */
    fun backNeedsConfirmation(state: MainScanCaptureState): Boolean =
        state.hasPendingPage || state.ownedUris.isNotEmpty() || state.isBusy
}
