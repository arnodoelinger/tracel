package com.tracel.plugin.listener.world.cell

import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.annotations.Unstable
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.ActionKind
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.*
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.cell.ColumnCell
import com.tracel.plugin.listener.support.cell.DragonEggCell
import com.tracel.plugin.listener.support.drop.BlockRelease
import com.tracel.plugin.services.TracelServices
import io.papermc.paper.block.TileStateInventoryHolder
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Campfire
import org.bukkit.block.Jukebox
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Bed
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityInteractEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/** Clicks that mutate block state without place / break / grow. */
@Unstable
class BlockInteractListener(services: TracelServices) : TracelListener(services) {
    private val before = ConcurrentHashMap<UUID, Pair<Block, BlockShape>>()
    private val beforeEntity = ConcurrentHashMap<UUID, Pair<Block, BlockShape>>()
    private val discInHand = ConcurrentHashMap<UUID, ItemStack>()
    private val cargoScheduled = ConcurrentHashMap.newKeySet<String>()

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun beforeInteract(event: PlayerInteractEvent) {
        val clicked = event.clickedBlock
        if (clicked != null && clicked.type == Material.DRAGON_EGG) DragonEggCell.clicked(
            clicked,
            event.player.uniqueId
        )
        if (clicked != null && (clicked.blockData is Bed || clicked.type == Material.RESPAWN_ANCHOR)) {
            ColumnCell.remember(event.player.uniqueId, clicked)
            (clicked.blockData as? Bed)?.let { bed ->
                val other =
                    if (bed.part == Bed.Part.FOOT) clicked.getRelative(bed.facing) else clicked.getRelative(bed.facing.oppositeFace)
                ColumnCell.remember(event.player.uniqueId, other)
            }
        }
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.PHYSICAL) return
        val block = event.clickedBlock ?: return
        if (event.action == Action.RIGHT_CLICK_BLOCK && block.container() != null) return
        val held = event.item
        if (held != null && held.type.isRecord) {
            discInHand[event.player.uniqueId] = held.clone()
        }
        before[event.player.uniqueId] = block to block.toShape()
    }

    @Observes(ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.PHYSICAL) return
        val block = event.clickedBlock ?: return
        if (event.action == Action.RIGHT_CLICK_BLOCK && block.container() != null) {
            before.remove(event.player.uniqueId)
            return
        }
        val player = event.player
        val snapshot = before.remove(player.uniqueId)
        val state = block.getState(false)
        val cargo = event.action == Action.RIGHT_CLICK_BLOCK &&
                (state is TileStateInventoryHolder || state is Campfire)
        val ejected = (state as? Jukebox)?.takeIf { it.hasRecord() && event.item?.type?.isRecord != true }?.record
        if (event.action == Action.RIGHT_CLICK_BLOCK && ejected != null && !ejected.isEmpty) {
            val holder = block.toHolderId()
            material.releasing(
                listOf(
                    BlockRelease(
                        holder,
                        block.world,
                        block.x,
                        block.y,
                        block.z,
                        mapOf(ejected.toItemKey() to ejected.amount.toLong())
                    )
                ),
                CauseKind.PLAYER_ACTION,
                HolderId.Player(player.uniqueId),
                at = block.toBlockPos(),
            )
            later(block.location) { services.differ.rebaseline(holder, emptyMap()) }
        } else if (cargo) {
            queueCargo(player, block)
        }
        val partner = (block.blockData as? Bisected)?.let { half ->
            block.getRelative(if (half.half == Bisected.Half.TOP) BlockFace.DOWN else BlockFace.UP)
        }?.takeIf { it.type == block.type && event.action == Action.RIGHT_CLICK_BLOCK }
        if (partner != null) {
            shape.reread(
                ActionKind.BLOCK_CHANGE,
                CauseKind.PLAYER_ACTION,
                HolderId.Player(player.uniqueId),
                listOf(partner)
            ) { it.before != it.after }
        }
        later(block.location) {
            if (snapshot != null) {
                val after = block.toShape()
                if (snapshot.second != after) {
                    shape.edit(
                        block = block,
                        before = snapshot.second,
                        after = after,
                        action = ActionKind.BLOCK_CHANGE,
                        cause = CauseKind.PLAYER_ACTION,
                        causedBy = HolderId.Player(
                            player.uniqueId
                        ),
                    )
                }
            }
        }
    }

    private fun totalsOf(
        block: Block,
        rememberedDisc: ItemStack?,
    ): Map<ItemKey, Long> {
        val held = runCatching { block.cargoTotals() }.getOrNull() ?: emptyMap()
        if (held.isNotEmpty()) return held
        if (rememberedDisc == null || !rememberedDisc.type.isRecord) return held
        val data = block.blockData as? Jukebox ?: return held
        if (!data.hasRecord()) return held
        val key = rememberedDisc.toItemKey()
        return mapOf(key to rememberedDisc.amount.toLong())
    }

    private fun queueCargo(player: Player, block: Block) {
        val key = "${player.uniqueId}:${block.world.uid}:${block.x},${block.y},${block.z}"
        if (!cargoScheduled.add(key)) return
        later(block.location, ticks = 2L) {
            cargoScheduled.remove(key)
            val holder = block.toHolderId()
            runCatching { block.cargoSlots() }.getOrNull()?.let { material.captureSlotLayout(holder, it) }
            val extra: Map<HolderId, Map<ItemKey, Long>> =
                mapOf(holder to totalsOf(block, discInHand[player.uniqueId]))
            material.reconcile(
                inventories = listOf(player.inventory),
                player = player,
                extra = extra
            )
        }
    }

    @Observes(priority = Priority.LOWEST, ignoreCancelled = false)
    fun beforeEntityInteract(event: EntityInteractEvent) {
        if (event.entity is Player) return
        beforeEntity[event.entity.uniqueId] = event.block to event.block.toShape()
    }

    @Observes(ignoreCancelled = false)
    fun onEntityInteract(event: EntityInteractEvent) {
        val entity = event.entity
        if (entity is Player) return
        val block = event.block
        val snapshot = beforeEntity.remove(entity.uniqueId) ?: return
        later(block.location) {
            val after = block.toShape()
            if (snapshot.second == after) return@later
            shape.edit(
                block = block,
                before = snapshot.second,
                after = after,
                action = ActionKind.BLOCK_CHANGE,
                cause = CauseKind.ENTITY_ACTION,
                causedBy = HolderId.Entity(entity.uniqueId),
            )
        }
    }
}
