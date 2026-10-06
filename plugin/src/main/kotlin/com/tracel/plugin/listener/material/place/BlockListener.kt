package com.tracel.plugin.listener.material.place

import com.destroystokyo.paper.event.block.BlockDestroyEvent
import com.tracel.annotations.Observes
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.plugin.adapter.block.cargoSlots
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.adapter.block.toHolderId
import com.tracel.plugin.adapter.block.toPlacedBlockId
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.cell.ColumnCell
import com.tracel.plugin.listener.support.cell.ReleasedCell
import com.tracel.plugin.listener.support.drop.BlockRelease
import com.tracel.plugin.listener.support.flow.CREATIVE_SINK
import com.tracel.plugin.listener.support.flow.CREATIVE_SOURCE
import com.tracel.plugin.listener.support.flow.isLedgeredHolder
import com.tracel.plugin.services.TracelServices
import io.papermc.paper.event.block.BlockBreakBlockEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.LeavesDecayEvent

/**
 * Block changes listener.
 */
class BlockListener(services: TracelServices) : TracelListener(services) {
    /** Block place. */
    @Observes
    fun onPlace(event: BlockPlaceEvent) {
        val itemKey = event.itemInHand.toItemKey()
        val playerHolder = HolderId.Player(event.player.uniqueId)
        val placedHolder = event.blockPlaced.toPlacedBlockId()

        val ledgered = event.player.isLedgeredHolder()
        if (ledgered) material.adjust(playerHolder, itemKey, -1L)

        material.moved(
            cause = CauseKind.PLAYER_ACTION,
            causedBy = playerHolder,
            itemKey = itemKey,
            from = if (ledgered) playerHolder else CREATIVE_SOURCE,
            to = placedHolder,
            quantity = 1L,
        )
    }

    /** Block break. */
    @Observes
    fun onBreak(event: BlockBreakEvent) {
        val block = event.block
        val placedHolder = block.toPlacedBlockId()
        val causedBy = HolderId.Player(event.player.uniqueId)
        val epochMillis = System.currentTimeMillis()

        if (!event.player.isLedgeredHolder()) {
            material.released(
                cause = CauseKind.BLOCK_BREAK,
                causedBy = causedBy,
                from = placedHolder,
                to = CREATIVE_SINK,
                epochMillis = epochMillis
            )
            return
        }

        ReleasedCell.claim(block)
        material.releasing(
            releases = listOf(BlockRelease(placedHolder, block)),
            cause = CauseKind.BLOCK_BREAK,
            causedBy = causedBy,
            epochMillis = epochMillis
        )
    }

    /**
     * Same material half as [onBreak] for piston / water.
     *
     * Cargo must leave through the correlator or it mints on the floor.
     */
    @Observes(ignoreCancelled = false)
    fun onBreakBlock(event: BlockBreakBlockEvent) {
        val block = event.block
        if (!ReleasedCell.claim(block)) return
        val epochMillis = System.currentTimeMillis()
        val releases = mutableListOf(BlockRelease(block.toPlacedBlockId(), block))
        if (block.cargoSlots() != null) {
            releases += BlockRelease(block.toHolderId(), block)
        }
        val by = ColumnCell.fluidPlayerAt(event.source) ?: ColumnCell.fluidPlayerAt(block)
        material.releasing(
            releases = releases,
            cause = if (by != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD,
            causedBy = by?.let(HolderId::Player),
            at = block.toBlockPos(),
            epochMillis = epochMillis
        )
    }

    /** Shape already logged decay. */
    @Observes
    fun onLeavesDecay(event: LeavesDecayEvent) {
        val block = event.block
        ReleasedCell.claim(block)
        material.releasing(
            releases = listOf(BlockRelease(block.toPlacedBlockId(), block)),
            cause = CauseKind.WORLD,
            causedBy = null,
            at = block.toBlockPos(),
        )
    }

    /**
     * A block that went because what held it did: e.g. a torch off a broken wall, the rest of a cactus,
     * the crop on trampled farmland.
     */
    @Observes
    fun onDestroy(event: BlockDestroyEvent) {
        if (!event.willDrop()) return
        val block = event.block
        if (!ReleasedCell.claim(block)) return
        val by = ColumnCell.playerAt(block) ?: ColumnCell.fluidPlayerAt(block)
        val releases = mutableListOf(BlockRelease(block.toPlacedBlockId(), block))
        if (block.cargoSlots() != null) releases += BlockRelease(block.toHolderId(), block)
        material.releasing(
            releases = releases,
            cause = if (by != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD,
            causedBy = by?.let(HolderId::Player),
            at = block.toBlockPos(),
        )
    }
}
