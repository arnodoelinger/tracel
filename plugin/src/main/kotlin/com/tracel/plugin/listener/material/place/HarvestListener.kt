package com.tracel.plugin.listener.material.place

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.entity.toBlockPos
import com.tracel.plugin.adapter.block.toHolderId
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.drop.BlockRelease
import com.tracel.plugin.listener.support.flow.harvestFlows
import io.papermc.paper.event.block.PlayerShearBlockEvent
import org.bukkit.World
import org.bukkit.event.block.BlockShearEntityEvent
import org.bukkit.event.player.PlayerHarvestBlockEvent
import org.bukkit.event.player.PlayerShearEntityEvent
import org.bukkit.inventory.ItemStack

/** Harvest listener. */
class HarvestListener(services: TracelServices) : TracelListener(services) {
    @Observes
    fun onHarvest(event: PlayerHarvestBlockEvent) {
        val totals = mutableMapOf<ItemKey, Long>()
        for (stack in event.itemsHarvested) {
            if (stack.type.isAir || stack.amount <= 0) continue
            totals.merge(stack.toItemKey(), stack.amount.toLong(), Long::plus)
        }
        if (totals.isEmpty()) return

        val block = event.harvestedBlock
        val bush = block.toHolderId()
        val player = HolderId.Player(event.player.uniqueId)
        val at = block.toBlockPos()
        val epochMillis = System.currentTimeMillis()

        // Age change is world log; without it rollback took berries and left a still-stripped bush
        shape.reread(
            action = ActionKind.BLOCK_CHANGE,
            cause = CauseKind.PLAYER_ACTION,
            causedBy = player,
            blocks = listOf(block)
        )

        material.releasing(
            releases = listOf(BlockRelease(bush, block.world, block.x, block.y, block.z, totals)),
            cause = CauseKind.PLAYER_ACTION,
            causedBy = player,
            at = at,
            epochMillis = epochMillis)
    }

    @Observes
    fun onShearBlock(event: PlayerShearBlockEvent) {
        val block = event.block
        val player = HolderId.Player(event.player.uniqueId)
        shape.reread(
            action = ActionKind.BLOCK_CHANGE,
            cause = CauseKind.PLAYER_ACTION,
            causedBy = player,
            blocks = listOf(block)
        )
        releaseEntityDrops(
            holder = block.toHolderId(),
            world = block.world, x = block.x, y = block.y, z = block.z,
            drops = event.drops,
            cause = CauseKind.PLAYER_ACTION,
            causedBy = player,
            at = block.toBlockPos(),
        )
    }

    @Observes
    fun onShearEntity(event: PlayerShearEntityEvent) {
        val entity = event.entity
        val at = entity.location
        releaseEntityDrops(
            holder = HolderId.Entity(entity.uniqueId),
            world = at.world, x = at.blockX, y = at.blockY, z = at.blockZ,
            drops = event.drops,
            cause = CauseKind.PLAYER_ACTION,
            causedBy = HolderId.Player(event.player.uniqueId),
            at = entity.toBlockPos(),
        )
    }

    @Observes
    fun onDispenserShear(event: BlockShearEntityEvent) {
        val entity = event.entity
        val at = entity.location
        releaseEntityDrops(
            holder = HolderId.Entity(entity.uniqueId),
            world = at.world, x = at.blockX, y = at.blockY, z = at.blockZ,
            drops = event.drops,
            cause = CauseKind.WORLD,
            causedBy = null,
            at = entity.toBlockPos(),
        )
    }

    private fun releaseEntityDrops(
        holder: HolderId,
        world: World,
        x: Int,
        y: Int,
        z: Int,
        drops: List<ItemStack>,
        cause: CauseKind,
        causedBy: HolderId?,
        at: BlockPos,
    ) {
        val totals = drops.toItemTotals()
        if (totals.isEmpty()) return
        material.releasing(
            releases = listOf(BlockRelease(holder, world, x, y, z, totals)),
            cause = cause,
            causedBy = causedBy,
            at = at
        )
    }
}
