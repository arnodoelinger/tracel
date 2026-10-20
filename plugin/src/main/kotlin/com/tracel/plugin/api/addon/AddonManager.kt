package com.tracel.plugin.api.addon

import com.tracel.api.access.TracelConnection
import com.tracel.api.addon.Addon
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
    private val extensions: Extensions = Extensions(logger),
    private val writes: AddonWrites = AddonWrites.NONE,
    private val binders: List<AddonBinder> = emptyList(),
    private val excused: (addon: String) -> Boolean = { false },
    private val guards: (addon: String, trip: (String) -> Unit) -> AddonGuard = { addon, trip ->
        AddonGuard(addon, logger, trip, excused = excused(addon))
    },
    private val connect: (addon: Any) -> TracelConnection = { addon -> DetachedConnection(addon.javaClass.simpleName) },
) {
    private val addons = LinkedHashMap<String, Entry>()
    private var running = false

    /** IDs of the addons enabled right now. */
    val enabledIds: Set<String> @Synchronized get() = addons.values.filter { it.context != null }.mapTo(LinkedHashSet()) { it.id }

    /**
     * Registers [addon] and enables it if the host is running.
     *
     * No [Addon], a bad ID, a taken one, or a handler with a bad signature throws.
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
        val bindings = binders.mapNotNull { it.prepare(addon) }
        val entry = Entry(meta.id, meta.requires.toList(), addon, bindings)
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
        val owned = Owned { resource, failure ->
            logger.log(Level.WARNING, "Addon \"${entry.id}\" failed to close $resource.", failure)
        }
        val guard = owned.own(guards(entry.id) { tripped(entry.id) })
        val connection = owned.own(connect(entry.addon))
        val context = AddonScope(entry.id, owned, extensions, writes, guard, connection)
        entry.context = context
        try {
            (entry.addon as? TracelAddon)?.onEnable(context)
            entry.bindings.forEach { it.bind(context) }
            logger.info("Addon \"${entry.id}\" enabled.")
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "Addon \"${entry.id}\" failed to enable and was disabled.", failure)
            disable(entry)
        }
    }

    @Synchronized
    private fun tripped(id: String) {
        addons[id]?.let(::disable)
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

    private class Entry(val id: String, val requires: List<String>, val addon: Any, val bindings: List<AddonBinding>) {
        var context: AddonScope? = null
    }
}
