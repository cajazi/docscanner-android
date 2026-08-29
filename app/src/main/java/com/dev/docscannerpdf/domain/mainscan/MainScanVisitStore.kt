package com.dev.docscannerpdf.domain.mainscan

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dev.docscannerpdf.ui.mainscan.MainScanWorkingImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * The retained owner of ONE Main Scanner visit.
 *
 * ## The defect this exists to remove
 *
 * Every field below used to be an `Activity`-local `mutableStateOf` on `MainActivity`. An Activity
 * is recreated for a configuration change, a theme or locale switch, a return from an app-lock
 * unlock, a size change on a foldable — all of which happen inside a perfectly healthy process. The
 * recreated activity started from the field initialisers, so the visit did not survive: a new
 * session id, no pending page, an EMPTY owned-file ledger, no polygon, no previews and no
 * authoritative artifact. The user's captured page was still on disk and still theirs, but nothing
 * left in memory referenced it, so it could neither be finished nor swept — and the crop surface
 * re-entered preparation from the top.
 *
 * An Activity-scoped [ViewModel] is retained across exactly that recreation, so holding the visit
 * here makes surviving it structural rather than something every field has to remember to do.
 *
 * ## Lifecycle contract
 *
 * `ACTIVITY_RECREATION_AND_COMPOSITION_REMOUNT_WITHIN_LIVING_PROCESS`.
 *
 * This deliberately does NOT survive process death. Nothing here is serialized, there is no
 * `SavedStateHandle` and no durable counter: if the process is killed, the visit is gone and its
 * files are reclaimed by the orphan sweep the next visit runs. Making that survive is a separate
 * piece of work with its own correctness burden (a restored ledger must still describe files that
 * exist), and pretending to support it halfway would be worse than not supporting it.
 *
 * ## What is NOT here
 *
 * No `Context`, no `filesDir`, no I/O, no delete, no sweep, no URI construction, no Room. The
 * filesystem contract — the two-barrier ownership guard, containment, the visit sweep — stays on
 * the activity that actually has `filesDir`, and stays the single place a Main Scanner file can be
 * removed from. This store only remembers; it never reclaims.
 *
 * Every field is a Compose snapshot state, so reads from composition still register as observations
 * and writes still schedule recomposition exactly as they did when the fields lived on the activity.
 */
class MainScanVisitStore : ViewModel() {

    /** Whether the app-owned Main Scanner camera surface is showing. */
    var captureSurfaceVisible by mutableStateOf(false)

    /**
     * The pure capture-session state of the visit, retained AS ONE VALUE.
     *
     * Deliberately not decomposed into separate retained fields. [MainScanCaptureState] carries the
     * session id, the capture generation, the pending page, the discard-dialog flag, the import
     * flag, the frozen crop seed and — the reason the unit matters — [MainScanCaptureState.ownedUris],
     * the complete ledger of every app-private file this visit has produced. Retaining the page
     * without the ledger would restore a visit whose files no sweep could name, which is the exact
     * leak this whole store exists to close.
     */
    var captureState by mutableStateOf(MainScanCaptureState())

    /** The workflow stage the captured page is at. */
    var stage by mutableStateOf(MainScanStage.CameraReady)

    /** The EXIF-upright working copy of the accepted capture. */
    var workingImage by mutableStateOf<MainScanWorkingImage?>(null)

    /** Polygon editing state — the corners the user has actually adjusted, and the rotation. */
    var cropState by mutableStateOf<MainScanCropState?>(null)

    /** The perspective-corrected preview, and the enhanced render of it. */
    var croppedImage by mutableStateOf<Bitmap?>(null)
    var enhancedImage by mutableStateOf<Bitmap?>(null)

    /**
     * The only persistable result of the pipeline: the source-resolution post-crop artifact.
     *
     * It is retained for the same reason the polygon is. The artifact is authoritative only for the
     * polygon it was rendered from, so losing one while keeping the other is how a lifecycle event
     * turns into a page that does not match the crop the user confirmed. Retained together, they
     * stay consistent by construction; the authority rules that publish and invalidate it are
     * unchanged and still live with the render.
     */
    var authoritative by mutableStateOf<MainScanAuthoritativeArtifact?>(null)

    /** Why the last authoritative render produced nothing, or null when it succeeded or never ran. */
    var authoritativeFailure by mutableStateOf<MainScanRenderFailure?>(null)

    /**
     * The job advancing the current stage.
     *
     * Retained with the visit rather than with the Activity. On `lifecycleScope` a recreation
     * cancelled it mid-crop or mid-enhance, which left the retained stage claiming `Cropping` or
     * `EnhancementPreparing` with no coroutine behind it — a progress overlay that could never
     * resolve, over a page the user could not get back to. Owned here, the work either completes or
     * fails through its existing controlled path.
     *
     * Assignable by the activity so the existing single-flight discipline — cancel the previous job
     * before starting the next — and the existing teardown are unchanged.
     */
    var processingJob: Job? = null

    /**
     * The scope that job runs in. [viewModelScope] is `Dispatchers.Main.immediate` + a
     * `SupervisorJob`, matching what `lifecycleScope` provided, and is cancelled when this store is
     * cleared — so the retained work is bounded by the visit and is not an unmanaged global scope.
     */
    val processingScope: CoroutineScope get() = viewModelScope

    /**
     * The visit's owner is genuinely going away — the Activity finished, or the process is being
     * torn down. NOT called for a configuration change, which is the whole point of this class.
     *
     * Cancels retained work and drops the in-memory references so the bitmaps become collectable.
     * It does NOT recycle them: releasing the reference is enough, and recycling a bitmap Compose
     * may still be replaying a display list for is a crash `runCatching` cannot catch. It also does
     * no file I/O and simulates no discard — deleting here would remove files while the ledger that
     * accounts for them is being thrown away, and a lifecycle callback is not a user decision.
     */
    override fun onCleared() {
        processingJob?.cancel()
        processingJob = null
        enhancedImage = null
        croppedImage = null
        workingImage = null
        authoritative = null
        authoritativeFailure = null
        super.onCleared()
    }
}
