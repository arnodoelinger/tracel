package com.tracel.plugin.adapter.block

import com.tracel.annotations.Unstable
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockExtras
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.capability.cargo.CargoClaims
import com.tracel.plugin.adapter.block.capability.cargo.CargoSnapshot
import com.tracel.plugin.adapter.block.capability.extras.BlockStateMetaExtras
import com.tracel.plugin.adapter.block.capability.extras.DISABLED_SLOTS
import com.tracel.plugin.adapter.block.capability.extras.NameableExtras
import com.tracel.plugin.adapter.block.special.BannerExtras
import com.tracel.plugin.adapter.block.special.ChiseledBookshelfCapture
import com.tracel.plugin.adapter.block.special.SkullExtras
import com.tracel.plugin.util.log.Warnings
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import org.bukkit.Material
import org.bukkit.Nameable
import org.bukkit.block.*
import org.bukkit.block.data.BlockData
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BannerMeta
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType

private val logger = Logger.getLogger("BlockShape")
private val hasBlockEntity = ConcurrentHashMap<Material, Boolean>()

private val rebuiltExtras = ConcurrentHashMap<ByteBuffer, ItemStack>()

@Unstable
private const val MAX_REBUILT_EXTRAS = 2_048

/** World-log coordinate of this block. */
fun Block.toBlockPos(): BlockPos = BlockPos(WorldId(world.uid), x, y, z)

/** World-log coordinate of this block state. */
fun BlockState.toBlockPos(): BlockPos = BlockPos(WorldId(world.uid), x, y, z)

/** Pose for the world log: block data plus tile extras without cargo. */
fun Block.toShape(): BlockShape {
    if (!type.mayHaveBlockEntity(this)) return BlockShape(BlockDataKey(blockData.asString))
    return captureShape(getState(true))
}

/** Same as [Block.toShape], from an already-loaded state. */
fun BlockState.toShape(): BlockShape {
    if (this !is TileState) return BlockShape(BlockDataKey(blockData.asString))
    return captureShape(this)
}

/** Whether [block] of this material is a tile entity. */
internal fun Material.mayHaveBlockEntity(block: Block): Boolean =
    hasBlockEntity.getOrPut(this) { block.getState(false) is TileState }

/** @return whether this block has a tile entity. */
fun Block.mayHaveTile(): Boolean = type.mayHaveBlockEntity(this)

/** Writes this shape onto [block]. Physics off unless asked. */
fun BlockShape.applyTo(block: Block, physics: Boolean = false) {
    val data = BlockDataCache.of(data) ?: run {
        Warnings.once(logger, "state:${data.value}") { "unknown block state ${data.value} — left alone" }
        return
    }
    block.setBlockData(data, physics)
    when (val extras = extras) {
        null -> Unit
        is BlockExtras.Opaque -> applyBlockEntityExtras(block, extras, physics, data)
    }
}

/** Tile extras with cargo stripped. */
internal fun captureShape(state: BlockState): BlockShape {
    if (ChiseledBookshelfCapture.skipTileExtras(state)) {
        return BlockShape(BlockDataKey(CargoClaims.cleared(state.blockData)))
    }
    val copy = if (state.isPlaced) state.copy() else state
    if (copy.isPlaced) {
        return BlockShape(BlockDataKey(CargoClaims.cleared(state.blockData)), NameableExtras.of(state))
    }
    CargoSnapshot.empty(copy)
    return BlockShape(
        BlockDataKey(CargoClaims.cleared(copy.blockData)),
        tileExtras(copy, state) ?: NameableExtras.of(state),
    )
}

/** Banner patterns or `BlockStateMeta` NBT, or `null` if capture failed. */
private fun tileExtras(state: BlockState, live: BlockState = state): BlockExtras? = runCatching {
    BannerExtras.of(state) ?: SkullExtras.of(state) ?: BlockStateMetaExtras.of(state, live)
}.getOrElse {
    logger.log(Level.FINE, "block entity at ${state.location} could not be captured, keeping its state only", it)
    null
}

private fun rebuilt(nbt: ByteArray): ItemStack {
    val key = ByteBuffer.wrap(nbt)
    rebuiltExtras[key]?.let { return it }
    val item = ItemStack.deserializeBytes(nbt)
    if (rebuiltExtras.size >= MAX_REBUILT_EXTRAS) rebuiltExtras.clear()
    rebuiltExtras[key] = item
    return item
}

/**
 * Restores opaque extras onto the live tile.
 *
 * Failure leaves the block data already written.
 */
@Suppress("DEPRECATION")
private fun applyBlockEntityExtras(
    block: Block,
    extras: BlockExtras.Opaque,
    physics: Boolean,
    data: BlockData,
) {
    runCatching {
        val item = rebuilt(extras.bytes)
        val bannerMeta = item.itemMeta as? BannerMeta
        if (bannerMeta != null) {
            val banner = block.getState(false) as? Banner ?: return
            banner.patterns = bannerMeta.patterns
            if (bannerMeta.hasCustomName()) banner.customName(bannerMeta.customName())
            banner.blockData = data
            banner.update(true, physics)
            return
        }
        val skullMeta = item.itemMeta as? SkullMeta
        if (skullMeta != null) {
            val skull = block.getState(false) as? Skull ?: return
            skull.ownerProfile = skullMeta.ownerProfile
            skull.noteBlockSound = skullMeta.noteBlockSound
            skull.blockData = data
            skull.update(true, physics)
            return
        }
        val meta = item.itemMeta as? BlockStateMeta
        if (meta != null && meta.hasBlockState()) {
            val state = meta.blockState.copy(block.location)
            state.blockData = data
            (state as? Nameable)?.let { named ->
                if (meta.hasCustomName()) named.customName(meta.customName())
            }
            val off = meta.persistentDataContainer.get(DISABLED_SLOTS, PersistentDataType.INTEGER_ARRAY)
            if (off != null) (state as? Crafter)?.let { crafter ->
                for (slot in off) runCatching {
                    crafter.setSlotDisabled(
                        slot,
                        true
                    )
                }
            }
            state.update(true, physics)
            return
        }
        if (item.itemMeta?.hasCustomName() == true) {
            val named = block.getState(false) as? Nameable ?: return
            named.customName(item.itemMeta!!.customName())
            (named as BlockState).blockData = data
            (named as BlockState).update(true, physics)
        }
    }.onFailure {
        Warnings.once(
            logger,
            "extras:${block.type}",
        ) { "${block.type} restored without its block entity detail: ${it.message}" }
    }
}
