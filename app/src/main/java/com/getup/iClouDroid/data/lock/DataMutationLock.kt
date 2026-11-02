package com.getup.iClouDroid.data.lock

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single app-wide lock that serialises every background path which mutates local data.
 *
 * ## Why this is app-scoped instead of feature-scoped
 *
 * Photos had this lock privately (`PhotoRepository.dataMutationMutex`) while it was the only
 * feature writing to a database. Drive changes that: its sync and upload workers write to a
 * *different* database, but they compete for the same non-DB resources — the Go core session,
 * the iCloud account quota, and the user's patience. AGENTS §12 states the rule directly:
 * 「上传任务必须共用这把，不得另起」.
 *
 * Copying the lock into `DriveRepository` would produce two independent locks that **both look
 * correct in isolation** and still let a photo reconcile and a drive upload interleave. That
 * class of bug only shows up under concurrency on a real account, which is the worst place to
 * find it.
 *
 * ## What must take this lock
 *
 * | Owner | Operation |
 * | --- | --- |
 * | `PhotoRepository` | reconcile walk, incremental sync, expunge sweep, upload queue insert |
 * | `DriveRepository` (D3+) | structure sync, upload, delete |
 *
 * Read-only paths must **not** take it: a gallery scroll or a folder listing must never wait
 * behind a long reconcile walk.
 *
 * ## Contract
 *
 * Re-entrancy is **not** provided. Anything that already holds the lock must call the `*Locked`
 * variant of a function rather than the public one — `PhotoRepository.syncIncremental()` is the
 * existing example, and taking the lock twice in one coroutine would deadlock.
 */
class DataMutationLock {

    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
