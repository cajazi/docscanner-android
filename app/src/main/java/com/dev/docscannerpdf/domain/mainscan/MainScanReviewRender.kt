package com.dev.docscannerpdf.domain.mainscan

import kotlinx.coroutines.Job

/**
 * The identity of the enhancement review's OWN filter re-render, held apart from the pipeline's
 * stage-advancing job.
 *
 * ## Why this is not simply `processingJob`
 *
 * [MainScanVisitStore.processingJob] is a single slot reused by every stage-advancing coroutine:
 * crop preparation, rotation, the crop/enhance advance — and the Confirm persistence. Cancelling
 * that slot to stop a superseded filter render would therefore be cancelling whatever happens to be
 * in it, and one of the things that can be in it is a save in flight. Persistence is not
 * interruptible, so Back may not reach for that handle at all.
 *
 * This tracker gives the filter render an independent, narrowly scoped identity: only a job that
 * [track] was told about can ever be reached by [cancelActive], so leaving the review cancels
 * exactly the review's own work and nothing else.
 *
 * Deliberately NOT Compose snapshot state. Nothing composes from it — the review draws its
 * `Processing…` card from `filterRendering` — and it is read and written only from the main thread
 * the review's callbacks already run on.
 */
class MainScanReviewRender {

    private var job: Job? = null

    /** Records [job] as the review's live filter render, superseding whatever was tracked before. */
    fun track(job: Job) {
        this.job = job
    }

    /**
     * Cancels the tracked filter render and forgets it. Returns true when there was one to cancel.
     *
     * A no-op when nothing was tracked, so Back out of a review whose filter was never changed
     * cancels nothing at all — and idempotent, so a second call cannot reach a job that has since
     * been replaced by an untracked one.
     */
    fun cancelActive(): Boolean {
        val active = job ?: return false
        job = null
        active.cancel()
        return true
    }
}
