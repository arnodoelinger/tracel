package com.tracel.plugin.listener.support

import com.tracel.annotations.Unstable
import com.tracel.plugin.util.ExpiringMap
import java.util.UUID
import org.bukkit.block.Block
import org.bukkit.entity.Entity

// TODO: rewrite

@Unstable
internal object RecentColumnActor {
    // Water needs longer than gravel; 5s left streams still spreading unattributed
    private const val TTL_MS = 30_000L

    private val byColumn = ExpiringMap<Column, UUID>(TTL_MS)

    fun remember(player: UUID, block: Block) {
        byColumn.put(column(block), player)
    }

    fun rememberAround(player: UUID, block: Block, radius: Int) {
        val world = block.world.uid
        byColumn.putAll(
            (-radius..radius).flatMap { dx ->
                (-radius..radius).map { dz -> Column(world, block.x + dx, block.z + dz) }
            },
            player,
        )
    }

    fun playerWhoDisturbed(entity: Entity): UUID? =
        playerAt(entity.world.uid, entity.location.blockX, entity.location.blockZ)

    fun playerAt(block: Block): UUID? = playerAt(block.world.uid, block.x, block.z)

    fun playerAt(world: UUID, x: Int, z: Int): UUID? = byColumn[Column(world, x, z)]

    private fun column(block: Block) = Column(block.world.uid, block.x, block.z)

    private data class Column(val world: UUID, val x: Int, val z: Int)
}
