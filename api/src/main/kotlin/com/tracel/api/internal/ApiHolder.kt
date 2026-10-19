package com.tracel.api.internal

import com.tracel.api.Internal
import com.tracel.api.TracelSession
import com.tracel.api.history.HistoryReads

/** What `Tracel` plugs in behind the API. */
@Internal
public interface ApiBackend {
    /** Registers an [addon] instance. */
    public fun registerAddon(addon: Any)

    /** A new session called [name]. */
    public fun session(name: String): TracelSession

    /** Reading history, open to everyone. */
    public val reads: HistoryReads
}

/** Holds the live [ApiBackend]. */
@Internal
public object ApiHolder {
    private val waiting = ArrayList<(ApiBackend) -> Unit>()

    /** The backend, or `null` while `Tracel` is not running. */
    @Volatile
    public var current: ApiBackend? = null; private set

    /** Publishes [backend] and runs everything that waited for it. */
    public fun attach(backend: ApiBackend) {
        val pending = synchronized(waiting) {
            check(current == null) { "Tracel API is already attached." }
            current = backend
            waiting.toList().also { waiting.clear() }
        }
        pending.forEach { it(backend) }
    }

    /** Withdraws the backend on shutdown. */
    public fun detach() {
        synchronized(waiting) { current = null }
    }

    /** Runs [action] now if `Tracel` is up, or once it is. */
    public fun whenReady(action: (ApiBackend) -> Unit) {
        val backend = synchronized(waiting) { current ?: run { waiting += action; null } }
        if (backend != null) action(backend)
    }
}
