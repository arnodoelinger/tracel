package com.tracel.plugin.api.addon

import com.tracel.api.addon.Addon
import com.tracel.api.addon.AddonContext
import com.tracel.api.addon.TracelAddon
import com.tracel.plugin.api.Owned
import java.util.logging.Level
import java.util.logging.Logger

private val ADDON_ID = Regex("[a-z0-9]+(-[a-z0-9]+)*")

/**
 * Hosts addons. They can be registered at any time; they run while the host does, and are disabled with
 * everything they own closed when it stops.
 *
 * A failing addon is disabled on its own and never takes the others, or `Tracel`, down with it.
 */
internal class AddonManager(
    private val logger: Logger,
    private val isPluginEnabled: (String) -> Boolean,
) {
    private val addons = LinkedHashMap<String, Entry>()
    private var running = false

    /** IDs of the addons enabled right now. */
    val enabledIds: Set<String> @Synchronized get() = addons.values.filter { it.context != null }.mapTo(LinkedHashSet()) { it.id }

    /**
     * Registers [addon] and enables it if the host is running.
     *
     * No [Addon], a bad IR or a taken one throws.
     */
    @Synchronized
    fun register(addon: Any) {
        val meta = requireNotNull(addon.javaClass.getAnnotation(Addon::class.java)) {
            "${addon.javaClass.name} must be annotated with \"@Addon\" to be registered."
        }
        require(ADDON_ID.matches(meta.id)) {
            "Addon ID \"${meta.id}\" of ${addon.javaClass.name} must be lowercase letters and digits, split by dashes."
        }
        require(meta.id !in addons) { "Addon \"${meta.id}\" is already registered." }
        val entry = Entry(meta.id, meta.requires.toList(), addon)
        addons[meta.id] = entry
        if (running) enable(entry)
    }

    /** Enables every registered addon whose requirements are met. */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        addons.values.forEach(::enable)
    }

    /**
     * Disables every enabled addon, newest first.
     *
     * Registrations stay for a later [start].
     */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        addons.values.toList().asReversed().forEach(::disable)
    }

    private fun enable(entry: Entry) {
        if (entry.context != null) return
        val missing = entry.requires.filterNot(isPluginEnabled)
        if (missing.isNotEmpty()) {
            logger.info("Addon \"${entry.id}\" needs ${missing.joinToString()}; it stays off.")
            return
        }
        val context = Context(entry.id, Owned { resource, failure ->
            logger.log(Level.WARNING, "Addon \"${entry.id}\" failed to close $resource.", failure)
        })
        entry.context = context
        try {
            (entry.addon as? TracelAddon)?.onEnable(context)
            logger.info("Addon \"${entry.id}\" enabled.")
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "Addon \"${entry.id}\" failed to enable and was disabled.", failure)
            disable(entry)
        }
    }

    private fun disable(entry: Entry) {
        val context = entry.context ?: return
        entry.context = null
        try {
            (entry.addon as? TracelAddon)?.onDisable()
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "Addon \"${entry.id}\" failed on disabling.", failure)
        }
        context.owned.close()
    }

    private class Entry(val id: String, val requires: List<String>, val addon: Any) {
        var context: Context? = null
    }

    private class Context(override val id: String, val owned: Owned) : AddonContext {
        override fun <T : AutoCloseable> own(resource: T): T = owned.own(resource)
    }
}
