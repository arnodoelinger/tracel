package com.tracel.storage.history

import com.tracel.engine.log.TransactionLog
import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowLot
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.item.namesMaterial
import com.tracel.model.log.Seq
import com.tracel.model.transaction.Transaction
import com.tracel.model.transaction.TxnId
import com.tracel.model.world.BlockPos
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.chrono.*
import com.tracel.storage.chrono.capsule.CapsuleView
import com.tracel.storage.chrono.query.Found
import com.tracel.storage.chrono.query.Sieve
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Packed
import com.tracel.storage.codec.Tag
import com.tracel.storage.util.eachRow
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.ints.IntSet

/**
 * The item log, kept by place: every flow of every transaction is a row in the capsules of the tiles the transaction
 * happened in, or touched through a block it moved items to or from.
 *
 * A transaction that happened nowhere in particular goes to tile zero of world zero. One that touches blocks in other
 * tiles is filed in each of them, whole, and read back once. The only row of its own is [Tag.TXN_BY_ID], which says
 * which sequence an id has: the one thing a capsule cannot be asked.
 */
class StoredTransactionLog(storage: TracelStorage, chrono: Chrono = Chrono.of(storage)) :
    AbstractChronoLog(storage, chrono), TransactionLog {
    override suspend fun append(transaction: Transaction) {
        storage.write {
            val seq = transaction.seq.raw
            val causedById = transaction.causedBy?.let { interning.internHolder(this, it) } ?: 0
            val at = transaction.at
            val atWorldId = at?.let { interning.internWorld(this, it.world) } ?: 0
            val cell = if (at == null) 0 else Cell.of(at.x, at.y, at.z).packed
            val kindCause = transaction.cause
            val writer = chrono.writer(this, Lane.ITEMS)

            val tiles = LinkedHashSet<Long>()
            val home = (if (at == null) Tile.of(0, 0, 0) else Tile.at(atWorldId, at.x, at.z)).packed
            tiles += home
            fun touch(holder: HolderId?) {
                val pos = holder?.let(::blockOf) ?: return
                tiles += Tile.at(interning.internWorld(this, pos.world), pos.x, pos.z).packed
            }
            touch(transaction.causedBy)

            val flows = transaction.flows
            val ids = Array(flows.size) { i ->
                val flow = flows[i]
                touch(flow.source)
                touch(flow.destination)
                intArrayOf(
                    interning.internItemKey(this, flow.itemKey), interning.internHolder(this, flow.source),
                    interning.internHolder(this, flow.destination),
                )
            }
            val lots = ItemRows.lotsByFlow(transaction.lots, flows.size)
            check(!writer.holds(seq) && tiles.none { chrono.holds(this, Tile(it), Lane.ITEMS, seq) }) {
                "transaction ${transaction.id} already appended — the log is append-only"
            }
            for (tile in tiles) {
                for ((i, flow) in flows.withIndex()) {
                    writer.addFlow(
                        Tile(tile),
                        seq,
                        transaction.epochMillis,
                        cell,
                        causedById,
                        ItemRows.kindOf(kindCause, flow.kind),
                        ids[i][0],
                        ids[i][1],
                        ids[i][2],
                        i,
                        flow.quantity.raw,
                        transaction.id.raw,
                        lots[i],
                        Tile(home),
                    )
                }
            }
        }
    }

    /**
     * The transaction with [id], found by reading every flow there is, newest first. Nothing asks for a transaction by
     * id while the plugin runs, so there is no index of ids: it would be a row per transaction for a question nobody
     * puts.
     */
    override suspend fun find(id: TxnId): Transaction? = storage.read {
        val found = chrono.reader(this).selectAnywhere(null, Sieve.ALL, Lane.ITEMS)
        val seq = found.firstNotNullOfOrNull { f ->
            f.rows.uuidLow?.let { ids ->
                (0 until f.rows.size).firstOrNull { ids[it] == id.raw }?.let { f.rows.seq[it] }
            }
        }
            ?: return@read null
        transactionsOf(
            this,
            found.map { it ->
                it.tile to it.rows.let { rows ->
                    rows.pick(IntArray(rows.size) { i -> i }.filter { rows.seq[it] == seq }.toIntArray())
                }
            },
            withLots = true
        )[seq]
    }

    override suspend fun lotsAt(seq: Seq): List<FlowLot> = lotsAtAll(listOf(seq))[seq].orEmpty()

    override suspend fun lotsAtAll(seqs: List<Seq>): Map<Seq, List<FlowLot>> = storage.read {
        val loaded = loadLots(this, LongArray(seqs.size) { seqs[it].raw })
        val out = HashMap<Seq, List<FlowLot>>(loaded.size)
        for ((seq, lots) in loaded) out[Seq(seq)] = lots
        out
    }

    /** The lots at every sequence in [seqs] that moved any. */
    internal fun loadLots(unit: StorageUnit, seqs: LongArray): Map<Long, List<FlowLot>> {
        val found = transactionsOfViews(unit, chrono.holding(unit, Lane.ITEMS, seqs), withLots = true)
        val wanted = seqs.toHashSet()
        val out = HashMap<Long, List<FlowLot>>()
        for ((seq, txn) in found) if (seq in wanted && txn.lots.isNotEmpty()) out[seq] = txn.lots
        return out
    }

    override suspend fun query(filter: LookupFilter): List<Transaction> =
        storage.read { select(this, filter, withLots = false) }

    /** [query] with the lots on every transaction, for a caller that reads them anyway. */
    @Suppress("DEPRECATION")
    internal fun select(unit: StorageUnit, filter: LookupFilter, withLots: Boolean): List<Transaction> {
        val ids = interning.idsFor(unit, filter)
        if (ids.matchesNothing) return emptyList()
        val materialIds = itemKeyIdsFor(unit, filter)
        if (materialIds != null && materialIds.isEmpty()) return emptyList()

        val causes = filter.causes
        val sieve = Sieve(
            since = filter.since ?: Long.MIN_VALUE,
            until = filter.until ?: Long.MAX_VALUE,
            kinds = { kind ->
                val cause = ItemRows.causeOf(kind)
                !cause.isBookkeeping && (causes.isEmpty() || cause in causes) && cause !in filter.excludedCauses
            },
        )
        val reader = chrono.reader(unit)
        val found = ArrayList<Found>()
        val region = filter.region
        var walkAll = false
        when {
            region != null -> {
                val worldId = ids.regionWorld ?: return emptyList()
                val tiles = ArrayList<Tile>()
                for (x in region.minTileX..region.maxTileX) for (z in region.minTileZ..region.maxTileZ) tiles += Tile.of(
                    worldId,
                    x,
                    z
                )
                found += reader.select(tiles, sieve, Lane.ITEMS)
            }

            filter.holders.isNotEmpty() -> {
                // A player or an entity is found by its track; a block is a place, and its transactions are in its tile.
                val tiles = LinkedHashSet<Tile>()
                for (holder in filter.holders) {
                    val pos = blockOf(holder)
                    if (pos != null) {
                        interning.findWorldId(unit, pos.world)?.let { tiles += Tile.at(it, pos.x, pos.z) }
                    } else if (holder is HolderId.Player) {
                        interning.findHolderId(unit, holder)
                            ?.let { found += reader.selectAnywhere(it, sieve, Lane.ITEMS) }
                    } else {
                        walkAll = true
                    }
                }
                if (tiles.isNotEmpty()) found += reader.select(tiles, sieve, Lane.ITEMS)
                // Only players are tracked: any other holder is found by reading what there is, newest first.
                if (walkAll) found += reader.selectAnywhere(null, sieve, Lane.ITEMS)
            }

            materialIds != null -> for (item in materialIds) found += reader.selectAnywhere(-item, sieve, Lane.ITEMS)
            else -> found += reader.selectAnywhere(null, sieve, Lane.ITEMS)
        }
        val byTile = found.map { it.tile to it.rows }
        val all = transactionsOf(unit, byTile, withLots)
        val out = all.values.filter { accepts(unit, it, filter, ids, materialIds) }.sortedByDescending { it.seq.raw }
        val from = filter.offset.coerceAtMost(out.size)
        val to = (from.toLong() + filter.limit).coerceAtMost(out.size.toLong()).toInt()
        return out.subList(from, to)
    }

    /** The interned ids of the item keys [filter] asks for: its exact keys, or else its material; `null` for any. */
    internal fun itemKeyIdsFor(unit: StorageUnit, filter: LookupFilter): IntSet? {
        val keys = filter.itemKeys
        val material = filter.material
        if (keys.isEmpty() && material == null) return null
        val out = IntOpenHashSet()
        unit.eachRow(byteArrayOf(Tag.INTERN_FORWARD.byte, Keys.NS_ITEM_KEY)) { cursor ->
            val key = Packed.decodeItemKey(cursor.value())
            val wanted =
                if (keys.isNotEmpty()) key in keys else material != null && key.material.namesMaterial(material)
            if (wanted) out.add(KeyReader.u32(cursor.key(), 2))
        }
        return out
    }

    override suspend fun itemKeys(): List<ItemKey> = storage.read {
        val out = ArrayList<ItemKey>()
        eachRow(
            byteArrayOf(
                Tag.INTERN_FORWARD.byte,
                Keys.NS_ITEM_KEY
            )
        ) { cursor -> out += Packed.decodeItemKey(cursor.value()) }
        out
    }

    // region rows to transactions

    @Suppress("SameParameterValue")
    private fun transactionsOfViews(
        unit: StorageUnit,
        views: List<Pair<Tile, CapsuleView>>,
        withLots: Boolean
    ): Map<Long, Transaction> =
        transactionsOf(unit, views.map { (tile, view) -> tile to view.rows() }, withLots)

    /** Every transaction in [parts], once, however many tiles it was filed in. Flows come back in index order. */
    private fun transactionsOf(
        unit: StorageUnit,
        parts: List<Pair<Tile, Rows>>,
        withLots: Boolean
    ): Map<Long, Transaction> {
        val seen = HashSet<Pair<Long, Int>>()
        val bySeq = LinkedHashMap<Long, ArrayList<Pair<Rows, Int>>>()
        for ((_, rows) in parts) {
            val flow = rows.aux?.get(1) ?: continue
            for (r in 0 until rows.size) {
                if (!seen.add(rows.seq[r] to flow[r])) continue
                bySeq.getOrPut(rows.seq[r]) { ArrayList() } += rows to r
            }
        }
        val holders = HashMap<Int, HolderId>()
        fun holder(id: Int): HolderId = holders.getOrPut(id) { interning.resolveHolder(unit, id) }
        val items = HashMap<Int, ItemKey>()
        val out = LinkedHashMap<Long, Transaction>()
        for ((seq, refs) in bySeq) {
            refs.sortBy { (rows, r) -> rows.aux!![1][r] }
            val (first, f) = refs[0]
            val flows = ArrayList<Flow>(refs.size)
            val lots = ArrayList<FlowLot>()
            for ((rows, r) in refs) {
                val index = rows.aux!![1][r]
                flows += Flow(
                    items.getOrPut(rows.before[r]) { interning.resolveItemKey(unit, rows.before[r]) },
                    Quantity(rows.uuidHigh!![r]),
                    holder(rows.after[r]),
                    holder(rows.aux[0][r]),
                    ItemRows.flowKindOf(rows.kind[r]),
                )
                if (withLots) rows.beforeExtrasAt(r)?.let { lots += ItemRows.readLots(index, it) }
            }
            val causedBy = first.actor[f]
            val cell = Cell(first.cell[f])
            out[seq] = Transaction(
                TxnId(first.uuidLow!![f]), Seq(seq), first.millis[f], ItemRows.causeOf(first.kind[f]),
                if (causedBy == 0) null else holder(causedBy), flows, lots,
                at = home(unit, first, f, cell),
            )
        }
        return out
    }

    /** Where the transaction happened: the home tile every copy carries, and the cell in it; `null` for nowhere. */
    private fun home(unit: StorageUnit, rows: Rows, i: Int, cell: Cell): BlockPos? {
        val aux = rows.aux!!
        val world = aux[2][i]
        if (world == 0) return null
        val tile = Tile.of(world, aux[3][i], aux[4][i])
        return BlockPos(interning.resolveWorld(unit, world), tile.originX + cell.x, cell.y, tile.originZ + cell.z)
    }

    // endregion

    // region filter

    private class HolderBlock(val worldId: Int, val x: Int, val y: Int, val z: Int)

    /**
     * Whether [txn] is one [filter] asks for. A transaction counts as in a region or a world when it happened there or
     * any holder it touches is a block there.
     */
    private fun accepts(
        unit: StorageUnit,
        txn: Transaction,
        filter: LookupFilter,
        ids: FilterIds,
        materialIds: IntSet?,
    ): Boolean {
        val touched = ArrayList<HolderId?>(1 + txn.flows.size * 2)
        touched += txn.causedBy
        for ((_, _, source, destination) in txn.flows) {
            touched += source
            touched += destination
        }
        fun idOf(holder: HolderId?): Int = holder?.let { interning.findHolderId(unit, it) } ?: 0
        if (ids.holders.isNotEmpty() && touched.none { idOf(it) in ids.holders }) return false
        if (ids.excluded.isNotEmpty() && touched.any { idOf(it) in ids.excluded }) return false
        if (materialIds != null && txn.flows.none {
                (interning.findItemKeyId(unit, it.itemKey) ?: -1) in materialIds
            }) return false
        val at = txn.at
        fun placeOf(holder: HolderId?): HolderBlock? {
            val pos = holder?.let(::blockOf) ?: return null
            val world = interning.findWorldId(unit, pos.world) ?: return null
            return HolderBlock(world, pos.x, pos.y, pos.z)
        }

        val places = touched.map { placeOf(it) }
        val happened =
            at?.let { pos -> interning.findWorldId(unit, pos.world)?.let { HolderBlock(it, pos.x, pos.y, pos.z) } }
        val spots = listOfNotNull(happened) + places.filterNotNull()
        if (ids.world != null && spots.none { it.worldId == ids.world }) return false
        val region = filter.region
        return !(region != null && (ids.regionWorld == null || spots.none {
            it.worldId == ids.regionWorld && region.containsBlock(
                it.x,
                it.y,
                it.z
            )
        }))
    }

    private fun blockOf(holder: HolderId): BlockPos? = when (holder) {
        is HolderId.Block -> BlockPos(holder.world, holder.x, holder.y, holder.z)
        is HolderId.PlacedBlock -> BlockPos(holder.world, holder.x, holder.y, holder.z)
        else -> null
    }

    // endregion
}
