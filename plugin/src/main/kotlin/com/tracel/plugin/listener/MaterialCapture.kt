package com.tracel.plugin.listener

import com.tracel.annotations.CauseKind
import com.tracel.engine.balance.InventoryDelta
import com.tracel.engine.container.ContainerSlotEntry
import com.tracel.engine.ledger.Ingredient
import com.tracel.engine.ledger.Product
import com.tracel.model.flow.Flow
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Quantity
import com.tracel.model.item.ItemKey
import com.tracel.model.world.BlockPos
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.entity.toBlockPos
import com.tracel.plugin.adapter.item.toHolderId
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.adapter.item.withCursor
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import com.tracel.plugin.adapter.block.CargoSlots
import com.tracel.plugin.listener.support.BlockRelease
import com.tracel.plugin.listener.support.ignoranceIsPermanent
import com.tracel.plugin.listener.support.isLedgeredHolder
import com.tracel.plugin.listener.support.recordAt
import com.tracel.plugin.listener.support.releaseAsTrackedDrops
import com.tracel.plugin.listener.support.worldgenMintFlows
import com.tracel.plugin.util.carriesCoordinates
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.launch
import org.bukkit.Location
import org.bukkit.inventory.CraftingInventory
import org.bukkit.inventory.ItemStack

private val logger = Logger.getLogger("MaterialCapture")

/**
 * Transaction log only.
 *
 * - Ring paths ([moved] / [single] / [released]) have no coordinates, so UUID holders miss
 * the spatial index
 * - [reconcile] / [positioned] / [direct] carry `at`. Look at what changed
 */
class MaterialCapture internal constructor(private val services: TracelServices) {
    private val inventoryQueued = ConcurrentHashMap.newKeySet<UUID>()
    private val pendingInventories = ConcurrentHashMap<UUID, ConcurrentLinkedQueue<Inventory>>()
    private val pendingReconcileCause = ConcurrentHashMap<UUID, CauseKind>()
    private val pendingRebaseline = ConcurrentHashMap.newKeySet<UUID>()

    private val lastSeenSlots = ConcurrentHashMap<HolderId, List<ContainerSlotEntry>>()

    /** One inventory read per player per tick. */
    fun scheduleReconcile(
        player: Player,
        inventories: Collection<Inventory> = listOf(player.inventory),
        cause: CauseKind = CauseKind.PLAYER_ACTION,
    ) {
        pendingInventories.getOrPut(player.uniqueId) { ConcurrentLinkedQueue() }.addAll(inventories)
        pendingReconcileCause[player.uniqueId] = cause
        armInventoryRead(player)
    }

    /** Snapshot without booking a transaction. */
    fun scheduleRebaseline(player: Player) {
        pendingRebaseline.add(player.uniqueId)
        armInventoryRead(player)
    }

    private fun armInventoryRead(player: Player) {
        if (!inventoryQueued.add(player.uniqueId)) return
        services.pendingCaptures.owed()
        Bukkit.getRegionScheduler().runDelayed(services.plugin, player.location, {
            try {
                flushInventoryRead(player)
            } finally {
                services.pendingCaptures.done()
            }
        }, 1L)
    }

    private fun flushInventoryRead(player: Player) {
        val id = player.uniqueId
        inventoryQueued.remove(id)
        if (pendingRebaseline.remove(id)) {
            if (!player.isLedgeredHolder()) {
                forget(HolderId.Player(id))
            } else {
                rebaseline(player)
                rebaselineOpenContainer(player)
            }
        }
        val inventories = pendingInventories.remove(id)?.distinct().orEmpty()
        val cause = pendingReconcileCause.remove(id) ?: CauseKind.PLAYER_ACTION
        if (inventories.isNotEmpty() && player.isLedgeredHolder()) {
            reconcile(inventories, player, cause)
        }
    }

    private fun rebaselineOpenContainer(player: Player) {
        val top = player.openInventory.topInventory
        val holder = top.toHolderId() ?: return
        if (holder is HolderId.Player) return
        services.differ.rebaseline(holder, top.toItemTotals())
    }

    // Ring: known fact, but no ledger read.
    // UUID holders will not land on the spatial index.

    /**
     * Records the movement of an item between two holders into the material tracking
     * ledger.
     */
    fun moved(
        cause: CauseKind,
        causedBy: HolderId?,
        itemKey: ItemKey,
        from: HolderId,
        to: HolderId,
        quantity: Long,
        epochMillis: Long = System.currentTimeMillis(),
    ) {
        services.gate.move(cause, causedBy, epochMillis, itemKey, from, to, quantity)
    }

    /**
     * Known move between two seeded holders.
     *
     * Adjusts both snapshots.
     */
    fun hopped(
        cause: CauseKind,
        causedBy: HolderId?,
        from: HolderId,
        to: HolderId,
        stack: ItemStack,
        epochMillis: Long = System.currentTimeMillis(),
    ) {
        val itemKey = stack.toItemKey()
        val quantity = stack.amount.toLong()
        adjust(from, itemKey, -quantity)
        adjust(to, itemKey, quantity)
        moved(cause, causedBy, itemKey, from, to, quantity, epochMillis)
    }

    /**
     * Records a change in the quantity of an item within a single holder in the material
     * tracking ledger.
     */
    fun single(
        cause: CauseKind,
        causedBy: HolderId?,
        itemKey: ItemKey,
        holder: HolderId,
        delta: Long,
        epochMillis: Long = System.currentTimeMillis(),
    ) {
        services.gate.single(cause, causedBy, epochMillis, itemKey, holder, delta)
    }

    /** Records multiple inventory deltas into the material tracking ledger. */
    fun many(
        cause: CauseKind,
        causedBy: HolderId?,
        deltas: List<InventoryDelta>,
        epochMillis: Long = System.currentTimeMillis(),
    ) {
        if (deltas.isEmpty()) return
        services.gate.many(cause, causedBy, epochMillis, deltas)
    }

    /**
     * Records the release of items from one holder to another within the material
     * tracking ledger.
     */
    fun released(
        cause: CauseKind,
        causedBy: HolderId?,
        from: HolderId,
        to: HolderId,
        epochMillis: Long = System.currentTimeMillis(),
    ) {
        services.gate.release(cause, causedBy, epochMillis, from, to)
    }

    /**
     * Claim window.
     *
     * Later vanilla needs to drop attribute to [releases] instead of minting with no origin.
     */
    fun releasing(
        releases: List<BlockRelease>,
        cause: CauseKind,
        causedBy: HolderId?,
        at: BlockPos? = null,
        epochMillis: Long = System.currentTimeMillis(),
    ) {
        if (releases.isEmpty()) return
        services.blockReleases.open(releases, cause, causedBy, epochMillis, at)
    }

    /**
     * Records the release of items from a holder into the world as tracked drops
     * in the material tracking ledger.
     */
    fun dropping(
        holder: HolderId,
        at: Location,
        cause: CauseKind,
        causedBy: HolderId?,
        epochMillis: Long = System.currentTimeMillis(),
        removed: List<ItemStack>? = null,
        andThen: (suspend () -> Unit)? = null,
    ) {
        services.releaseAsTrackedDrops(holder, at, cause, causedBy, epochMillis, removed, andThen)
    }

    /** Lots of a broken [holder] now live at [to]. Storage work: call from a coroutine, never the event thread. */
    suspend fun relocate(holder: HolderId, to: HolderId) {
        services.atomically { services.repo.relocate(holder, to) }
        forget(to)
    }

    /**
     * Diff live inventories vs. ledger.
     *
     * Must run on the region thread that owns [player].
     */
    fun reconcile(
        inventories: List<Inventory>,
        player: Player,
        cause: CauseKind = CauseKind.PLAYER_ACTION,
        extra: Map<HolderId, Map<ItemKey, Long>> = emptyMap(),
    ) {
        val causedBy = HolderId.Player(player.uniqueId)
        val ledgered = player.isLedgeredHolder()

        val totalsByHolder = mutableMapOf<HolderId, MutableMap<ItemKey, Long>>()
        for (inventory in inventories) {
            val holder = inventory.toHolderId() ?: continue
            if (!ledgered && holder == causedBy) continue
            if (holder is HolderId.Block || holder is HolderId.Entity) captureSlotLayout(holder, inventory)
            var totals = (inventory as? CraftingInventory)?.matrix?.toItemTotals() ?: inventory.toItemTotals()
            if (holder == causedBy) {
                totals = totals.withCursor(player)
            }
            val merged = totalsByHolder.getOrPut(holder) { mutableMapOf() }
            for ((key, qty) in totals) merged.merge(key, qty, Long::plus)
        }
        for ((holder, totals) in extra) {
            if (!ledgered && holder == causedBy) continue
            val merged = totalsByHolder.getOrPut(holder) { mutableMapOf() }
            for ((key, qty) in totals) merged.merge(key, qty, Long::plus)
        }
        foldInOpenGrid(player, causedBy, inventories, totalsByHolder)

        val epochMillis = System.currentTimeMillis()
        val at = player.toBlockPos()

        val placed = totalsByHolder.keys.any { it.carriesCoordinates() }
        if (totalsByHolder.keys.all { services.differ.seeded(it) }) {
            val deltas = totalsByHolder.entries.flatMap { (holder, totals) ->
                services.differ.diffIfSeeded(holder, totals).orEmpty()
            }
            if (deltas.isEmpty()) return
            if (placed) many(cause, causedBy, deltas, epochMillis)
            else committing("$cause by $causedBy") {
                services.capture.record(deltas, epochMillis, cause, causedBy, at, ::ignoranceIsPermanent)
            }
            return
        }

        committing("$cause by $causedBy") {
            val deltas = totalsByHolder.flatMap { (holder, totals) -> services.differ.diff(holder, totals) }
            if (deltas.isEmpty()) return@committing
            services.capture.record(deltas, epochMillis, cause, causedBy, at, ::ignoranceIsPermanent)
        }
    }

    /**
     * Non-click holder.
     *
     * Snapshot must be of the moment the caller read the world (same region thread).
     */
    fun reconcile(
        holder: HolderId,
        totals: Map<ItemKey, Long>,
        cause: CauseKind,
        causedBy: HolderId?,
        at: BlockPos?,
    ) {
        val epochMillis = System.currentTimeMillis()
        val fast = services.differ.diffIfSeeded(holder, totals)
        if (fast != null) {
            many(cause, causedBy, fast, epochMillis)
            return
        }
        committing("$cause at $holder") {
            val deltas = services.differ.diff(holder, totals)
            if (deltas.isEmpty()) return@committing
            services.capture.record(deltas, epochMillis, cause, causedBy, at)
        }
    }

    /** Records a positional inventory update into the material tracking ledger. */
    fun positioned(
        cause: CauseKind,
        causedBy: HolderId?,
        at: Location,
        deltas: List<InventoryDelta>,
        epochMillis: Long = System.currentTimeMillis(),
        mintShortfallAt: HolderId? = null,
    ) = services.recordAt(cause, causedBy, epochMillis, at, deltas, mintShortfallAt)

    /** Records a direct material flow transaction into the tracking ledger. */
    fun direct(
        flows: List<Flow>,
        cause: CauseKind,
        causedBy: HolderId?,
        at: BlockPos?,
        epochMillis: Long = System.currentTimeMillis(),
    ) {
        if (flows.isEmpty()) return
        committing("$cause at ${at ?: causedBy}") {
            services.capture.recordDirect(flows, epochMillis, cause, causedBy, at)
        }
    }

    /** Records a crafting action into the material tracking ledger. */
    suspend fun crafted(
        ingredients: List<Ingredient>,
        product: Product,
        causedBy: HolderId?,
        at: BlockPos?,
        epochMillis: Long = System.currentTimeMillis(),
    ) {
        services.capture.recordCraft(ingredients, product, epochMillis, causedBy, at)
    }

    /**
     * Player craft in one unit of work: pick the gain the recipe made among [totals], book [ingredients] into it.
     * No single match books nothing and hands [unmatched] the gain count. Storage work — never the event thread.
     */
    suspend fun craftedByPlayer(
        player: HolderId,
        totals: Map<ItemKey, Long>,
        produced: String?,
        ingredients: List<Ingredient>,
        at: BlockPos,
        epochMillis: Long,
        unmatched: (gains: Int) -> Unit,
    ) {
        services.atomically {
            val gains = gains(player, totals)
            val gain = when {
                gains.size == 1 -> gains.single()
                // Recipe names this craft among other same-tick gains
                produced != null -> gains.singleOrNull { it.itemKey.material == produced }
                else -> null
            }
            if (gain == null) {
                unmatched(gains.size)
                return@atomically
            }
            val product = Product(player, gain.itemKey, Quantity(gain.delta))
            crafted(ingredients, product, player, at, epochMillis)
        }
    }

    /** Records a gain of items into the material tracking ledger. */
    suspend fun gains(holder: HolderId, totals: Map<ItemKey, Long>): List<InventoryDelta> =
        services.differ.diff(holder, totals).filter { it.delta > 0 }

    /**
     * Records the creation or "minting" of items into a specific holder within
     * the material tracking ledger.
     */
    fun minted(
        totals: Map<ItemKey, Long>,
        into: HolderId,
        cause: CauseKind,
        causedBy: HolderId?,
        at: BlockPos?,
        epochMillis: Long = System.currentTimeMillis(),
    ) {
        if (totals.isEmpty()) return
        for ((key, qty) in totals) adjust(into, key, qty)
        direct(worldgenMintFlows(totals, into), cause, causedBy, at, epochMillis)
    }

    /**
     * Keep the differ in sync with event-booked moves.
     *
     * Skip and the next read reports the same movement twice.
     */
    fun adjust(holder: HolderId, itemKey: ItemKey, delta: Long) {
        services.differ.adjust(holder, itemKey, delta)
    }

    /**
     * Updates the material tracking baseline for the given player's inventory.
     *
     * This ensures future inventory snapshots are compared against the updated baseline.
     */
    fun rebaseline(player: Player) {
        val holder = HolderId.Player(player.uniqueId)
        var totals = player.inventory.toItemTotals()
        totals = totals.withCursor(player)
        services.differ.rebaseline(holder, totals)
    }

    /** Forgets the snapshot associated with the specified holder. */
    fun forget(holder: HolderId) {
        services.differ.forget(holder)
    }

    /**
     * Captures the layout of occupied slots in a cargo container and records it for
     * the given holder.
     */
    @Suppress("ReplaceManualRangeWithIndicesCalls")
    fun captureSlotLayout(holder: HolderId, cargo: CargoSlots) {
        val slots = ArrayList<ContainerSlotEntry>(cargo.size)
        for (slot in 0 until cargo.size) {
            val stack = cargo.get(slot) ?: continue
            if (stack.isEmpty || stack.type.isAir) continue
            slots += ContainerSlotEntry(slot, stack.toItemKey(), stack.amount.toLong())
        }
        rememberSlots(holder, slots)
    }

    private fun foldInOpenGrid(
        player: Player,
        causedBy: HolderId,
        inventories: List<Inventory>,
        totalsByHolder: MutableMap<HolderId, MutableMap<ItemKey, Long>>,
    ) {
        val counted = totalsByHolder[causedBy] ?: return
        if (inventories.any { it is CraftingInventory && it.toHolderId() == causedBy }) return
        val grid = runCatching { player.openInventory.topInventory as? CraftingInventory }.getOrNull() ?: return
        for ((key, qty) in grid.matrix.toItemTotals()) counted.merge(key, qty, Long::plus)
    }

    private fun captureSlotLayout(holder: HolderId, inventory: Inventory) {
        val contents = inventory.contents
        val slots = ArrayList<ContainerSlotEntry>(contents.size)
        for (slot in contents.indices) {
            val stack = contents[slot] ?: continue
            if (stack.isEmpty || stack.type.isAir) continue
            slots += ContainerSlotEntry(slot, stack.toItemKey(), stack.amount.toLong())
        }
        rememberSlots(holder, slots)
    }

    private fun rememberSlots(holder: HolderId, slots: List<ContainerSlotEntry>) {
        if (lastSeenSlots.put(holder, slots) == slots) return
        val epochMillis = System.currentTimeMillis()
        committing("container slots at $holder") { services.containerSlots.record(holder, epochMillis, slots) }
    }

    private fun committing(what: String, work: suspend () -> Unit) {
        services.pendingCaptures.owed()
        services.scope.launch {
            try {
                services.atomically { work() }
            } catch (e: IllegalStateException) {
                logger.log(Level.FINE, "untracked material in $what, not recorded", e)
            }
        }.invokeOnCompletion { services.pendingCaptures.done() }
    }
}
