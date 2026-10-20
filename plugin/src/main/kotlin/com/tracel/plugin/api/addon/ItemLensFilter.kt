package com.tracel.plugin.api.addon

import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.plugin.services.TracelServices

/**
 * [filter] with a custom item a lens names, e.g., `itemsadder:ruby`, turned into
 * the exact item keys it stands for.
 *
 * Unchanged for a game material or when no lens is registered.
 */
internal suspend fun TracelServices.withItemLens(filter: LookupFilter): LookupFilter {
    val name = filter.material ?: return filter
    val extensions = Extensions.live
    if (extensions.itemLenses.all.isEmpty() || !Extensions.isCustomName(name) || filter.itemKeys.isNotEmpty()) {
        return filter
    }
    val keys = extensions.itemKeysNamed(name, reading { log.itemKeys() }) { hash -> itemForms.find(hash) }
    return if (keys.isEmpty()) filter else filter.copy(itemKeys = keys)
}
