package com.dev.docscannerpdf.domain.idscan

/**
 * Pure ownership bookkeeping for the app-private JPEGs a passport review session produces.
 *
 * The review's reducer ([PassportReviewFlow]) is deliberately framework-free and owns no file
 * lifecycle: transitions such as [PassportReviewFlow.rotate] back to 0°, or the watermark
 * invalidation every upstream change performs, simply DROP a URI from the state. Whoever performs
 * the transition must therefore capture what the OLD state referenced and delete only what the NEW
 * state no longer references — which is exactly what [supersededUris] computes.
 *
 * Everything here is set arithmetic on strings (no Android types, no I/O), so every rule below is
 * unit-testable on the JVM:
 *
 * - the ACTIVE authoritative URIs can never be returned for deletion (they are subtracted);
 * - a URI frozen by an in-progress save is protected explicitly;
 * - only app-owned `file://` paths under the app's private files directory are ever deletable, so
 *   an imported gallery `content://` URI (which the baker copies FROM but never owns) is
 *   structurally excluded.
 *
 * The caller still performs the real delete through its own scheme+directory guard, so this class
 * is the first of two independent barriers, never the only one.
 */
object PassportFileOwnership {

    /**
     * Every app-owned URI [state] currently references — the canonical base, the settled filter
     * render, the settled rotation bake and the published watermark render. Blank entries are
     * ignored. A null state (no review open) references nothing.
     */
    fun referencedUris(state: PassportReviewState?): Set<String> {
        if (state == null) return emptySet()
        return buildSet {
            add(state.baseUri)
            add(state.filteredUri)
            state.rotationRenderedUri?.let { add(it) }
            state.watermarkRenderedUri?.let { add(it) }
        }.filterTo(mutableSetOf()) { it.isNotBlank() }
    }

    /**
     * The URIs [before] referenced that [after] no longer does — safe to delete once [after] has
     * been published as the live state. [protectedUris] is never returned (the save-frozen final
     * image, for instance), and a URI the new state still references is never returned even if the
     * old state referenced it too.
     */
    fun supersededUris(
        before: PassportReviewState?,
        after: PassportReviewState?,
        protectedUris: Set<String> = emptySet()
    ): Set<String> = referencedUris(before) - referencedUris(after) - protectedUris

    /**
     * What a finished session must delete: everything the session is known to have produced
     * ([ownedUris], the running ledger) plus whatever its last [state] still referenced, minus the
     * files that must SURVIVE ([retainUris] — on the success path the persisted final artifact).
     * Used by cancel and by post-save cleanup, so a visit can never leave a file behind that
     * nothing points at.
     */
    fun sessionOrphans(
        ownedUris: Set<String>,
        state: PassportReviewState?,
        retainUris: Set<String> = emptySet()
    ): Set<String> = (ownedUris + referencedUris(state)) - retainUris

    /**
     * Whether [uriString] is a deletable app-owned file: a `file://` URI whose path sits inside
     * [filesDirPath] and contains no parent-directory traversal. Content URIs, other schemes and
     * anything outside the app's private storage are rejected — the passport flow must never delete
     * a user's gallery original.
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
