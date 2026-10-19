package com.tracel.plugin.api

/**
 * Things to close together, last in first out.
 *
 * One that fails to close is reported and the rest still close:
 * a broken addon resource does not get to leak the others.
 */
internal class Owned(private val failed: (AutoCloseable, Throwable) -> Unit) : AutoCloseable {
    private val held = ArrayDeque<AutoCloseable>()
    private var closed = false

    val isClosed: Boolean @Synchronized get() = closed

    /**
     * Holds [resource] until [close]; after it, closes [resource] at once,
     * so nothing outlives its owner.
     */
    fun <T : AutoCloseable> own(resource: T): T {
        val late = synchronized(this) {
            if (!closed) held.addLast(resource)
            closed
        }
        if (late) shut(resource)
        return resource
    }

    override fun close() {
        val toClose = synchronized(this) {
            closed = true
            held.toList().asReversed().also { held.clear() }
        }
        toClose.forEach(::shut)
    }

    private fun shut(resource: AutoCloseable) {
        try {
            resource.close()
        } catch (failure: Throwable) {
            failed(resource, failure)
        }
    }
}
