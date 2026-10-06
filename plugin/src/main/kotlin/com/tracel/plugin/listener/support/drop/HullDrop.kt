package com.tracel.plugin.listener.support.drop

import com.tracel.annotations.Unstable
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.util.concurrent.ExpiringMap
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import org.bukkit.Location

/** Binds a hull drop to the [HolderId.PlacedEntity] that died. */
@Unstable
class HullDrop {
    private data class Claim(
        val world: UUID,
        val x: Double,
        val y: Double,
        val z: Double,
        val holder: HolderId.PlacedEntity,
        val key: ItemKey,
        val by: HolderId?,
    )

    private val next = AtomicLong()
    private val claims = ExpiringMap<Long, Claim>(ttlMillis = 1_000L)

    /** What a hull spawn is bound to, and who broke it. */
    data class Hull(val holder: HolderId.PlacedEntity, val by: HolderId?)

    /**
     * [holder] drops [key] at [at].
     *
     * Call from the break / death event, before vanilla spawns the item. [take] on that spawn
     * returns this holder.
     *
     * The floor item keeps the same lot.
     */
    fun expect(at: Location, holder: HolderId.PlacedEntity, key: ItemKey, by: HolderId? = null) {
        val world = at.world?.uid ?: return
        claims.put(next.incrementAndGet(), Claim(world, at.x, at.y, at.z, holder, key, by))
    }

    /** Bind a nearby hull spawn to the [HolderId.PlacedEntity] that [expect]ed this [key]. */
    fun take(at: Location, key: ItemKey): Hull? {
        val world = at.world?.uid ?: return null
        val radiusSq = MATCH_RADIUS * MATCH_RADIUS
        var bestId: Long? = null
        var bestDist = Double.MAX_VALUE
        var best: Claim? = null
        claims.forEachFresh { id, claim ->
            if (claim.world != world || claim.key != key) return@forEachFresh
            val dx = claim.x - at.x
            val dy = claim.y - at.y
            val dz = claim.z - at.z
            val d = dx * dx + dy * dy + dz * dz
            if (d <= radiusSq && d < bestDist) {
                bestDist = d
                bestId = id
                best = claim
            }
        }
        if (bestId != null) claims.remove(bestId)
        return best?.let { Hull(it.holder, it.by) }
    }

    private companion object {
        const val MATCH_RADIUS = 2.0
    }
}
