package com.tracel.plugin.bukkit.block

import com.tracel.annotations.Unstable
import com.tracel.plugin.listener.support.lectern.LecternPages
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.BrushableBlock
import org.bukkit.block.Campfire
import org.bukkit.block.Chest
import org.bukkit.block.ChiseledBookshelf
import org.bukkit.block.Jukebox
import org.bukkit.block.Lectern
import org.bukkit.block.data.type.Chest as ChestData
import org.bukkit.block.data.type.ChiseledBookshelf as ChiseledBookshelfData
import org.bukkit.block.data.type.Lectern as LecternData
import org.bukkit.inventory.ItemStack

/**
 * Chiseled bookshelf cargo.
 *
 * @see ChiseledBookshelf
 */
@Unstable
internal object BookshelfCargo : CargoSurface {
    override fun of(state: BlockState): CargoSlots? {
        if (state !is ChiseledBookshelf) return null
        return BookshelfHeld(state)
    }

    /** Sync bookshelf cargo. */
    @Suppress("UsePropertyAccessSyntax")
    fun sync(block: Block) {
        val state = runCatching { block.getState(false) }.getOrNull() as? ChiseledBookshelf ?: return
        runCatching { state.setLastInteractedSlot(-1) }
    }
}

private class BookshelfHeld(private val shelf: ChiseledBookshelf) : CargoSlots {
    override val size: Int get() = live().inventory.size.coerceAtLeast(occupiedSlots())

    override fun get(slot: Int): ItemStack? = live().inventory.getItem(slot)

    override fun set(slot: Int, stack: ItemStack?) {
        live().inventory.setItem(slot, stack ?: ItemStack.empty())
    }

    private fun live(): ChiseledBookshelf {
        if (!shelf.isPlaced) return shelf
        return (shelf.block.getState(false) as? ChiseledBookshelf) ?: shelf
    }

    private fun occupiedSlots(): Int {
        val data = live().blockData as? ChiseledBookshelfData ?: return 0
        return data.maximumOccupiedSlots
    }
}

/**
 * Suspicious sand / gravel.
 *
 * @see BrushableBlock
 */
@Unstable
internal object BrushableCargo : CargoSurface {
    override fun of(state: BlockState): CargoSlots? {
        if (state !is BrushableBlock) return null
        return BrushableHeld(state)
    }
}

@Unstable
@Suppress("UsePropertyAccessSyntax")
private class BrushableHeld(private val block: BrushableBlock) : CargoSlots {
    override val size: Int get() = 1
    override fun get(slot: Int): ItemStack? = if (slot == 0) block.item else null
    override fun set(slot: Int, stack: ItemStack?) {
        if (slot == 0) block.setItem(stack ?: ItemStack.empty())
    }
}

/**
 * Campfire.
 *
 * @see Campfire
 */
@Unstable
internal object CampfireCargo : CargoSurface {
    override fun of(state: BlockState): CargoSlots? {
        if (state !is Campfire) return null
        return CampfireHeld(state)
    }

    fun resync(block: Block) {
        val state = runCatching { block.getState(false) }.getOrNull() as? Campfire ?: return
        runCatching { state.update(true, false) }
    }
}

@Unstable
private class CampfireHeld(private val campfire: Campfire) : CargoSlots {
    override val size: Int get() = campfire.size
    override fun get(slot: Int): ItemStack? = campfire.getItem(slot)
    override fun set(slot: Int, stack: ItemStack?) = campfire.setItem(slot, stack ?: ItemStack.empty())
}

/**
 * Half of a double chest.
 *
 * @see Chest
 */
@Unstable
internal object ChestHalfCargo : CargoSurface {
    override fun of(state: BlockState): CargoSlots? {
        if (state !is Chest) return null
        return InventorySlots(state.blockInventory)
    }
}

/**
 * Books in a chiseled bookshelf.
 *
 * @see ChiseledBookshelf
 */
@Unstable
internal object ChiseledBookshelfCapture {
    fun skipTileExtras(state: BlockState): Boolean = state is ChiseledBookshelf
}

/**
 * The other half of a large chest.
 *
 * @see ChestData
 */
@Unstable
internal object DoubleChest {
    fun partner(block: Block): Block? {
        val data = block.blockData as? ChestData ?: return null
        val face = turn(data.facing, clockwise = data.type == ChestData.Type.LEFT) ?: return null
        return runCatching { block.getRelative(face).takeIf { it.type == block.type } }.getOrNull()
    }

    private fun turn(face: BlockFace, clockwise: Boolean): BlockFace? = when (face) {
        BlockFace.NORTH -> if (clockwise) BlockFace.EAST else BlockFace.WEST
        BlockFace.EAST -> if (clockwise) BlockFace.SOUTH else BlockFace.NORTH
        BlockFace.SOUTH -> if (clockwise) BlockFace.WEST else BlockFace.EAST
        BlockFace.WEST -> if (clockwise) BlockFace.NORTH else BlockFace.SOUTH
        else -> null
    }
}

/**
 * Jukebox cargo (disc).
 *
 * @see Jukebox
 */
@Unstable
internal object JukeboxCargo : CargoSurface {
    override fun of(state: BlockState): CargoSlots? {
        if (state !is Jukebox) return null
        return JukeboxHeld(state)
    }
}

private class JukeboxHeld(private val state: Jukebox) : CargoSlots {
    override val size: Int get() = 1

    override fun get(slot: Int): ItemStack? {
        if (slot != 0) return null
        val box = live()
        real(box.inventory.getItem(0))?.let { return it }
        real(box.record)?.let { return it }
        return null
    }

    @Suppress("UsePropertyAccessSyntax")
    override fun set(slot: Int, stack: ItemStack?) {
        if (slot != 0) return
        val record = stack ?: ItemStack.empty()
        val box = live()
        runCatching { box.stopPlaying() }
        box.setRecord(record)
        if (!box.isPlaced) return
        runCatching { box.update(true, false) }
    }

    private fun live(): Jukebox {
        if (!state.isPlaced) return state
        return (state.block.getState(false) as? Jukebox) ?: state
    }

    private fun real(stack: ItemStack?): ItemStack? =
        stack?.takeUnless { it.isEmpty || it.type.isAir }
}

/**
 * Jukebox record.
 *
 * @see Jukebox
 */
@Unstable
@Suppress("UsePropertyAccessSyntax")
internal object JukeboxRecord {
    // Warning: never Inventory.clear a jukebox snapshot.
    // That calls JukeboxSongPlayer.stop and NPEs when the copy has no level.
    fun clear(state: BlockState): Boolean {
        if (state !is Jukebox) return false
        state.setRecord(ItemStack.empty())
        return true
    }
}

/**
 * Lectern cargo (book).
 *
 * @see Lectern
 */
@Unstable
internal object LecternCargo : CargoSurface {
    override fun of(state: BlockState): CargoSlots? {
        if (state !is Lectern) return null
        return LecternHeld(state)
    }
}

private class LecternHeld(private val lectern: Lectern) : CargoSlots {
    override val size: Int get() = 1

    override fun get(slot: Int): ItemStack? {
        if (slot != 0) return null
        val book = lectern.inventory.getItem(0)
        return book.takeUnless { it == null || it.isEmpty || it.type.isAir }
    }

    override fun set(slot: Int, stack: ItemStack?) {
        if (slot != 0) return
        val book = stack != null && !stack.isEmpty && !stack.type.isAir
        if (!book && lectern.isPlaced) LecternPages.left(lectern.toBlockPos(), lectern.page)
        lectern.inventory.setItem(0, stack ?: ItemStack.empty())
        val data = lectern.blockData as? LecternData ?: return
        data.setHasBook(book)
        lectern.blockData = data
        if (lectern.isPlaced) runCatching { lectern.update(true, false) }
    }
}
