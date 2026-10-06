package com.tracel.plugin.listener.world.entity

import com.tracel.annotations.Observes
import com.tracel.annotations.Unstable
import com.tracel.engine.world.edit.BlockEdit
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.WorldId
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.*
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.cell.ColumnCell
import com.tracel.plugin.listener.support.drop.BlockRelease
import com.tracel.plugin.listener.support.entity.explosionActor
import com.tracel.plugin.services.TracelServices
import com.tracel.plugin.specifics.block.AIR
import org.bukkit.ExplosionResult
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.ShulkerBox
import org.bukkit.block.data.type.Bed
import org.bukkit.entity.LivingEntity
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent

/**
 * Explosion listener.
 *
 * One transaction per blast: shapes, contents, and placement releases share attribution.
 */
@Unstable
@Suppress("UnstableApiUsage")
class ExplosionListener(services: TracelServices) : TracelListener(services) {
    @Observes
    fun onEntityExplode(event: EntityExplodeEvent) {
        if (!event.explosionResult.destroys()) return
        val causedBy = services.explosionActor(event.entity)
            ?: (event.entity as? LivingEntity)?.let { HolderId.Entity(it.uniqueId) }
        if (causedBy != null) services.redstoneTriggers.recordExplosion(event.location, causedBy)
        capture(event.blockList(), causedBy)
    }

    @Observes
    fun onBlockExplode(event: BlockExplodeEvent) {
        if (!event.explosionResult.destroys()) return
        val exploded = event.explodedBlockState
        val by = ColumnCell.playerAt(exploded.block)?.let(HolderId::Player)
        val self = explodedCells(exploded)
        if (self.isNotEmpty()) {
            shape.edits(
                action = ActionKind.BLOCK_BREAK,
                cause = CauseKind.EXPLOSION,
                causedBy = by,
                world = WorldId(exploded.world.uid),
                edits = self,
            )
        }
        if (by != null) services.redstoneTriggers.recordExplosion(exploded.location, by)
        capture(event.blockList(), causedBy = by)
    }

    private fun explodedCells(state: BlockState): List<BlockEdit> {
        if (state.type.isAir) return emptyList()
        val out = arrayListOf(BlockEdit(state.block.toBlockPos(), state.toShape(), AIR))
        val bed = state.blockData as? Bed ?: return out
        val other = bed.clone() as Bed
        other.part = if (bed.part == Bed.Part.HEAD) Bed.Part.FOOT else Bed.Part.HEAD
        val towards = if (bed.part == Bed.Part.HEAD) bed.facing.oppositeFace else bed.facing
        val partner = state.block.getRelative(towards)
        out += BlockEdit(partner.toBlockPos(), BlockShape(BlockDataKey(other.asString)), AIR)
        return out
    }

    private fun capture(blocks: List<Block>, causedBy: HolderId?) {
        if (blocks.isEmpty()) return
        val epochMillis = System.currentTimeMillis()

        // TNT in the crater primes, unless a plugin cancels the prime and it stays: read it a tick later
        val (tnt, broken) = blocks.partition { it.type == Material.TNT }
        shape.edits(
            action = ActionKind.BLOCK_BREAK,
            cause = CauseKind.EXPLOSION,
            causedBy = causedBy,
            world = WorldId(blocks.first().world.uid),
            edits = broken.map { BlockEdit(it.toBlockPos(), it.toShape(), AIR) },
            epochMillis = epochMillis,
        )
        if (tnt.isNotEmpty()) shape.reread(
            ActionKind.BLOCK_BREAK,
            CauseKind.EXPLOSION,
            causedBy,
            tnt
        ) { it.before != it.after }

        for (block in blocks) {
            if (block.state is ShulkerBox) {
                val drop = runCatching { block.drops }.getOrNull()?.firstOrNull { it.type == block.type }
                if (drop != null) material.packedShulker(block, drop, CauseKind.EXPLOSION, causedBy)
                continue
            }
            val slots = block.cargoSlots() ?: continue
            val holder = block.toHolderId()
            val at = block.location.add(0.5, 0.5, 0.5)
            val removed = slots.takeAll()
            material.dropping(
                holder = holder,
                at = at,
                cause = CauseKind.EXPLOSION,
                causedBy = causedBy,
                epochMillis = epochMillis,
                removed = removed
            )
        }

        material.releasing(
            blocks.map { BlockRelease(it.toPlacedBlockId(), it) },
            cause = CauseKind.EXPLOSION,
            causedBy = causedBy,
            epochMillis = epochMillis,
        )

        val toWatch = LinkedHashSet<Block>(blocks.size * 3)
        val up = BlockFace.UP
        for (block in blocks) {
            toWatch += block
            toWatch += block.getRelative(up)
            for (face in BlockFace.entries) {
                if (!face.isCartesian) continue
                val n = block.getRelative(face)
                toWatch += n
                toWatch += n.getRelative(up)
            }
        }
        shape.reread(
            action = ActionKind.BLOCK_PLACE,
            cause = CauseKind.EXPLOSION,
            causedBy = causedBy,
            blocks = toWatch.toList(),
            delayTicks = 2L,
        ) { it.after != AIR }
    }
}

@Suppress("UnstableApiUsage")
private fun ExplosionResult.destroys(): Boolean =
    this == ExplosionResult.DESTROY || this == ExplosionResult.DESTROY_WITH_DECAY
