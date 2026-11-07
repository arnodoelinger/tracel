package com.tracel.storage.chrono.capsule

import com.tracel.storage.chrono.bits.SegmentReader
import java.lang.foreign.MemorySegment

/** The two climbing columns every capsule has: sequence, and wall-clock time, both stored as gaps. */
internal class Timeline(val seq: Parts.Gaps, val millisFirst: Long, val millis: Parts.Gaps) {
    companion object {
        fun read(seg: MemorySegment, cursor: SegmentReader, rows: Int): Timeline {
            val seq = Parts.readGaps(cursor, rows)
            val millisFirst = cursor.varint()
            val millis = Parts.readGaps(cursor, rows)
            return Timeline(seq, millisFirst, millis)
        }
    }
}
