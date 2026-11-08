package com.nuvio.tv.core.usenet

import java.io.Closeable

/** Registers resources before a blocking operation so cancellation can close them immediately. */
internal class UsenetPreparation : Closeable {
    private val resources = mutableListOf<Closeable>()
    private var closed = false

    @Synchronized fun own(resource: Closeable) {
        if (closed) {
            resource.close()
            throw UsenetFailure("Usenet preparation was cancelled.")
        }
        resources.add(resource)
    }

    @Synchronized fun handoff() {
        ensureUsenet(!closed, "Usenet preparation was cancelled.")
        resources.clear()
    }

    override fun close() {
        val closing = synchronized(this) {
            closed = true
            resources.toList().also { resources.clear() }
        }
        closing.asReversed().forEach { runCatching { it.close() } }
    }
}
