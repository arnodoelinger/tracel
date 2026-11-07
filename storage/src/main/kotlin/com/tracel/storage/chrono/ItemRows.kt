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

    fun kindOf(cause: CauseKind, flow: FlowKind): Int = (cause.ordinal shl FLOW_BITS) or flow.ordinal

    fun causeOf(kind: Int): CauseKind = CauseKind.entries[kind ushr FLOW_BITS]

    fun flowKindOf(kind: Int): FlowKind = FlowKind.entries[kind and ((1 shl FLOW_BITS) - 1)]

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
