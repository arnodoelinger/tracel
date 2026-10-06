package com.tracel.plugin.capture

import com.tracel.engine.balance.InventoryDelta
import com.tracel.engine.capture.PlacedDeltas
import com.tracel.engine.capture.material.flow.releaseFlows
import com.tracel.engine.ledger.craft.Ingredient
import com.tracel.engine.ledger.craft.Product
import com.tracel.engine.wear.WearMark
import com.tracel.model.cause.CauseKind
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.transaction.Transaction
import com.tracel.model.world.BlockPos
import com.tracel.plugin.adapter.block.CargoSlots
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.adapter.block.toHolderId
import com.tracel.plugin.adapter.block.toPlacedBlockId
import com.tracel.plugin.adapter.entity.toBlockPos
import com.tracel.plugin.adapter.item.*
import com.tracel.plugin.capture.commit.CommitQueue
import com.tracel.plugin.capture.reads.InventoryReads
import com.tracel.plugin.capture.settle.AutomationSettle
import com.tracel.plugin.capture.slots.SlotLayouts
import com.tracel.plugin.listener.support.drop.BlockRelease
import com.tracel.plugin.listener.support.drop.CraftDrop
import com.tracel.plugin.listener.support.drop.recordAt
import com.tracel.plugin.listener.support.drop.releaseAsTrackedDrops
import com.tracel.plugin.listener.support.flow.destroyedFlows
import com.tracel.plugin.listener.support.flow.ignoranceIsPermanent
import com.tracel.plugin.listener.support.flow.isLedgeredHolder
import com.tracel.plugin.listener.support.flow.worldgenMintFlows
import com.tracel.plugin.services.TracelServices
import com.tracel.plugin.util.holder.carriesCoordinates
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.CraftingInventory
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.*

const val DEATH_READ_QUIET_MILLIS = 1_000L

/**
 * Transaction log only.
 *
 * - Ring paths ([moved] / [single] / [released]) have no coordinates, so UUID holders miss
 * the spatial index
 * - [reconcile] / [positioned] / [direct] carry `at`. Look at what changed
 */
class MaterialCapture internal constructor(private val services: TracelServices) {
    private val commits = CommitQueue(services)
    private val slotLayouts = SlotLayouts(services, commits)
    private val reads = InventoryReads(services, this)
    private val automation = AutomationSettle(services, this, commits)

    private fun committing(what: String, work: suspend () -> Unit) = commits.committing(what, work)

    /** One inventory read per player per tick. */
    fun scheduleReconcile(
        player: Player,
        inventories: Collection<Inventory> = listOf(player.inventory),
        cause: CauseKind = CauseKind.PLAYER_ACTION,
    ) = reads.scheduleReconcile(player, inventories, cause)

    /** Updates the timestamp for when a craft is owed to a specific [player]. */
    fun craftOwed(player: UUID) = reads.craftOwed(player)

    /** Removes the specified [player] from the list of players who are owed crafts. */
    fun craftBooked(player: UUID) = reads.craftBooked(player)

    /** [player] just died: the reads their death queued would book the dropped pockets as burned. */
    fun died(player: UUID) = reads.died(player)

    /** [player] is back from death: empty pockets until the queued delivery lands, a read now burns what it owes. */
    fun respawned(player: UUID) = reads.respawned(player)

    /** Whether a queued click read will diff [holder]: it accounts for what the click moved in or out of it. */
    fun reconcilePending(holder: HolderId): Boolean = reads.reconcilePending(holder)

    /** Snapshot without booking a transaction. */
    fun scheduleRebaseline(player: Player) = reads.scheduleRebaseline(player)

    /** Books what automation really did to [inventories], read a tick later and diffed together. */
    fun settleLater(at: Location, cause: CauseKind, inventories: Map<HolderId, Inventory>) =
        automation.settleLater(at, cause, inventories)

    /** Captures the layout of occupied slots in a cargo container and records it for the given holder. */
    fun captureSlotLayout(holder: HolderId, cargo: CargoSlots) = slotLayouts.capture(holder, cargo)

    /** Whether [holder] already has a snapshot, so [seedOnOpen] has nothing to do: skip reading its contents. */
    fun seeded(holder: HolderId): Boolean = services.differ.seeded(holder)

    /**
     * First look inside [holder] this session: what it holds past the ledger is the world's, minted in
     * its own transaction before any click. Found inside the click, the mint sat in the taker's own
     * transaction, and rolling the taker back burned it instead of putting it back.
     */
    fun seedOnOpen(holder: HolderId, totals: Map<ItemKey, Long>, at: BlockPos?, baseline: Boolean = false) {
        if (services.differ.seeded(holder)) return
        // a hopper settles by diff a tick later: unseeded then, the diff minted the stock over again
        if (baseline) services.differ.rebaseline(holder, totals)
        committing("unseen stock at $holder") {
            val believed = services.ledger.totalsAt(holder).mapValues { it.value.raw }
            val shortfall = LinkedHashMap<ItemKey, Long>()
            for ((itemKey, qty) in totals) {
                val missing = qty - (believed[itemKey] ?: 0L)
                if (missing > 0L) shortfall[itemKey] = missing
            }
            if (shortfall.isNotEmpty()) {
                services.capture.recordDirect(
                    worldgenMintFlows(shortfall, holder),
                    System.currentTimeMillis(),
                    CauseKind.WORLD,
                    null,
                    at
                )
            }
        }
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
        at: BlockPos? = null,
    ) {
        if (from is HolderId.ItemEntity) {
            val flow = Flow(itemKey, Quantity(quantity), from, to, FlowKind.MOVE)
            if (services.blockReleases.afterRelease(flow, cause, causedBy, epochMillis, at)) return
        }
        if (at != null) {
            val deltas = listOf(InventoryDelta(from, itemKey, -quantity), InventoryDelta(to, itemKey, quantity))
            if (services.gate.parkedDeltas(PlacedDeltas(deltas, epochMillis, cause, causedBy, at))) return
            committing("$cause move $from -> $to") {
                services.capture.record(deltas, epochMillis, cause, causedBy, at, ::ignoranceIsPermanent)
            }
            return
        }
        if (services.gate.move(cause, causedBy, epochMillis, itemKey, from, to, quantity)) return
        committing("$cause move $from -> $to") {
            services.capture.record(
                listOf(InventoryDelta(from, itemKey, -quantity), InventoryDelta(to, itemKey, quantity)),
                epochMillis, cause, causedBy, mintShortfall = ::ignoranceIsPermanent,
            )
        }
    }

    fun packedShulker(block: Block, drop: ItemStack, cause: CauseKind, causedBy: HolderId?) {
        val product = drop.toItemKey()
        val contents = block.toHolderId()
        val placed = block.toPlacedBlockId()
        val at = block.toBlockPos()
        val epochMillis = System.currentTimeMillis()
        committing("shulker packed at $at") {
            val inside = services.ledger.totalsAt(contents)
            if (inside.isEmpty()) return@committing
            val ingredients = inside.map { (key, qty) -> Ingredient(contents, key, qty) } +
                    services.ledger.totalsAt(placed).map { (key, qty) -> Ingredient(placed, key, qty) }
            services.capture.recordCraft(ingredients, Product(placed, product, Quantity(1)), epochMillis, causedBy, at)
        }
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
        if (services.gate.single(cause, causedBy, epochMillis, itemKey, holder, delta)) return
        committing("$cause at $holder") {
            services.capture.record(
                listOf(InventoryDelta(holder, itemKey, delta)),
                epochMillis,
                cause,
                causedBy,
                mintShortfall = ::ignoranceIsPermanent
            )
        }
    }

    /** Records multiple inventory deltas into the material tracking ledger. */
    fun many(
        cause: CauseKind,
        causedBy: HolderId?,
        deltas: List<InventoryDelta>,
        epochMillis: Long = System.currentTimeMillis(),
    ) {
        if (deltas.isEmpty()) return
        if (services.gate.many(cause, causedBy, epochMillis, deltas)) return
        committing("$cause, ${deltas.size} deltas") {
            services.capture.record(deltas, epochMillis, cause, causedBy, mintShortfall = ::ignoranceIsPermanent)
        }
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
        if (services.gate.release(cause, causedBy, epochMillis, from, to)) return
        committing("$cause release $from -> $to") {
            val flows = services.ledger.releaseFlows(from, to)
            if (flows.isNotEmpty()) services.capture.recordDirect(flows, epochMillis, cause, causedBy)
        }
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
        anonymous: Boolean = false,
    ) {
        val causedBy = HolderId.Player(player.uniqueId)
        val blamed = if (anonymous) null else causedBy
        val ledgered = player.isLedgeredHolder()

        val totalsByHolder = mutableMapOf<HolderId, MutableMap<ItemKey, Long>>()
        for (inventory in inventories) {
            val transient = inventory.transientInputs()
            val holder = if (transient != null) causedBy else inventory.toHolderId() ?: continue
            if (!ledgered && holder == causedBy) continue
            if (transient == null && (holder is HolderId.Block || holder is HolderId.Entity)) slotLayouts.capture(
                holder,
                inventory
            )
            val totals =
                transient ?: (inventory as? CraftingInventory)?.matrix?.toItemTotals() ?: inventory.toItemTotals()
            val merged = totalsByHolder.getOrPut(holder) { mutableMapOf() }
            for ((key, qty) in totals) merged.merge(key, qty, Long::plus)
        }
        totalsByHolder[causedBy]?.let { mine -> totalsByHolder[causedBy] = mine.withCursor(player).toMutableMap() }
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
            if (anonymous) {
                gapped(deltas, epochMillis, cause, at)
                return
            }
            if (placed) many(cause, blamed, deltas, epochMillis)
            else committing("$cause by $causedBy") {
                services.capture.record(deltas, epochMillis, cause, blamed, at, ::ignoranceIsPermanent)
            }
            return
        }

        committing("$cause by $causedBy") {
            val raw = totalsByHolder.flatMap { (holder, totals) -> services.differ.diff(holder, totals) }
            val deltas = if (anonymous) raw.map { it.copy(fromGap = true) } else raw
            if (deltas.isEmpty()) return@committing
            services.capture.record(deltas, epochMillis, cause, blamed, at, ::ignoranceIsPermanent)
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

    /**
     * [holder]'s contents were destroyed where they sat and nothing was dropped, e.g., a chest a
     * `WorldEdit` edit overwrote.
     */
    fun destroyed(
        holder: HolderId,
        present: Map<ItemKey, Long>,
        at: BlockPos,
        cause: CauseKind,
        causedBy: HolderId?,
        epochMillis: Long = System.currentTimeMillis(),
    ) {
        if (present.isEmpty()) return
        committing("$cause destroyed $holder") {
            val believed = services.ledger.totalsAt(holder).mapValues { it.value.raw }
            val flows = destroyedFlows(holder, believed, present)
            if (flows.isNotEmpty()) services.capture.recordDirect(flows, epochMillis, cause, causedBy, at)
        }
        forget(holder)
    }

    /** A tool lost or regained durability. */
    fun worn(player: Player, stack: ItemStack, before: Int, after: Int) {
        val holder = HolderId.Player(player.uniqueId)
        val itemKey = stack.toItemKey()
        val at = player.toBlockPos()
        val epochMillis = System.currentTimeMillis()
        committing("wear by $holder") { services.wearCapture.record(holder, itemKey, before, after, epochMillis, at) }
    }

    /** Records a crafting action into the material tracking ledger. */
    suspend fun crafted(
        ingredients: List<Ingredient>,
        product: Product,
        causedBy: HolderId?,
        at: BlockPos?,
        epochMillis: Long = System.currentTimeMillis(),
    ): Transaction = services.capture.recordCraft(ingredients, product, epochMillis, causedBy, at)

    /**
     * Player craft in one unit of work: pick the gain the recipe made, book [ingredients] into it, and
     * book the rest of the same diff (a cake's buckets, a honey block's bottles) instead of dropping it.
     *
     * [seeded] is the diff the region thread already took; without it the diff happens here, against
     * whatever snapshot the storage thread finds. [thrown] is the product dropped straight from the
     * result slot. No product at all books the rest and hands [unmatched] the gain count.
     */
    internal suspend fun craftedByPlayer(
        player: HolderId,
        totals: Map<ItemKey, Long>,
        seeded: List<InventoryDelta>?,
        produced: String?,
        ingredients: List<Ingredient>,
        thrown: List<CraftDrop.Thrown>,
        at: BlockPos,
        epochMillis: Long,
        productDamage: Int?,
        unmatched: (gains: Int) -> Unit,
    ) {
        services.atomically {
            val diff = seeded ?: services.differ.diff(player, totals)
            val gains = diff.filter { it.delta > 0 }
            val gain = when {
                gains.size == 1 -> gains.single()
                // Recipe names this craft among other same-tick gains
                produced != null -> gains.singleOrNull { it.itemKey.material == produced }
                else -> null
            }
            val (dropped, strays) = thrown.partition { produced != null && it.itemKey.material == produced }
            val net = LinkedHashMap<ItemKey, Long>()
            for ((_, itemKey, delta1) in diff) net.merge(itemKey, delta1, Long::plus)
            val flows = ArrayList<Flow>()
            for ((pile, itemKey, quantity) in strays) {
                net.merge(itemKey, quantity, Long::plus)
                flows += Flow(itemKey, Quantity(quantity), player, pile, FlowKind.MOVE)
            }

            val productKey = gain?.itemKey ?: dropped.firstOrNull()?.itemKey
            if (productKey == null) {
                unmatched(gains.size)
            } else {
                val quantity = (gain?.delta ?: 0L) + dropped.filter { it.itemKey == productKey }.sumOf { it.quantity }
                if (gain != null) net.merge(productKey, -gain.delta, Long::plus)
                for ((_, itemKey, quantity1) in ingredients) net.merge(itemKey, quantity1.raw, Long::plus)
                val transaction =
                    crafted(ingredients, Product(player, productKey, Quantity(quantity)), player, at, epochMillis)
                if (productDamage != null) {
                    val output = transaction.lots.last { it.flowIndex == ingredients.size }.lotId
                    services.wear.record(WearMark(output, epochMillis, productDamage, productDamage))
                }
                for ((pile1, itemKey, quantity1) in dropped) {
                    if (itemKey == productKey) flows += Flow(
                        productKey,
                        Quantity(quantity1),
                        player,
                        pile1,
                        FlowKind.MOVE
                    )
                }
            }
            if (flows.isNotEmpty()) services.capture.recordDirect(
                flows,
                epochMillis,
                CauseKind.PLAYER_ACTION,
                player,
                at,
                ::ignoranceIsPermanent
            )
            val rest = net.filterValues { it != 0L }.map { (key, delta) -> InventoryDelta(player, key, delta) }
            if (rest.isNotEmpty()) services.capture.record(
                rest,
                epochMillis,
                CauseKind.CRAFT,
                player,
                at,
                ::ignoranceIsPermanent
            )
        }
    }

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
        services.differ.rebaseline(HolderId.Player(player.uniqueId), player.heldTotals())
    }

    /** Forgets the snapshot associated with the specified holder. */
    fun forget(holder: HolderId) {
        services.differ.forget(holder)
    }

    private fun gapped(deltas: List<InventoryDelta>, epochMillis: Long, cause: CauseKind, at: BlockPos) {
        val gaps = deltas.map { it.copy(fromGap = true) }
        committing("$cause, a gap") {
            services.capture.record(
                gaps,
                epochMillis,
                cause,
                null,
                at,
                ::ignoranceIsPermanent
            )
        }
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

}
