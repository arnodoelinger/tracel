package com.tracel.storage.chrono

import com.tracel.model.cause.CauseKind
import com.tracel.model.flow.FlowKind
import com.tracel.model.flow.FlowLot
import com.tracel.model.item.Quantity
import com.tracel.model.lot.LotId
import com.tracel.storage.chrono.bits.ByteWriter
import com.tracel.storage.chrono.bits.SegmentReader
import java.lang.foreign.MemorySegment

/** How a flow is written into a row of the items lane, shared by whoever writes one. */
internal object ItemRows {
    private const val FLOW_BITS = 4

    init {
        check(FlowKind.entries.size <= 1 shl FLOW_BITS) { "${FlowKind.entries.size} flow kinds outgrew $FLOW_BITS bits." }
    }

    class IntArrayList(capacity: Int) {
        private var items = IntArray(maxOf(capacity, 1))
        private var size = 0

        fun add(v: Int) {
            if (size == items.size) items = items.copyOf(size * 2)
            items[size++] = v
        }

        fun addOnce(v: Int) {
            if (size == 0 || items[size - 1] != v) add(v)
        }

        fun toArray(): IntArray = items.copyOf(size)
    }

    fun kindOf(cause: CauseKind, flow: FlowKind): Int = (cause.ordinal shl FLOW_BITS) or flow.ordinal

    fun causeOf(kind: Int): CauseKind = CauseKind.entries[kind ushr FLOW_BITS]

    fun flowKindOf(kind: Int): FlowKind = FlowKind.entries[kind and ((1 shl FLOW_BITS) - 1)]

    inline fun filing(
        home: Long,
        flows: Int,
        causedBy: Long?,
        source: (Int) -> Long?,
        destination: (Int) -> Long?,
    ): Map<Long, IntArray> {
        val byTile = LinkedHashMap<Long, IntArrayList>()
        byTile[home] = IntArrayList(flows).also { for (i in 0 until flows) it.add(i) }
        for (i in 0 until flows) {
            val from = source(i)
            val to = destination(i)
            if (from != null && from != home) byTile.getOrPut(from) { IntArrayList(2) }.addOnce(i)
            if (to != null && to != home) byTile.getOrPut(to) { IntArrayList(2) }.addOnce(i)
        }
        if (causedBy != null && flows > 0 && causedBy !in byTile) byTile[causedBy] = IntArrayList(1).also { it.add(0) }
        val out = LinkedHashMap<Long, IntArray>(byTile.size)
        for ((tile, list) in byTile) out[tile] = list.toArray()
        return out
    }

    fun lotsByFlow(lots: List<FlowLot>, flows: Int): Array<ByteArray?> {
        val out = arrayOfNulls<ByteArray>(flows)
        if (lots.isEmpty()) return out
        for ((flow, group) in lots.groupBy { it.flowIndex }) {
            if (flow !in 0 until flows) continue
            val sink = ByteWriter(16)
            for ((_, lotId, quantity) in group) {
                sink.varint(lotId.raw)
                sink.signed(quantity.raw)
            }
            out[flow] = sink.toByteArray()
        }
        return out
    }

    fun readLots(flowIndex: Int, blob: ByteArray): List<FlowLot> {
        val cursor = SegmentReader(MemorySegment.ofArray(blob))
        val out = ArrayList<FlowLot>()
        while (cursor.at < blob.size) out += FlowLot(flowIndex, LotId(cursor.varint()), Quantity(cursor.signed()))
        return out
    }
}
