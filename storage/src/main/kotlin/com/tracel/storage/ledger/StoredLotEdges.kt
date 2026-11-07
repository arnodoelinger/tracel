package com.tracel.storage.ledger

import com.tracel.engine.ledger.repository.LotEdges
import com.tracel.model.item.Quantity
import com.tracel.model.lot.LotEdge
import com.tracel.model.lot.LotId
import com.tracel.model.rollback.RollbackJobId
import com.tracel.model.transaction.TxnId
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.EdgeRecord
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Tag
import com.tracel.storage.history.QueryProbe
import com.tracel.storage.history.walkWanted
import com.tracel.storage.Interning
import com.tracel.storage.util.eachRow
import java.lang.foreign.MemorySegment

/**
 * The edges of the lot graph, each one written both ways:
 * out of its parent and into its child.
 */
internal class StoredLotEdges(
    private val storage: TracelStorage,
    private val cache: LotCache,
) : LotEdges {
    private val interning: Interning get() = storage.interning

    /** Writes the edge [packed] both ways, out of [parent] and into [child], so either end finds it. */
    fun link(unit: StorageUnit, parent: Long, child: Long, packed: ByteArray) {
        unit.put(Tag.EDGE_FROM[parent, child], packed)
        unit.put(Tag.EDGE_INTO[child, parent], packed)
    }

    override suspend fun recordEdge(edge: LotEdge) {
        storage.write {
            val packed = when (edge) {
                is LotEdge.Split -> EdgeRecord.of(EdgeRecord.SPLIT, edge.quantity.raw, 0, 0)
                is LotEdge.Transform -> EdgeRecord.of(
                    EdgeRecord.TRANSFORM,
                    edge.quantity.raw,
                    edge.craftedBy.raw,
                    interning.internHolder(this, edge.producedAt),
                )

                is LotEdge.Compensate -> EdgeRecord.of(
                    EdgeRecord.COMPENSATE,
                    edge.quantity.raw,
                    edge.rollbackJob.raw,
                    0,
                )
            }
            link(this, edge.parent.raw, edge.child.raw, packed)
            cache.changed(this, edge.parent.raw, edge.child.raw)
        }
    }

    override suspend fun removeEdge(parent: LotId, child: LotId) {
        storage.write {
            delete(Tag.EDGE_FROM[parent.raw, child.raw])
            delete(Tag.EDGE_INTO[child.raw, parent.raw])
            cache.changed(this, parent.raw, child.raw)
        }
    }

    override suspend fun edgesFrom(lotId: LotId): List<LotEdge> = storage.read {
        readEdgesFrom(lotId)
    }

    override suspend fun findCompensateEdge(originalLotId: LotId, job: RollbackJobId): LotEdge.Compensate? =
        storage.read {
            eachRow(Tag.EDGE_FROM[originalLotId.raw]) { cursor ->
                val value = cursor.value()
                if (EdgeRecord.kind[value] != EdgeRecord.COMPENSATE) return@eachRow
                if (EdgeRecord.reference[value] != job.raw) return@eachRow
                return@read LotEdge.Compensate(
                    LotId(KeyReader.u64(cursor.key(), 9)),
                    originalLotId,
                    Quantity(EdgeRecord.quantity[value]),
                    job,
                )
            }
            null
        }

    override suspend fun edgesFromAll(ids: Collection<LotId>): Map<LotId, List<LotEdge>> {
        if (ids.isEmpty()) return emptyMap()
        val unique = ids.distinct()
        val sorted = rawsOf(unique)
        return storage.read {
            val out = HashMap<LotId, MutableList<LotEdge>>(unique.size)
            QueryProbe.cursors(1)
            QueryProbe.scanned(sorted.size.toLong())
            walkWanted(Tag.EDGE_FROM, sorted, 0, sorted.size) { at, cursor ->
                out.getOrPut(LotId(at)) { ArrayList() } +=
                    decodeEdge(this, cursor.value(), parent = at, child = cursor.keyU64(9))
            }
            for (id in unique) out.putIfAbsent(id, ArrayList(0))
            out
        }
    }

    override suspend fun edgesIntoAll(ids: Collection<LotId>): Map<LotId, List<LotEdge>> {
        if (ids.isEmpty()) return emptyMap()
        val unique = ids.distinct()
        val sorted = rawsOf(unique)
        return storage.read {
            val out = HashMap<LotId, MutableList<LotEdge>>(unique.size)
            QueryProbe.cursors(1)
            QueryProbe.scanned(sorted.size.toLong())
            walkWanted(Tag.EDGE_INTO, sorted, 0, sorted.size) { at, cursor ->
                out.getOrPut(LotId(at)) { ArrayList() } +=
                    decodeEdge(this, cursor.value(), parent = cursor.keyU64(9), child = at)
            }
            for (id in unique) out.putIfAbsent(id, ArrayList(0))
            out
        }
    }

    override suspend fun edgesInto(lotId: LotId): List<LotEdge> = storage.read {
        readEdgesInto(lotId)
    }

    private fun StorageUnit.readEdgesFrom(lotId: LotId): MutableList<LotEdge> {
        val out = ArrayList<LotEdge>()
        eachRow(Tag.EDGE_FROM[lotId.raw]) { cursor ->
            out += decodeEdge(this, cursor.value(), parent = lotId.raw, child = KeyReader.u64(cursor.key(), 9))
        }
        return out
    }

    private fun StorageUnit.readEdgesInto(lotId: LotId): MutableList<LotEdge> {
        val out = ArrayList<LotEdge>()
        eachRow(Tag.EDGE_INTO[lotId.raw]) { cursor ->
            out += decodeEdge(this, cursor.value(), parent = KeyReader.u64(cursor.key(), 9), child = lotId.raw)
        }
        return out
    }

    private fun decodeEdge(
        unit: StorageUnit,
        value: MemorySegment,
        parent: Long,
        child: Long,
    ): LotEdge {
        val quantity = Quantity(EdgeRecord.quantity[value])
        return when (val kind = EdgeRecord.kind[value]) {
            EdgeRecord.SPLIT -> LotEdge.Split(LotId(child), LotId(parent), quantity)
            EdgeRecord.TRANSFORM -> LotEdge.Transform(
                LotId(child),
                LotId(parent),
                quantity,
                TxnId(EdgeRecord.reference[value]),
                interning.resolveHolder(unit, EdgeRecord.producedAt[value]),
            )

            EdgeRecord.COMPENSATE -> LotEdge.Compensate(
                LotId(child),
                LotId(parent),
                quantity,
                RollbackJobId(EdgeRecord.reference[value]),
            )

            else -> error("unrecognized lot edge kind: $kind.")
        }
    }
}
