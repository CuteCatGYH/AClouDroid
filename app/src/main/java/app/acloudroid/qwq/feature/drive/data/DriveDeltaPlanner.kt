package app.acloudroid.qwq.feature.drive.data

/**
 * Decides which containers a sync pass needs to re-list.
 *
 * This exists because iCloud Drive has **no global change cursor**. D3 reconnaissance
 * (REFERENCE.md §5.6, §5.7) ruled out every `changes` variant as well as a CloudKit-level
 * delta on the `com.apple.CloudDocs` zone, so there is nothing analogous to Photos' `syncToken`
 * to persist and resume from.
 *
 * What is left is a per-folder `etag`. That is enough to make a *walk* cheap: ask the server for
 * each container's current `etag` (a small response with no children), compare against the
 * cached value, and only re-list the ones whose tag moved.
 *
 * The planner is deliberately a pure function so the "should I fetch this?" rule can be unit
 * tested exhaustively without a device or a network. The thresholds it applies are the entire
 * correctness story of D3, and they are the kind of thing that looks right and is subtly wrong.
 */
object DriveDeltaPlanner {

    /**
     * How stale a container's listing may get before it is re-listed regardless of `etag`.
     *
     * Without this, a container whose `etag` never changes would never be re-listed — fine while
     * the tags are accurate, but it means a single missed `etag` bump hides a change permanently.
     * The age check is the escape hatch.
     */
    const val DEFAULT_MAX_AGE_MS = 6L * 60 * 60 * 1000

    /** A container the sync pass may re-list, plus the metadata needed to judge it. */
    data class Candidate(
        val drivewsid: String,
        val parentDrivewsid: String,
        val name: String,
        /** Cached `etag`, or null when the container has never been listed. */
        val cachedEtag: String?,
        /** Whether this container's children were ever written locally. */
        val childrenFetched: Boolean,
        /** Wall-clock millis of the last successful write for this row. */
        val lastSyncedAt: Long
    )

    /** What the sync pass should do with one container. */
    enum class Action {
        /** Never listed locally — must be fetched in full. */
        LIST_UNKNOWN,

        /** Cached, but its `etag` moved — the children changed, re-list. */
        LIST_CHANGED,

        /** Cached and stale beyond [DEFAULT_MAX_AGE_MS] — re-list as a safety net. */
        LIST_STALE,

        /** Cached, `etag` identical, and recently checked — skip the network entirely. */
        SKIP
    }

    /**
     * Decides the action for one container.
     *
     * Order matters, and it is not the obvious one:
     *
     * 1. Never-listed always wins. `etag == null` and `childrenFetched == false` both mean "we do
     *    not know what is in here", and a cached etag cannot vouch for a listing that was never
     *    written.
     * 2. A changed `etag` beats staleness — the result is the same (`LIST_CHANGED` reads better
     *    in a log than `LIST_STALE` when both are true).
     * 3. Staleness is the last resort, checked only when the tags agree.
     *
     * @param freshEtag the `etag` the server just reported, or null if the probe failed. A null
     *   here must **never** be read as "unchanged" — that would silently skip a container whose
     *   probe merely errored. It falls through to the staleness check instead.
     */
    fun decide(
        candidate: Candidate,
        freshEtag: String?,
        now: Long,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS
    ): Action {
        if (!candidate.childrenFetched || candidate.cachedEtag == null) {
            return Action.LIST_UNKNOWN
        }
        if (freshEtag != null && freshEtag != candidate.cachedEtag) {
            return Action.LIST_CHANGED
        }
        val age = now - candidate.lastSyncedAt
        if (freshEtag == null || age > maxAgeMs || age < 0) {
            // `age < 0` covers a clock change or a row written with a bad timestamp. Treating a
            // negative age as "stale" is the safe direction: re-list rather than trust a value
            // that cannot mean anything.
            return Action.LIST_STALE
        }
        return Action.SKIP
    }

    /**
     * Orders candidates so parents are visited before their children.
     *
     * A walk that lists a child before its parent can write a child row whose parent does not
     * exist yet, which the UI then shows as an orphan. Sorting by `drivewsid` does not work —
     * nesting depth is not encoded there — so this builds a depth map from the known parent links.
     *
     * Containers whose parent is not in [candidates] are treated as roots: they may belong to a
     * subtree that was just removed, and refusing to visit them would strand their rows.
     */
    fun orderParentsFirst(candidates: List<Candidate>): List<Candidate> {
        if (candidates.size <= 1) return candidates

        val byId = candidates.associateBy { it.drivewsid }
        val depthCache = HashMap<String, Int>(candidates.size)

        fun depthOf(id: String, seen: MutableSet<String>): Int {
            depthCache[id]?.let { return it }
            // Cycle guard: a malformed parent link must not spin here.
            if (!seen.add(id)) return 0
            val parent = byId[id]?.parentDrivewsid
            val d = if (parent == null || parent !in byId) {
                0
            } else {
                depthOf(parent, seen) + 1
            }
            depthCache[id] = d
            return d
        }

        return candidates.sortedWith(
            compareBy({ depthOf(it.drivewsid, HashSet()) }, { it.drivewsid })
        )
    }

    /**
     * Computes which previously-cached children are gone.
     *
     * Only valid for a container that was **just listed in full** — the caller must not invoke
     * this with the ids of a partial page. A wrong call here deletes live rows, so the guard is
     * at the call site: this function cannot tell a complete listing from a truncated one.
     *
     * @param localChildren cached child ids of the container, from the DB.
     * @param freshChildren ids returned by the listing just performed.
     */
    fun removedChildren(localChildren: List<String>, freshChildren: Set<String>): List<String> =
        localChildren.filterNot { it in freshChildren }

    /** Aggregated outcome of a sync pass, for logging and for the UI's progress line. */
    data class SyncReport(
        val containersConsidered: Int = 0,
        val listed: Int = 0,
        val skipped: Int = 0,
        val removedRows: Int = 0,
        /** True when the walk finished visiting every candidate, not just part of it. */
        val walkCompleted: Boolean = false,
        /** Set when the pass stopped early; [walkCompleted] is false whenever this is non-null. */
        val abortReason: String? = null
    )
}
