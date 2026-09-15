package com.tracel.plugin.listener.world.entity

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.annotations.Unstable
import com.tracel.engine.world.BlockEdit
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.block.cargoSlots
import com.tracel.plugin.adapter.block.takeAll
import com.tracel.plugin.adapter.block.toHolderId
import com.tracel.plugin.adapter.block.toPlacedBlockId
import com.tracel.plugin.adapter.block.toShape
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.BlockRelease
import com.tracel.plugin.listener.support.explosionActor
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.LivingEntity
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent

/**
 * Explosion listener.
 *
 * One transaction per blast: shapes, contents, and placement releases share attribution.
 */
@Unstable
class ExplosionListener(services: TracelServices) : TracelListener(services) {
    @Observes
    fun onEntityExplode(event: EntityExplodeEvent) {
        val causedBy = services.explosionActor(event.entity)
            ?: (event.entity as? LivingEntity)?.let { HolderId.Entity(it.uniqueId) }
        if (causedBy != null) services.redstoneTriggers.recordExplosion(event.location, causedBy)
        capture(event.blockList(), causedBy)
    }

    @Observes
    fun onBlockExplode(event: BlockExplodeEvent) = capture(event.blockList(), causedBy = null)

    private fun capture(blocks: List<Block>, causedBy: HolderId?) {
        if (blocks.isEmpty()) return
        val epochMillis = System.currentTimeMillis()

        shape.edits(
            action = ActionKind.BLOCK_BREAK,
            cause = CauseKind.EXPLOSION,
            causedBy = causedBy,
            world = WorldId(blocks.first().world.uid),
            edits = blocks.map { BlockEdit(it.toBlockPos(), it.toShape(), BlockShape.AIR) },
            epochMillis = epochMillis,
        )

        for (block in blocks) {
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
        ) { it.after != BlockShape.AIR }
    }
}
