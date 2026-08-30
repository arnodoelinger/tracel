package com.tracel.engine.log

import com.tracel.annotations.Reads
import com.tracel.annotations.RunsOn
import com.tracel.annotations.SingleWriter
import com.tracel.annotations.ThreadContext
import com.tracel.annotations.isBookkeeping
import com.tracel.engine.ownership.SingleWriterGuard
import com.tracel.model.flow.FlowLot
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.id.WorldId
import com.tracel.model.transaction.Transaction
import java.util.NavigableMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

/** In-memory [TransactionLog]. */
@SingleWriter
@RunsOn(ThreadContext.STORAGE)
public class InMemoryTransactionLog : TransactionLog {
    private val writer = SingleWriterGuard()
    private val transactions = ConcurrentHashMap<TxnId, Transaction>()
    private val bySeq = ConcurrentSkipListMap<Long, Transaction>()
    private val byTime = ConcurrentSkipListMap<Stamp, Transaction>()

    override suspend fun append(transaction: Transaction) {
        writer.checkIn()
        check(transactions.putIfAbsent(transaction.id, transaction) == null) {
            "transaction ${transaction.id} already appended — the log is append-only"
        }
        bySeq.putIfAbsent(transaction.seq.raw, transaction)
        byTime.putIfAbsent(Stamp(transaction.epochMillis, transaction.seq.raw), transaction)
    }

    @Reads
    override suspend fun find(id: TxnId): Transaction? = transactions[id]

    @Reads
    override suspend fun query(filter: LookupFilter): List<Transaction> {
        val wanted = wantedCount(filter.offset, filter.limit)
        if (wanted <= 0) return emptyList()
        val view = seqView(filter) ?: return emptyList()
        val matched = ArrayList<Transaction>()
        for (txn in view.descendingMap().values) {
            if (!txn.matches(filter)) continue
            matched += txn
            if (matched.size == wanted) break
        }
        if (matched.isEmpty()) return emptyList()
        val from = filter.offset
        if (from >= matched.size) return emptyList()
        if (from == 0) return matched
        return ArrayList(matched.subList(from, matched.size))
    }

    @Reads
    override suspend fun lotsAt(seq: Seq): List<FlowLot> = bySeq[seq.raw]?.lots.orEmpty()

    @Reads
    override suspend fun lotsAtAll(seqs: List<Seq>): Map<Seq, List<FlowLot>> {
        if (seqs.isEmpty()) return emptyMap()
        val out = HashMap<Seq, List<FlowLot>>()
        for (seq in seqs) {
            val lots = bySeq[seq.raw]?.lots ?: continue
            if (lots.isNotEmpty()) out[seq] = lots
        }
        return if (out.isEmpty()) emptyMap() else out
    }

    private fun seqView(filter: LookupFilter): NavigableMap<Long, Transaction>? {
        val since = filter.since
        val until = filter.until
        if (since == null && until == null) return bySeq
        val from = Stamp(since ?: Long.MIN_VALUE, Long.MIN_VALUE)
        val to = Stamp(until ?: Long.MAX_VALUE, Long.MAX_VALUE)
        val window = byTime.subMap(from, true, to, true)
        if (window.isEmpty()) return null
        var minSeq = Long.MAX_VALUE
        var maxSeq = Long.MIN_VALUE
        for ((_, seq1) in window.values) {
            val seq = seq1.raw
            if (seq < minSeq) minSeq = seq
            if (seq > maxSeq) maxSeq = seq
        }
        return bySeq.subMap(minSeq, true, maxSeq, true)
    }

    private fun wantedCount(offset: Int, limit: Int): Int {
        if (limit <= 0) return 0
        val total = offset.toLong() + limit.toLong()
        return if (total >= Int.MAX_VALUE) Int.MAX_VALUE else total.toInt()
    }

    private fun Transaction.matches(filter: LookupFilter): Boolean {
        if (cause.isBookkeeping) return false
        if (filter.since != null && epochMillis < filter.since) return false
        if (filter.until != null && epochMillis > filter.until) return false
        if (filter.causes.isNotEmpty() && cause !in filter.causes) return false
        if (filter.excludedCauses.isNotEmpty() && cause in filter.excludedCauses) return false
        if (!holdersMatch(filter.holders, filter.excludedHolders)) return false
        val material = filter.material
        if (material != null) {
            var hit = false
            for ((itemKey) in flows) {
                if (itemKey.material == material) {
                    hit = true
                    break
                }
            }
            if (!hit) return false
        }
        return positionsMatch(filter.world, filter.region)
    }

    private fun Transaction.holdersMatch(wanted: Set<HolderId>, excluded: Set<HolderId>): Boolean {
        if (wanted.isEmpty() && excluded.isEmpty()) return true
        var hitWanted = wanted.isEmpty()
        fun consider(holder: HolderId): Boolean {
            if (excluded.isNotEmpty() && holder in excluded) return false
            if (!hitWanted && holder in wanted) hitWanted = true
            return true
        }
        val by = causedBy
        if (by != null && !consider(by)) return false
        for ((_, _, source, destination) in flows) {
            if (!consider(source)) return false
            if (!consider(destination)) return false
        }
        return hitWanted
    }

    private fun Transaction.positionsMatch(world: WorldId?, region: LookupRegion?): Boolean {
        if (world == null && region == null) return true
        var worldHit = world == null
        var regionHit = region == null
        fun consider(posWorld: WorldId, x: Int, y: Int, z: Int): Boolean {
            if (!worldHit && posWorld == world) worldHit = true
            if (!regionHit && region != null && region.contains(posWorld, x, y, z)) regionHit = true
            return worldHit && regionHit
        }
        val at = at
        if (at != null && consider(at.world, at.x, at.y, at.z)) return true
        val by = causedBy
        if (by != null && by.considerPosition(::consider)) return true
        for ((_, _, source, destination) in flows) {
            if (source.considerPosition(::consider)) return true
            if (destination.considerPosition(::consider)) return true
        }
        return worldHit && regionHit
    }

    private inline fun HolderId.considerPosition(consider: (WorldId, Int, Int, Int) -> Boolean): Boolean =
        when (this) {
            is HolderId.Block -> consider(world, x, y, z)
            is HolderId.PlacedBlock -> consider(world, x, y, z)
            else -> false
        }

    private fun LookupRegion.contains(world: WorldId, x: Int, y: Int, z: Int): Boolean =
        this.world == world && containsBlock(x, y, z)

    @Reads
    public fun all(): Collection<Transaction> = transactions.values

    private data class Stamp(val millis: Long, val seq: Long) : Comparable<Stamp> {
        override fun compareTo(other: Stamp): Int {
            val byTime = millis.compareTo(other.millis)
            return if (byTime != 0) byTime else seq.compareTo(other.seq) // Stop polluting the heap
        }
    }
}
