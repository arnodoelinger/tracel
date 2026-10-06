package com.tracel.plugin.adapter.entity.link

import org.bukkit.Bukkit
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Vanilla keeps one leash hitch per fence and reuses it. A restore that expected the old
 * uuid must still find the live one.
 *
 * The alias lasts as long as that hitch. Gone from the world — gone from the map.
 */
internal object EntityAliases {
    private val byOldId = ConcurrentHashMap<UUID, UUID>()

    /** Records that [recorded] now lives as [actual]. No-op when they are already the same. */
    fun remember(recorded: UUID, actual: UUID) {
        if (recorded != actual) byOldId[recorded] = actual
    }

    /** The UUID to look up in the world. The recorded one if the hitch is gone or never aliased. */
    fun resolve(recorded: UUID): UUID {
        val actual = byOldId[recorded] ?: return recorded
        val live = Bukkit.getEntity(actual)
        if (live == null || !live.isValid) {
            byOldId.remove(recorded, actual)
            return recorded
        }
        return actual
    }
}
