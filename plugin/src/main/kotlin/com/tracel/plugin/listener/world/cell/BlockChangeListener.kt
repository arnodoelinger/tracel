package com.tracel.plugin.listener.world.cell

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.annotations.Unstable
import com.tracel.engine.world.BlockEdit
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.BlockPos
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.block.toShape
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.FluidDisturbance
import com.tracel.plugin.listener.support.RecentColumnActor
import com.tracel.plugin.listener.support.explosionActor
import io.papermc.paper.event.block.BlockBreakBlockEvent
import io.papermc.paper.event.block.VaultChangeStateEvent
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.block.SpongeAbsorbEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityEnterBlockEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import java.util.Collections
import java.util.UUID

private const val FLUID_RADIUS = 8
private const val TRAMPLE_MEMORY = 256

/** World-shape edits listener. */
@Unstable
class BlockChangeListener(services: TracelServices) : TracelListener(services) {
    private val trampleAbove = Collections.synchronizedMap(
        object : LinkedHashMap<BlockPos, BlockShape>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: Map.Entry<BlockPos, BlockShape>) = size > TRAMPLE_MEMORY
        }
    )

    companion object { }

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
        RecentColumnActor.remember(event.player.uniqueId, event.block.getRelative(BlockFace.DOWN))
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
        shape.removed(
            block = event.block,
            cause = CauseKind.PLAYER_ACTION,
            causedBy = player
        )
        rememberActor(event.player.uniqueId, event.block)
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
    fun onBurn(event: BlockBurnEvent) = shape.removed(event.block)

    @Observes(ignoreCancelled = false)
    fun onBreakBlock(event: BlockBreakBlockEvent) = shape.removed(event.block)

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
            else -> CauseKind.WORLD
        }
        val by = player?.let(HolderId::Player)
            ?: igniter?.let { services.explosionActor(it) }

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
        shape.reread(
            action = ActionKind.BLOCK_CHANGE,
            cause = CauseKind.WORLD,
            causedBy = null,
            blocks = touched
        )
    }

    @Observes
    fun onFade(event: BlockFadeEvent) {
        val by = RecentColumnActor.playerAt(event.block)
        shape.became(
            event.block, event.newState,
            if (by != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD,
            by?.let { HolderId.Player(it) },
        )
    }

    @Observes
    fun onLeavesDecay(event: LeavesDecayEvent) = shape.removed(event.block)

    @Observes(priority = Priority.LOWEST)
    fun onEntityChangeBefore(event: EntityChangeBlockEvent) {
        if (event.entity !is Player) return
        val above = event.block.getRelative(BlockFace.UP)
        if (above.type.isAir) return
        trampleAbove[event.block.toBlockPos()] = above.toShape()
    }

    @Observes
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        val entity = event.entity
        val player = entity as? Player
        val fallingBy = (entity as? FallingBlock)?.let { RecentColumnActor.playerWhoDisturbed(it) }
        val by = player?.uniqueId ?: fallingBy
        shape.edit(
            event.block,
            event.block.toShape(),
            BlockShape(BlockDataKey(event.blockData.asString)),
            ActionKind.BLOCK_CHANGE,
            if (by != null) CauseKind.PLAYER_ACTION else CauseKind.ENTITY_ACTION,
            by?.let { HolderId.Player(it) } ?: HolderId.Entity(entity.uniqueId),
        )
        if (player != null) {
            val above = event.block.getRelative(BlockFace.UP)
            val before = trampleAbove.remove(event.block.toBlockPos())
            if (before != null && before != above.toShape()) {
                shape.edit(
                    above, before, above.toShape(),
                    ActionKind.BLOCK_CHANGE, CauseKind.PLAYER_ACTION, HolderId.Player(player.uniqueId),
                )
            }
        }
    }

    @Observes
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        RecentColumnActor.rememberAround(event.player.uniqueId, event.block, FLUID_RADIUS)
        claimFluid(event.block)
        afterTick(event.block, ActionKind.BLOCK_CHANGE, event.player.uniqueId)
    }

    @Observes
    fun onBucketFill(event: PlayerBucketFillEvent) {
        RecentColumnActor.rememberAround(event.player.uniqueId, event.block, FLUID_RADIUS)
        claimFluid(event.block)
        afterTick(event.block, ActionKind.BLOCK_CHANGE, event.player.uniqueId)
    }

    private fun claimFluid(block: Block) {
        val cells = ArrayList<BlockPos>(7)
        cells += block.toBlockPos()
        for (face in BlockFace.entries) {
            if (!face.isCartesian) continue
            cells += block.getRelative(face).toBlockPos()
        }
        FluidDisturbance.claim(cells)
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

private fun rememberActor(player: UUID, block: Block) {
    if (block.type == Material.WATER || block.type == Material.LAVA) {
        RecentColumnActor.rememberAround(player, block, FLUID_RADIUS)
    } else {
        RecentColumnActor.remember(player, block)
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
