package com.tracel.storage.ports.ops

import com.tracel.annotations.isBookkeeping
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.codec.records.ContainerSlot
import com.tracel.storage.codec.records.World
import com.tracel.storage.spi.EngineCursor
import com.tracel.storage.util.eachRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private const val SLICE = 10_000

/** What a partial purge may take. Everything else, the lot ledger included, is state and stays. */
enum class PurgeCategory {
    BLOCKS,
    ITEMS,
    CONTAINERS,
}

/**
 * What to take. Whatever is set has to match; a record that misses any of it stays.
 *
 * @property before only records older than this, in epoch millis
 * @property world only what happened in this world. Container visits have no world and stay
 * @property player only what this player did
 */
data class PurgeFilter(
    val before: Long? = null,
    val world: WorldId? = null,
    val player: UUID? = null,
)

/** A [filter] applied to [categories]. */
data class PurgeSpec(val categories: Set<PurgeCategory>, val filter: PurgeFilter = PurgeFilter()) {
    init {
        require(categories.isNotEmpty()) { "a purge of no category purges nothing" }
        require(PurgeCategory.CONTAINERS !in categories || filter.player == null) {
            "a container layout is nobody's doing, so it cannot be purged by player"
        }
    }
}

/** One category's share: [matched] of its [total] records, [rows] rows with the indexes, and the span they cover. */
data class PurgeTally(val total: Long, val matched: Long, val rows: Long, val oldest: Long?, val newest: Long?)

/** What a partial purge took, or would take. */
data class PurgeReport(val tallies: Map<PurgeCategory, PurgeTally>) {
    val matched: Long get() = tallies.values.sumOf { it.matched }
    val rows: Long get() = tallies.values.sumOf { it.rows }
    val oldest: Long? get() = tallies.values.mapNotNull { it.oldest }.minOrNull()
    val newest: Long? get() = tallies.values.mapNotNull { it.newest }.maxOrNull()
}

/** Counts what a purge of [spec] would take, and touches nothing. */
suspend fun previewPurge(storage: TracelStorage, spec: PurgeSpec): PurgeReport = purge(storage, spec, apply = false)

/**
 * Deletes what [spec] says, a slice at a time so the writers are never kept waiting, then compacts to
 * give the disk back.
 */
suspend fun purgeSome(storage: TracelStorage, spec: PurgeSpec): PurgeReport {
    val report = purge(storage, spec, apply = true)
    if (report.rows > 0) withContext(Dispatchers.IO) { storage.engine.compactEverything() }
    return report
}

private class Wanted(val before: Long?, val world: Int?, val player: Int?) {
    fun accepts(epoch: Long, worldId: Int, causedBy: Int): Boolean =
        (before == null || epoch < before) &&
                (world == null || world == worldId) &&
                (player == null || player == causedBy)

    companion object {
        const val MISSING = -1
    }
}

private class Count {
    var total = 0L
    var matched = 0L
    var rows = 0L
    var oldest = Long.MAX_VALUE
    var newest = Long.MIN_VALUE

    fun took(epoch: Long, rows: Int) {
        matched++
        this.rows += rows
        if (epoch < oldest) oldest = epoch
        if (epoch > newest) newest = epoch
    }

    fun tally() = PurgeTally(
        total, matched, rows,
        oldest.takeIf { it != Long.MAX_VALUE },
        newest.takeIf { it != Long.MIN_VALUE },
    )
}

private suspend fun purge(storage: TracelStorage, spec: PurgeSpec, apply: Boolean): PurgeReport {
    val filter = spec.filter
    val wanted = storage.read {
        Wanted(
            filter.before,
            filter.world?.let { storage.interning.findWorldId(this, it) ?: Wanted.MISSING },
            filter.player?.let { storage.interning.findHolderId(this, HolderId.Player(it)) ?: Wanted.MISSING },
        )
    }

    val tallies = LinkedHashMap<PurgeCategory, PurgeTally>()
    for (category in PurgeCategory.entries) {
        if (category !in spec.categories) continue
        val count = Count()
        when (category) {
            PurgeCategory.BLOCKS -> sweep(storage, Keys.tagPrefix(Keys.WCHG), apply) { cursor, doomed ->
                blocks(cursor, wanted, count, doomed)
            }

            PurgeCategory.ITEMS -> sweep(storage, Keys.tagPrefix(Keys.TXN), apply) { cursor, doomed ->
                items(cursor, wanted, count, doomed)
            }

            PurgeCategory.CONTAINERS -> {
                val sitsIn = HashMap<Int, Boolean>()
                sweep(storage, Keys.tagPrefix(Keys.CONTAINER_SLOT), apply) { cursor, doomed ->
                    layouts(cursor, filter, count, doomed) { holderId ->
                        sitsIn.getOrPut(holderId) {
                            val holder = runCatching { storage.interning.resolveHolder(this, holderId) }.getOrNull()
                            (holder as? HolderId.Block)?.world == filter.world
                        }
                    }
                }
                if (filter.world == null) sweep(storage, Keys.tagPrefix(Keys.ACTOR_VISIT), apply) { cursor, doomed ->
                    visits(cursor, filter, count, doomed)
                }
            }
        }
        tallies[category] = count.tally()
    }
    return PurgeReport(tallies)
}

private suspend fun sweep(
    storage: TracelStorage,
    prefix: ByteArray,
    apply: Boolean,
    judge: StorageUnit.(EngineCursor, MutableList<ByteArray>) -> Unit,
) {
    var from = prefix
    while (true) {
        val doomed = ArrayList<ByteArray>()
        var last: ByteArray? = null
        var more = false
        storage.read {
            scan(prefix, from).use { cursor ->
                var seen = 0
                while (true) {
                    if (seen == SLICE) {
                        more = true
                        break
                    }
                    if (!cursor.next()) break
                    last = cursor.key()
                    judge(cursor, doomed)
                    seen++
                }
            }
        }
        if (apply && doomed.isNotEmpty()) storage.write { for (key in doomed) delete(key) }
        val resume = last
        if (!more || resume == null) return
        from = resume + 0.toByte()
    }
}

private fun blocks(cursor: EngineCursor, wanted: Wanted, count: Count, doomed: MutableList<ByteArray>) {
    val v = cursor.value()
    count.total++
    val epoch = Records.wchgEpochMillis(v)
    val worldId = Records.wchgWorldId(v)
    val by = Records.wchgCausedBy(v)
    if (!wanted.accepts(epoch, worldId, by)) return

    val before = doomed.size
    val key = cursor.key()
    val seq = KeyReader.u64(key, 1)
    val x = Records.wchgX(v)
    val y = Records.wchgY(v)
    val z = Records.wchgZ(v)
    doomed += key
    when (Records.wchgKind(v)) {
        World.CHANGE_SECTION -> doomed += Keys.wchgAtSection(worldId, x shr 4, y shr 4, z shr 4, seq)
        World.CHANGE_ENTITY -> {
            doomed += Keys.wchgAt(worldId, x, y, z, seq)
            doomed += Keys.wchgEntity(Records.entityChangeUuid(v), seq)
        }

        else -> doomed += Keys.wchgAt(worldId, x, y, z, seq)
    }
    if (!Records.wchgCause(v).isBookkeeping) {
        if (by != 0) doomed += Keys.actor(by, seq)
        doomed += Keys.time(epoch, seq)
        doomed += Keys.spatial(worldId, x shr 4, z shr 4, y, seq, epoch)
    }
    count.took(epoch, doomed.size - before)
}

private fun StorageUnit.items(cursor: EngineCursor, wanted: Wanted, count: Count, doomed: MutableList<ByteArray>) {
    val v = cursor.value()
    count.total++
    val epoch = Records.txnEpochMillis(v)
    val worldId = Records.txnWorldId(v)
    val by = Records.txnCausedBy(v)
    if (!wanted.accepts(epoch, worldId, by)) return

    val before = doomed.size
    val key = cursor.key()
    val seq = KeyReader.u64(key, 1)
    doomed += key
    doomed += Keys.txnById(Records.txnId(v))
    if (!Records.txnCause(v).isBookkeeping) {
        val holders = HashSet<Int>()
        val itemKeys = HashSet<Int>()
        if (by != 0) holders += by
        for (flow in 0 until Records.txnFlowCount(v)) {
            holders += Records.flowSource(v, flow)
            holders += Records.flowDestination(v, flow)
            itemKeys += Records.flowItemKeyId(v, flow)
        }
        for (holderId in holders) doomed += Keys.actor(holderId, seq)
        for (itemKeyId in itemKeys) doomed += Keys.item(itemKeyId, seq)
        doomed += Keys.time(epoch, seq)
        if (worldId != 0) {
            doomed += Keys.spatial(worldId, Records.txnX(v) shr 4, Records.txnZ(v) shr 4, Records.txnY(v), seq, epoch)
        }
        eachRow(Keys.txnLotPrefix(seq)) { doomed += it.key() }
    }
    count.took(epoch, doomed.size - before)
}

private fun StorageUnit.layouts(
    cursor: EngineCursor,
    filter: PurgeFilter,
    count: Count,
    doomed: MutableList<ByteArray>,
    inWorld: StorageUnit.(Int) -> Boolean,
) {
    count.total++
    val epoch = ContainerSlot.layoutEpochMillis(cursor.value())
    if (filter.before != null && epoch >= filter.before) return
    if (filter.world != null && !inWorld(cursor.keyU32(1))) return
    doomed += cursor.key()
    count.took(epoch, 1)
}

private fun visits(cursor: EngineCursor, filter: PurgeFilter, count: Count, doomed: MutableList<ByteArray>) {
    count.total++
    val epoch = Keys.invert(cursor.keyU64(5))
    if (filter.before != null && epoch >= filter.before) return
    doomed += cursor.key()
    count.took(epoch, 1)
}
