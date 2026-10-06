package com.tracel.tests.support

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.WorldId
import java.util.UUID

object Fixtures {
    val diamond: ItemKey = ItemKey("minecraft:diamond")
    val diamondBlock: ItemKey = ItemKey("minecraft:diamond_block")

    val world: WorldId = WorldId(UUID(0L, 1L))

    fun block(x: Int, y: Int, z: Int): HolderId.Block = HolderId.Block(world, x, y, z)
    fun placedBlock(x: Int, y: Int, z: Int): HolderId.PlacedBlock = HolderId.PlacedBlock(world, x, y, z)
    fun player(seed: Long): HolderId.Player = HolderId.Player(UUID(0L, seed))
    fun itemEntity(seed: Long): HolderId.ItemEntity = HolderId.ItemEntity(UUID(0L, seed))
    fun entity(seed: Long): HolderId.Entity = HolderId.Entity(UUID(7L, seed))
}
