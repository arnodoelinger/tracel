package com.tracel.plugin.adapter.entity.kind

import org.bukkit.entity.Entity

/** Hull macher. */
internal fun interface HullMatch {
    /**
     * Whether this matcher owns [entity].
     *
     * Several matchers may accept the same hull.
     */
    fun matches(entity: Entity): Boolean
}
