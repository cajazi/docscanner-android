package com.dev.docscannerpdf.domain.mainscan

/**
 * Pure ownership bookkeeping for the app-private JPEGs a Main Scanner visit produces.
 *
 * Same rationale as the passport flow's ledger: [MainScanCaptureFlow] is deliberately
 * framework-free and owns no file lifecycle, so transitions that DROP a URI from the state (a
 * failed capture, a discard, a superseded generation) must be paired with a caller that knows what
 * the old state referenced. That is what [supersededUris] and [visitOrphans] compute.
 *
 * Everything here is set arithmetic on strings — no Android types, no I/O — so every rule is
 * unit-testable on the JVM:
 *
 * - a URI the NEW state still references can never be returned for deletion;
 * - a URI being handed forward to the crop workflow is protected explicitly;
 * - only app-owned `file://` paths under the app's private files directory are ever deletable, so
 *   a user's gallery `content://` original — which the import path copies FROM and never owns — is
 *   structurally excluded and can never be deleted.
 *
 * The caller performs the real unlink behind its own scheme + directory check, so this is the first
 * of two independent barriers, never the only one.
 */
object MainScanFileOwnership {

    /**
     * Every app-owned URI [state] currently references: its running ledger plus the pending page.
     * Blank entries are ignored. A null state (no visit open) references nothing.
     */
    fun referencedUris(state: MainScanCaptureState?): Set<String> {
        if (state == null) return emptySet()
        return buildSet {
            addAll(state.ownedUris)
            state.pendingPage?.uri?.let { add(it) }
        }.filterTo(mutableSetOf()) { it.isNotBlank() }
    }

    /**
     * The URIs [before] referenced that [after] no longer does — safe to delete once [after] is the
     * live state. [protectedUris] is never returned (the page being handed to crop, for instance),
     * and a URI the new state still references is never returned even if the old state had it too.
     */
    fun supersededUris(
        before: MainScanCaptureState?,
        after: MainScanCaptureState?,
        protectedUris: Set<String> = emptySet()
    ): Set<String> = referencedUris(before) - referencedUris(after) - protectedUris

    /**
     * What a finished visit must delete: everything it is known to have produced, minus the files
     * that must SURVIVE ([retainUris] — the page handed forward to the crop workflow, or a
     * persisted artifact). Used by discard and by capture-surface teardown, so a visit can never
     * leave behind a file nothing points at.
     */
    fun visitOrphans(
        state: MainScanCaptureState?,
        retainUris: Set<String> = emptySet()
    ): Set<String> = referencedUris(state) - retainUris

    /**
     * Whether a capture-directory file may be reclaimed by the visit-start orphan sweep.
     *
     * The sweep exists because the visit ledger lives in memory: a process death mid-visit strands
     * its capture files with nothing left to reference them. But the sweep runs asynchronously, so a
     * capture completing before the enumeration could otherwise be deleted out from under its own
     * pending page — destroying the frame the user is about to crop.
     *
     * The bound is therefore strict: only a file last modified BEFORE the visit opened is
     * reclaimable. Anything this visit writes carries a later timestamp and is structurally outside
     * the sweep's reach, rather than merely unlikely to be caught by it. Equal timestamps are NOT
     * reclaimed — with coarse filesystem clocks a file written in the same millisecond as the visit
     * opening must be treated as belonging to the visit.
     */
    fun isReclaimableOrphan(lastModifiedMs: Long, visitOpenedAtMs: Long): Boolean =
        lastModifiedMs < visitOpenedAtMs

    /**
     * Whether [uriString] is a deletable app-owned file: a `file://` URI whose path sits inside
     * [filesDirPath] with no parent-directory traversal. Content URIs, other schemes, and anything
     * outside the app's private storage are rejected — the Main Scanner must never delete a user's
     * gallery original.
     */
    fun isOwnedFileUri(uriString: String?, filesDirPath: String): Boolean {
        if (uriString.isNullOrBlank() || filesDirPath.isBlank()) return false
        if (!uriString.startsWith(FILE_SCHEME_PREFIX)) return false
        val path = uriString.removePrefix(FILE_SCHEME_PREFIX)
        if (path.isEmpty() || path.contains("..")) return false
        val root = filesDirPath.trimEnd('/')
        return path == root || path.startsWith("$root/")
    }

    private const val FILE_SCHEME_PREFIX = "file://"
}
