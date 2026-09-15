package com.tracel.plugin.adapter.block

import com.tracel.model.id.WorldId
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockExtras
import com.tracel.model.world.BlockPos
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.capability.cargo.CargoClaims
import com.tracel.plugin.adapter.block.capability.cargo.CargoSnapshot
import com.tracel.plugin.adapter.block.capability.extras.BlockStateMetaExtras
import com.tracel.plugin.adapter.block.capability.extras.NameableExtras
import com.tracel.plugin.adapter.block.special.BannerExtras
import com.tracel.plugin.adapter.block.special.ChiseledBookshelfCapture
import com.tracel.plugin.util.Warnings
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import org.bukkit.Material
import org.bukkit.Nameable
import org.bukkit.block.Banner
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.TileState
import org.bukkit.block.data.BlockData
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BannerMeta
import org.bukkit.inventory.meta.BlockStateMeta

private val logger = Logger.getLogger("BlockShape")
private val hasBlockEntity = ConcurrentHashMap<Material, Boolean>()

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
        tileExtras(copy) ?: NameableExtras.of(state),
    )
}

/** Banner patterns or BlockStateMeta NBT, or `null` if capture failed. */
private fun tileExtras(state: BlockState): BlockExtras? = runCatching {
    BannerExtras.of(state) ?: BlockStateMetaExtras.of(state)
}.getOrElse {
    logger.log(Level.FINE, "block entity at ${state.location} could not be captured, keeping its state only", it)
    null
}

/**
 * Restores opaque extras onto the live tile.
 *
 * Failure leaves the block data already written.
 */
private fun applyBlockEntityExtras(
    block: Block,
    extras: BlockExtras.Opaque,
    physics: Boolean,
    data: BlockData,
) {
    runCatching {
        val item = ItemStack.deserializeBytes(extras.nbt)
        val bannerMeta = item.itemMeta as? BannerMeta
        if (bannerMeta != null) {
            val banner = block.getState(false) as? Banner ?: return
            banner.patterns = bannerMeta.patterns
            banner.blockData = data
            banner.update(true, physics)
            return
        }
        val meta = item.itemMeta as? BlockStateMeta
        if (meta != null && meta.hasBlockState()) {
            val state = meta.blockState.copy(block.location)
            state.blockData = data
            (state as? Nameable)?.let { named ->
                if (meta.hasCustomName()) named.customName(meta.customName())
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
