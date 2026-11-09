package app.acloudroid.qwq.feature.local.ui

/**
 * Builds the second line of a queue row: file size, plus the failure reason **only when it is
 * still true**.
 *
 * ## Why this is not just an inline `if`
 *
 * `lastError` records why the *last* attempt failed. Once the row is queued again -- either because
 * the automatic retry kicked in, or because the user pressed "重试失败" -- that string is stale. The
 * row is now uploading, so rendering `6.2 MB · NETWORK: timeout` under a "正在上传" chip reads as
 * "the retry did not work", which is the opposite of what is happening.
 *
 * `UploadQueueDao.retryFailedTasks` clears the column, but this gate stays as the second half of the
 * fix: a row can also become live again via `resetUploadingToPending`, and any future path that
 * reuses a row would have the same problem. Cheaper to make the rendering rule explicit than to
 * remember to null the column everywhere.
 */
object QueueSubtitle {

    /**
     * @param fileSizeText already-formatted size (the caller owns the unit formatting).
     * @param status the row's status; the error suffix is attached only for [STATUS_FAILED].
     * @param lastError the recorded failure reason, or null.
     */
    fun build(
        fileSizeText: String,
        status: String,
        lastError: String?
    ): String {
        if (status != STATUS_FAILED) return fileSizeText
        val reason = lastError?.trim().orEmpty()
        return if (reason.isEmpty()) fileSizeText else "$fileSizeText · $reason"
    }

    /** Whether the `FAILED`-only affordances (red tint, retry emphasis) apply. */
    fun isFailed(status: String): Boolean = status == STATUS_FAILED

    const val STATUS_FAILED = "FAILED"
}
