package com.tracel.api

import com.tracel.api.access.AccessError
import com.tracel.api.access.TracelConnection
import com.tracel.api.addon.Addon
import com.tracel.api.history.HistoryReads
import com.tracel.api.internal.ApiBackend
import com.tracel.api.internal.ApiHolder
import com.tracel.api.result.Res
import org.bukkit.plugin.Plugin

/**
 * Entry point of the `Tracel` API.
 *
 * Example:
 * ```
 * Tracel.register(MyAddon())
 * Tracel.onReady { ... }
 * ```
 *
 * Usable once `Tracel` has enabled; [register] and [onReady] wait for it, so they are safe to call earlier.
 */
@Unstable
public object Tracel {
    private val backend: ApiBackend
        get() = ApiHolder.current
            ?: error("Tracel API is not available yet. Use Tracel.onReady { ... } or check \"Tracel.isAvailable\".")

    /** Whether `Tracel` is enabled and the API can be used. */
    @JvmStatic
    public val isAvailable: Boolean get() = ApiHolder.current != null

    /** Runs [action] as soon as the API is available: now if it already is. */
    @JvmStatic
    public fun onReady(action: Runnable): Unit = ApiHolder.whenReady { action.run() }

    /**
     * Registers an addon: an instance of a class annotated with [Addon].
     *
     * It is enabled once `Tracel` is up and its [Addon.requires] are met, and disabled when `Tracel` stops.
     *
     * No [Addon] or a taken ID fails right here.
     */
    @JvmStatic
    public fun register(addon: Any) {
        requireNotNull(addon.javaClass.getAnnotation(Addon::class.java)) {
            "${addon.javaClass.name} must be annotated with \"@Addon\" to be registered."
        }
        ApiHolder.whenReady { it.registerAddon(addon) }
    }

    /**
     * Reading history.
     *
     * Open to every plugin, no grant needed: `with(Tracel.reads) { block.history() }`.
     */
    @JvmStatic
    public val reads: HistoryReads get() = backend.reads

    /**
     * [plugin]'s connection.
     *
     * Only [plugin]'s own code may ask: called from classes another plugin loaded,
     * it answers [AccessError.NotYourPlugin].
     */
    @JvmStatic
    public fun connect(plugin: Plugin): Res<TracelConnection, AccessError> {
        val caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { frames ->
            frames.map { it.declaringClass }.filter { it != Tracel::class.java }.findFirst().orElse(null)
        }
        if (caller?.classLoader !== plugin.javaClass.classLoader) return Res.Err(AccessError.NotYourPlugin(plugin.name))
        return backend.connect(plugin)
    }

    /** A session that owns what you start through it; close it, or use [session] with a block. */
    @JvmStatic
    public fun session(name: String): TracelSession = backend.session(name)

    /** Runs [block] inside a session called [name] and closes it afterward, whatever happens. */
    public inline fun <T> session(name: String, block: TracelSession.() -> T): T = session(name).use(block)
}
