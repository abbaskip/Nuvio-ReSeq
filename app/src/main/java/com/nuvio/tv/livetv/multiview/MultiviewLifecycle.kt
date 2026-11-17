package com.nuvio.tv.livetv.multiview

/** The ordering here is a connection boundary: the single player must be gone before creation. */
internal class MultiviewLifecycle<T>(
    releaseSingle: () -> Unit,
    create: () -> T,
    private val foreground: (T) -> Unit,
    private val background: (T) -> Unit,
    private val release: (T) -> Unit,
) {
    val session: T
    private var closed = false
    init {
        releaseSingle()
        session = create()
    }
    fun onForeground() { if (!closed) foreground(session) }
    fun onBackground() {
        if (closed) return
        background(session)
    }
    fun close() {
        if (closed) return
        closed = true
        release(session)
    }
}

/** One in-memory entry intent. Restored/deep-linked routes always return to the guide. */
internal object MultiviewRouteAccess {
    private var requested = false
    fun request() { requested = true }
    fun take(): Boolean = requested.also { requested = false }
}
