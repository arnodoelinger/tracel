package com.tracel.plugin.listener.support.cell

import com.tracel.annotations.Unstable
import com.tracel.plugin.util.ExpiringMap
import java.util.UUID
import org.bukkit.block.Block
import org.bukkit.entity.Entity

// TODO: rewrite

@Unstable
internal object ColumnCell {
    // Water needs longer than gravel; 5s left streams still spreading unattributed
    private const val TTL_MS = 30_000L

    private const val BAND_SHIFT = 3

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

    private val byCell = ExpiringMap<Cell, UUID>(TTL_MS)

    private data class Cell(val world: UUID, val x: Int, val band: Int, val z: Int)

    fun rememberFluidAround(player: UUID, block: Block, radius: Int, refresh: Boolean = true) {
        val world = block.world.uid
        val band = block.y shr BAND_SHIFT
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                for (b in band - 1..band + 1) {
                    val cell = Cell(world, block.x + dx, b, block.z + dz)
                    if (refresh) byCell.put(cell, player) else byCell.putIfAbsent(cell, player)
                }
            }
        }
    }

    fun fluidPlayerAt(block: Block): UUID? = byCell[Cell(block.world.uid, block.x, block.y shr BAND_SHIFT, block.z)]
}
