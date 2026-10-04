package com.tracel.plugin.listener.world.cell

import com.destroystokyo.paper.event.block.BlockDestroyEvent
import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.annotations.Unstable
import com.tracel.engine.world.BlockEdit
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.adapter.block.toPlacedBlockId
import com.tracel.plugin.adapter.block.toShape
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.cell.ColumnCell
import com.tracel.plugin.listener.support.cell.FireCell
import com.tracel.plugin.listener.support.drop.BlockRelease
import com.tracel.plugin.listener.support.entity.explosionActor
import io.papermc.paper.event.block.BlockBreakBlockEvent
import io.papermc.paper.event.block.VaultChangeStateEvent
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.bukkit.block.data.Waterlogged
import org.bukkit.block.data.type.*
import org.bukkit.block.data.type.Tripwire
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Player
import org.bukkit.event.block.*
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityEnterBlockEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import java.util.*

private const val FLUID_RADIUS = 8

/** World-shape edits listener. */
@Unstable
class BlockChangeListener(services: TracelServices) : TracelListener(services) {
    companion object {}

    @Observes
    fun onPlace(event: BlockPlaceEvent) {
        if (event is BlockMultiPlaceEvent) return
        val player = HolderId.Player(event.player.uniqueId)
        shape.edit(
            block = event.block,
            before = event.blockReplacedState.toShape(),
            after = event.block.toShape(),
            action = ActionKind.BLOCK_PLACE,
            cause = CauseKind.PLAYER_ACTION,
            causedBy = player,
        )
        rememberActor(event.player.uniqueId, event.block)
        ColumnCell.remember(event.player.uniqueId, event.block.getRelative(BlockFace.DOWN))
        rereadNeighbours(event.block, player)
    }

    private fun rereadNeighbours(block: Block, by: HolderId) {
        val around = BlockFace.entries.filter { it.isCartesian }.map { block.getRelative(it) }
            .filter { it.isShapedByNeighbours() }
        if (around.isEmpty()) return
        shape.reread(
            action = ActionKind.BLOCK_CHANGE,
            cause = CauseKind.PLAYER_ACTION,
            causedBy = by,
            blocks = around
        ) { it.before != it.after && !it.after.isAirLike }
    }

    @Observes
    fun onMultiPlace(event: BlockMultiPlaceEvent) {
        shape.edits(
            action = ActionKind.BLOCK_PLACE,
            cause = CauseKind.PLAYER_ACTION,
            causedBy = HolderId.Player(event.player.uniqueId),
            world = WorldId(event.block.world.uid),
            edits = event.replacedBlockStates.map { replaced ->
                val block = replaced.block
                BlockEdit(block.toBlockPos(), replaced.toShape(), block.toShape())
            },
        )
    }

    @Observes
    fun onBreak(event: BlockBreakEvent) {
        val player = HolderId.Player(event.player.uniqueId)
        if (event.block.leavesWater()) {
            shape.reread(
                action = ActionKind.BLOCK_BREAK,
                cause = CauseKind.PLAYER_ACTION,
                causedBy = player,
                blocks = listOf(event.block)
            )
        } else {
            shape.removed(
                block = event.block,
                cause = CauseKind.PLAYER_ACTION,
                causedBy = player
            )
        }
        rememberActor(event.player.uniqueId, event.block)
        rereadNeighbours(event.block, player)
        val falling = gravityAbove(event.block)
        if (falling.isNotEmpty()) {
            shape.reread(
                action = ActionKind.BLOCK_CHANGE,
                cause = CauseKind.PLAYER_ACTION,
                causedBy = player,
                blocks = falling
            )
        }
    }

    @Observes
    fun onBurn(event: BlockBurnEvent) {
        val by = FireCell.at(event.ignitingBlock) ?: FireCell.at(event.block)
        by?.let { FireCell.lit(event.block, it) }
        shape.removed(
            block = event.block,
            cause = if (by != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD,
            causedBy = by?.let(HolderId::Player)
        )
    }

    @Observes(ignoreCancelled = false)
    fun onBreakBlock(event: BlockBreakBlockEvent) {
        val by = ColumnCell.fluidPlayerAt(event.source) ?: ColumnCell.fluidPlayerAt(event.block)
        shape.removed(
            block = event.block,
            cause = if (by != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD,
            causedBy = by?.let(HolderId::Player)
        )
    }

    @Observes
    fun onDestroy(event: BlockDestroyEvent) {
        val by = ColumnCell.playerAt(event.block) ?: ColumnCell.fluidPlayerAt(event.block)
        shape.edit(
            block = event.block,
            before = event.block.toShape(),
            after = BlockShape(BlockDataKey(event.newState.asString)),
            action = ActionKind.BLOCK_BREAK,
            cause = if (by != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD,
            causedBy = by?.let(HolderId::Player),
        )
    }

    @Observes
    @Unstable
    fun onDispenseBucket(event: BlockDispenseEvent) {
        val type = event.item.type
        if (type != Material.BUCKET && !type.name.endsWith("_BUCKET")) return
        val facing = (event.block.blockData as? Directional)?.facing ?: return
        val target = event.block.getRelative(facing)
        shape.reread(
            action = ActionKind.BLOCK_CHANGE,
            cause = CauseKind.WORLD,
            causedBy = null,
            blocks = listOf(target)
        )
    }

    @Observes
    fun onVaultState(event: VaultChangeStateEvent) {
        afterTick(
            block = event.block,
            action = ActionKind.BLOCK_CHANGE,
            player = event.player?.uniqueId
        )
    }

    @Observes
    fun onEntityEnterBlock(event: EntityEnterBlockEvent) {
        shape.reread(
            action = ActionKind.BLOCK_CHANGE,
            cause = CauseKind.ENTITY_ACTION,
            causedBy = HolderId.Entity(event.entity.uniqueId),
            blocks = listOf(event.block),
        )
    }

    @Observes
    fun onIgnite(event: BlockIgniteEvent) {
        val player = event.player?.uniqueId
        val igniter = event.ignitingEntity
        val explosion = when (event.cause) {
            BlockIgniteEvent.IgniteCause.FIREBALL,
            BlockIgniteEvent.IgniteCause.EXPLOSION,
            BlockIgniteEvent.IgniteCause.ENDER_CRYSTAL,
                -> true

            else -> false
        }
        val cause = when {
            explosion -> CauseKind.EXPLOSION
            player != null -> CauseKind.PLAYER_ACTION
            event.cause == BlockIgniteEvent.IgniteCause.SPREAD || event.cause == BlockIgniteEvent.IgniteCause.LAVA ->
                if (FireCell.at(event.ignitingBlock) != null || event.ignitingBlock?.let { ColumnCell.fluidPlayerAt(it) } != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD

            else -> CauseKind.WORLD
        }
        val lighter = player
            ?: (igniter as? Player)?.uniqueId
            ?: FireCell.at(event.ignitingBlock).takeIf { event.cause == BlockIgniteEvent.IgniteCause.SPREAD }
            ?: event.ignitingBlock?.let { ColumnCell.fluidPlayerAt(it) }
                .takeIf { event.cause == BlockIgniteEvent.IgniteCause.LAVA }
        lighter?.let { FireCell.lit(event.block, it) }
        val by = player?.let(HolderId::Player)
            ?: igniter?.let { services.explosionActor(it) }
            ?: lighter?.let(HolderId::Player)

        // Neighbor air -> fire at a lower seq than the break restored to air; delay
        if (explosion) {
            shape.reread(
                action = ActionKind.BLOCK_PLACE,
                cause = cause,
                causedBy = by,
                blocks = listOf(event.block),
                delayTicks = 2L,
            )
            return
        }
        val around = ArrayList<Block>(7)
        around += event.block
        for (face in BlockFace.entries) {
            if (!face.isCartesian) continue
            around += event.block.getRelative(face)
        }
        shape.reread(
            action = ActionKind.BLOCK_PLACE,
            cause = cause,
            causedBy = by,
            blocks = around
        )
    }

    @Observes
    fun onSpongeAbsorb(event: SpongeAbsorbEvent) {
        val touched = (event.blocks.map { it.block } + event.block).distinct()
        val by = ColumnCell.playerAt(event.block)?.let(HolderId::Player)
        val cause = if (by != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD
        shape.reread(
            action = ActionKind.BLOCK_CHANGE,
            cause = cause,
            causedBy = by,
            blocks = touched
        )
        val plants = touched.filter { it.type in SPONGE_PLANTS }
        if (plants.isNotEmpty()) {
            material.releasing(
                releases = plants.map { BlockRelease(it.toPlacedBlockId(), it) },
                cause = cause,
                causedBy = by,
                at = event.block.toBlockPos()
            )
        }
    }

    @Observes
    fun onFade(event: BlockFadeEvent) {
        val by = ColumnCell.fluidPlayerAt(event.block)
        shape.became(
            block = event.block,
            newState = event.newState,
            cause = if (by != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD,
            causedBy = by?.let { HolderId.Player(it) },
        )
    }

    @Observes
    fun onLeavesDecay(event: LeavesDecayEvent) = shape.removed(event.block)

    @Observes
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        val entity = event.entity
        val player = entity as? Player
        val fallingBy = (entity as? FallingBlock)?.let { ColumnCell.playerWhoDisturbed(it) }
        val by = player?.uniqueId ?: fallingBy
        shape.edit(
            block = event.block,
            before = event.block.toShape(),
            after = BlockShape(BlockDataKey(event.blockData.asString)),
            action = ActionKind.BLOCK_CHANGE,
            cause = if (by != null) CauseKind.PLAYER_ACTION else CauseKind.ENTITY_ACTION,
            causedBy = by?.let { HolderId.Player(it) } ?: HolderId.Entity(entity.uniqueId),
        )
        if (event.block.type == Material.FARMLAND) {
            val above = event.block.getRelative(BlockFace.UP)
            if (!above.type.isAir) {
                shape.reread(
                    action = ActionKind.BLOCK_CHANGE,
                    cause = if (by != null) CauseKind.PLAYER_ACTION else CauseKind.ENTITY_ACTION,
                    causedBy = by?.let { HolderId.Player(it) } ?: HolderId.Entity(entity.uniqueId),
                    blocks = listOf(above),
                ) { it.before != it.after }
            }
        }
    }

    @Observes
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        ColumnCell.rememberFluidAround(event.player.uniqueId, event.block, FLUID_RADIUS)
        afterTick(event.block, ActionKind.BLOCK_CHANGE, event.player.uniqueId)
    }

    @Observes
    fun onBucketFill(event: PlayerBucketFillEvent) {
        ColumnCell.rememberFluidAround(event.player.uniqueId, event.block, FLUID_RADIUS)
        afterTick(event.block, ActionKind.BLOCK_CHANGE, event.player.uniqueId)
    }

    @Observes
    fun onSignChange(event: SignChangeEvent) = afterTick(event.block, ActionKind.SIGN_EDIT, event.player.uniqueId)

    private fun afterTick(block: Block, action: ActionKind, player: UUID?) =
        shape.reread(
            action,
            if (player != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD,
            player?.let(HolderId::Player),
            listOf(block),
        )
}

private val SPONGE_PLANTS = setOf(Material.KELP, Material.KELP_PLANT, Material.SEAGRASS, Material.TALL_SEAGRASS)

private fun Block.leavesWater(): Boolean =
    type == Material.ICE || (blockData as? Waterlogged)?.isWaterlogged == true

private fun rememberActor(player: UUID, block: Block) {
    if (block.type == Material.WATER || block.type == Material.LAVA || block.leavesWater()) {
        ColumnCell.rememberFluidAround(player, block, FLUID_RADIUS)
    } else {
        ColumnCell.remember(player, block)
    }
}

private fun gravityAbove(broken: Block): List<Block> {
    val out = ArrayList<Block>()
    var at = broken.getRelative(BlockFace.UP)
    repeat(16) {
        if (!at.type.hasGravity()) return out
        out += at
        at = at.getRelative(BlockFace.UP)
    }
    return out
}

private fun Block.isShapedByNeighbours(): Boolean = when (blockData) {
    is Chest, is Fence, is Wall, is GlassPane, is Stairs, is RedstoneWire, is Tripwire -> true
    else -> false
}
