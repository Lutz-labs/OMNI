package dev.omalauncher.chroot

import dev.omalauncher.backend.BackendState

/**
 * Idempotent prepare/close for the chroot backend. Runtime.start can run more
 * than once (activity re-creation, launcher restarts); prepare must run at
 * most once per close, and its result is cached until close.
 */
class BackendLifecycle(
    private val prepareFn: suspend () -> BackendState,
    private val closeFn: () -> Unit,
) {
    private var prepared = false
    private var last: BackendState = BackendState.Checking

    /** Prepares once; later calls return the cached result until [close]. */
    suspend fun prepare(): BackendState {
        if (prepared) return last
        prepared = true
        last = prepareFn()
        return last
    }

    /** Closes the backend and allows the next [prepare] to run again. */
    fun close() {
        if (!prepared) return
        closeFn()
        prepared = false
        last = BackendState.Checking
    }
}
