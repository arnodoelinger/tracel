package com.tracel.plugin.listener.world.cell

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.engine.world.BlockEdit
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.ActionKind
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.block.toShape
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.FluidDisturbance
import com.tracel.plugin.listener.support.FluidProvenance
import com.tracel.plugin.listener.support.RecentColumnActor
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Snowable
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockFertilizeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.EntityBlockFormEvent
import org.bukkit.event.block.FluidLevelChangeEvent
import org.bukkit.event.block.MoistureChangeEvent
import org.bukkit.event.world.PortalCreateEvent
import org.bukkit.event.world.StructureGrowEvent

/** World-caused shape edits. */
class NaturalChangeListener(services: TracelServices) : TracelListener(services) {
    @Observes
    fun onForm(event: BlockFormEvent) {
        if (event is BlockSpreadEvent || event is EntityBlockFormEvent) return
        shape.became(event.block, event.newState)
    }

    @Observes
    fun onEntityForm(event: EntityBlockFormEvent) {
        val former = event.entity
        val who = if (former is Player) HolderId.Player(former.uniqueId) else HolderId.Entity(former.uniqueId)
        val cause = if (who is HolderId.Player) CauseKind.PLAYER_ACTION else CauseKind.ENTITY_ACTION
        shape.became(event.block, event.newState, cause, who)
        val below = event.block.getRelative(BlockFace.DOWN)
        if (below.blockData is Snowable) {
            shape.reread(
                action = ActionKind.BLOCK_CHANGE,
                cause = cause,
                causedBy = who,
                blocks = listOf(below)
            )
        }
    }

    @Observes
    fun onSpread(event: BlockSpreadEvent) = shape.became(event.block, event.newState)

    @Observes
    fun onGrow(event: BlockGrowEvent) {
        if (event is BlockFormEvent) return
        shape.became(event.block, event.newState)
    }

    @Observes
    fun onStructureGrow(event: StructureGrowEvent) {
        if (event.player != null) return
        shape.edits(
            action = ActionKind.BLOCK_CHANGE,
            cause = CauseKind.WORLD,
            causedBy = null,
            world = WorldId(event.world.uid),
            edits = event.blocks.map { BlockEdit(it.toBlockPos(), it.block.toShape(), it.toShape()) },
        )
    }

    @Observes
    fun onFertilize(event: BlockFertilizeEvent) {
        val by = event.player?.let { HolderId.Player(it.uniqueId) }
        shape.edits(
            action = ActionKind.BLOCK_CHANGE,
            cause = if (by != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD,
            causedBy = by,
            world = WorldId(event.block.world.uid),
            edits = event.blocks.map { BlockEdit(it.toBlockPos(), it.block.toShape(), it.toShape()) },
        )
    }

    @Observes
    fun onPortalCreate(event: PortalCreateEvent) {
        val by = (event.entity as? Player)?.let { HolderId.Player(it.uniqueId) }
        shape.reread(
            action = ActionKind.BLOCK_PLACE,
            cause = if (by != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD,
            causedBy = by,
            blocks = event.blocks.map { it.block },
        )
    }

    @Observes
    fun onFromTo(event: BlockFromToEvent) {
        val by = RecentColumnActor.playerAt(event.block) ?: RecentColumnActor.playerAt(event.toBlock)
        if (by != null) {
            RecentColumnActor.rememberAround(by, event.toBlock, 1)
        }
        FluidProvenance.onFlow(event.block, event.toBlock)
        FluidDisturbance.onFlow(event.block.toBlockPos(), event.toBlock.toBlockPos())
        shape.reread(
            action = ActionKind.BLOCK_CHANGE,
            cause = if (by != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD,
            causedBy = by?.let { HolderId.Player(it) },
            blocks = listOf(event.block, event.toBlock).distinct(),
        )
    }

    @Observes
    fun onFluidLevel(event: FluidLevelChangeEvent) {
        val by = RecentColumnActor.playerAt(event.block)
        if (by != null) RecentColumnActor.rememberAround(by, event.block, 1)
        FluidDisturbance.inherit(
            event.block.toBlockPos(),
            BlockFace.entries.filter { it.isCartesian }.map { event.block.getRelative(it).toBlockPos() },
        )
        shape.edit(
            block = event.block,
            before = event.block.toShape(),
            after = BlockShape(BlockDataKey(event.newData.asString)),
            action = ActionKind.BLOCK_CHANGE,
            cause = if (by != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD,
            causedBy = by?.let { HolderId.Player(it) },
        )
    }

    @Observes
    fun onMoisture(event: MoistureChangeEvent) = shape.became(event.block, event.newState)
}
