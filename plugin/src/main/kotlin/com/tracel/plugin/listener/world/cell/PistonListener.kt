package com.tracel.plugin.listener.world.cell

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.annotations.Unstable
import com.tracel.model.world.ActionKind
import com.tracel.model.holder.HolderId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.block.toHolderId
import com.tracel.plugin.adapter.block.toPlacedBlockId
import com.tracel.plugin.listener.TracelListener
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent

@Unstable
private const val PISTON_SETTLE_TICKS = 3L

/** Piston listener. */
@Unstable
class PistonListener(services: TracelServices) : TracelListener(services) {
    @Observes
    fun onExtend(event: BlockPistonExtendEvent) = moved(event.block, event.blocks, event.direction)

    @Observes
    fun onRetract(event: BlockPistonRetractEvent) = moved(event.block, event.blocks, event.direction)

    private fun moved(piston: Block, blocks: List<Block>, direction: BlockFace) {
        val facing = (piston.blockData as? Directional)?.facing ?: direction
        val touched = blocks + blocks.map { it.getRelative(direction) } + piston + piston.getRelative(facing)
        val by = services.redstoneTriggers.recentPressNear(piston.world, piston.x, piston.y, piston.z)
        val cause = if (by is HolderId.Player) CauseKind.PLAYER_ACTION else CauseKind.WORLD
        shape.reread(ActionKind.BLOCK_CHANGE, cause, by, touched.distinct(), delayTicks = PISTON_SETTLE_TICKS) {
            !it.after.data.value.startsWith("minecraft:moving_piston")
        }
        if (blocks.isEmpty()) return

        // Farthest first or the near account merges into the still-occupied far one.
        // Relocate same tick as capture: a tick early, rollback looked one block behind.
        val ordered = blocks.sortedByDescending { it.distanceAlong(piston, direction) }
        val moves = ordered.map { it.toHolderId() to it.getRelative(direction).toHolderId() }
        val placed = ordered.map { it.toPlacedBlockId() to it.getRelative(direction).toPlacedBlockId() }

        // Owed until relocate finishes; a lookup in the window sees a half-pushed ledger
        later(piston.location) {
            owing {
                services.atomically {
                    for ((from, to) in moves) services.repo.relocate(from, to)
                    for ((from, to) in placed) services.repo.relocate(from, to)
                }
            }
        }
    }

    private fun Block.distanceAlong(origin: Block, direction: BlockFace): Int =
        (x - origin.x) * direction.modX + (y - origin.y) * direction.modY + (z - origin.z) * direction.modZ
}
