package com.tracel.plugin.config

import com.tracel.api.access.Grant

/**
 * What other plugins may do through the API: [grants] by plugin, and whether any of them
 * may do what cannot be undone, purge and import ([allowDestructive]).
 */
data class ApiSettings(
    val grants: Map<String, Set<Grant>> = emptyMap(),
    val slowpokes: Set<String> = emptySet(),
    val allowDestructive: Boolean = false,
) {
    /** What [plugin] may do: everything, unless the admin listed it. */
    fun grantsOf(plugin: String): Set<Grant> = grants[plugin.lowercase()] ?: Grant.entries.toSet()

    /** Whether addon [id] keeps running when it stalls or hangs. */
    fun excuses(id: String): Boolean = id.lowercase() in slowpokes
}
