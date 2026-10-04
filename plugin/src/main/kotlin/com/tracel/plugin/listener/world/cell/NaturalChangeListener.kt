package com.tracel.plugin.listener.world.cell

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.annotations.Unstable
import com.tracel.engine.world.BlockEdit
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.world.ActionKind
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.adapter.block.toShape
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.cell.ColumnCell
import com.tracel.plugin.listener.support.cell.DragonEggCell
import com.tracel.plugin.util.ExpiringSet
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Snowable
import org.bukkit.entity.LightningStrike
import org.bukkit.entity.Player
import org.bukkit.event.block.*
import org.bukkit.event.weather.LightningStrikeEvent
import org.bukkit.event.world.PortalCreateEvent
import org.bukkit.event.world.StructureGrowEvent
import java.util.*

/** World-caused shape edits. */
@Unstable
class NaturalChangeListener(services: TracelServices) : TracelListener(services) {
    @Observes
    fun onForm(event: BlockFormEvent) {
        if (event is BlockSpreadEvent || event is EntityBlockFormEvent) return
        val by = ColumnCell.fluidPlayerAt(event.block)?.let(HolderId::Player)
        shape.became(event.block, event.newState, if (by != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD, by)
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
        if (event.isFromBonemeal) return
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
        val egg = event.block.type == Material.DRAGON_EGG
        val by = if (egg) DragonEggCell.lastAt(event.block)
        else ColumnCell.fluidPlayerAt(event.block) ?: ColumnCell.fluidPlayerAt(event.toBlock)
        if (by != null && !egg) ColumnCell.rememberFluidAround(by, event.toBlock, 1, refresh = false)
        val cause = if (by != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD
        val causedBy = by?.let { HolderId.Player(it) }
        if (egg) shape.reread(ActionKind.BLOCK_CHANGE, cause, causedBy, listOf(event.block, event.toBlock))
        else shape.flowed(event.toBlock, cause, causedBy)
    }

    @Observes
    fun onFluidLevel(event: FluidLevelChangeEvent) {
        val by = ColumnCell.fluidPlayerAt(event.block)
        shape.flowed(
            event.block,
            if (by != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD,
            by?.let { HolderId.Player(it) },
        )
    }

    @Observes
    fun onMoisture(event: MoistureChangeEvent) = shape.became(event.block, event.newState)

    // No event reports these: the world writes them straight in. Read them after instead

    @Observes
    @Suppress("DEPRECATION")
    fun onPlaceUnderWater(event: BlockPlaceEvent) {
        val block = event.blockPlaced
        if (block.type == Material.WET_SPONGE && block.world.isUltraWarm) {
            shape.reread(
                ActionKind.BLOCK_CHANGE,
                CauseKind.PLAYER_ACTION,
                HolderId.Player(event.player.uniqueId),
                listOf(block)
            )
        }
        if (block.type in BUBBLE_MAKERS || event.blockReplacedState.type in BUBBLE_MAKERS) {
            bubbleColumn(block, HolderId.Player(event.player.uniqueId))
        }
    }

    @Observes
    fun onBreakUnderWater(event: BlockBreakEvent) {
        if (event.block.type in BUBBLE_MAKERS) bubbleColumn(event.block, HolderId.Player(event.player.uniqueId))
    }

    private fun bubbleColumn(base: Block, by: HolderId) {
        val column = ArrayList<Block>()
        var cell = base.getRelative(BlockFace.UP)
        while (column.size < MAX_BUBBLE_COLUMN && (cell.type == Material.WATER || cell.type == Material.BUBBLE_COLUMN)) {
            column += cell
            cell = cell.getRelative(BlockFace.UP)
        }
        if (column.isNotEmpty()) shape.reread(
            ActionKind.BLOCK_CHANGE,
            CauseKind.PLAYER_ACTION,
            by,
            column,
            delayTicks = BUBBLE_DELAY_TICKS
        )
    }

    @Observes
    fun onLightning(event: LightningStrikeEvent) = struck(event.lightning)

    @Observes
    fun onLightningSpawn(event: EntityAddToWorldEvent) {
        (event.entity as? LightningStrike)?.let(::struck)
    }

    private val seenBolts = ExpiringSet<UUID>(BOLT_SEEN_MS)

    private fun struck(bolt: LightningStrike) {
        if (!seenBolts.add(bolt.uniqueId)) return
        val hit = bolt.location.block
        val near = ArrayList<Block>(LIGHTNING_REACH)
        for (dx in -1..1) for (dy in -2..0) for (dz in -1..1) near += hit.getRelative(dx, dy, dz)
        shape.reread(
            ActionKind.BLOCK_CHANGE,
            CauseKind.WORLD,
            null,
            near,
            delayTicks = LIGHTNING_SETTLE_TICKS
        ) { it.before != it.after }
    }

    private companion object {
        val BUBBLE_MAKERS = setOf(Material.SOUL_SAND, Material.MAGMA_BLOCK)

        const val MAX_BUBBLE_COLUMN = 64
        const val BUBBLE_DELAY_TICKS = 5L
        const val LIGHTNING_REACH = 27
        const val LIGHTNING_SETTLE_TICKS = 5L
        const val BOLT_SEEN_MS = 5_000L
    }
}
