package com.tracel.storage.ports.event

import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.model.event.ActorEvent
import com.tracel.model.event.EventKind
import com.tracel.model.log.Seq
import com.tracel.model.world.BlockPos
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.records.recordBytes
import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.i8
import com.tracel.storage.ffm.Bytes.putI32
import com.tracel.storage.ffm.Bytes.putI64
import com.tracel.storage.ffm.Bytes.putI8
import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.ffm.Bytes.writeBytes
import com.tracel.storage.intern.Interning
import java.lang.foreign.MemorySegment

/** The event log: what was said, what was typed, who joined and disconnected. */
class EventLog(private val storage: TracelStorage) {
    private val interning: Interning get() = storage.interning

    /** Appends [event]. The log is append-only, so a sequence number that is taken is an error. */
    suspend fun append(event: ActorEvent) {
        storage.write {
            val seq = event.seq.raw
            check(get(Keys.event(seq)) == null) { "event at ${event.seq} already appended — the log is append-only" }
            val byId = event.by?.let { interning.internHolder(this, it) } ?: 0
            val at = event.at
            val worldId = at?.let { interning.internWorld(this, it.world) } ?: 0
            val text = event.text?.toByteArray(Charsets.UTF_8) ?: NO_TEXT
            put(Keys.event(seq), recordBytes(HEADER_BYTES + text.size) {
                putI8(KIND, event.kind.ordinal.toByte())
                putI32(BY, byId)
                putI32(WORLD, worldId)
                putI32(X, at?.x ?: 0)
                putI32(Y, at?.y ?: 0)
                putI32(Z, at?.z ?: 0)
                putI64(EPOCH, event.epochMillis)
                writeBytes(HEADER_BYTES.toLong(), text)
            })
            if (byId != 0) put(Keys.eventActor(byId, event.epochMillis, seq), NONE)
            put(Keys.eventTime(event.epochMillis, seq), NONE)
        }
    }

    /**
     * The events of [kinds] that [filter] lets through, newest first. Only who, when and where are asked of the
     * filter: an event is not a block or an item, and a filter that names one matches no event.
     */
    suspend fun query(filter: LookupFilter, kinds: Set<EventKind>): List<ActorEvent> = storage.read {
        if (kinds.isEmpty() || filter.material != null || filter.blockMaterials.isNotEmpty()) return@read emptyList()
        val holderIds = filter.holders.map { interning.findHolderId(this, it) ?: return@map null }
        if (filter.holders.isNotEmpty() && holderIds.all { it == null }) return@read emptyList()
        val excluded = filter.excludedHolders.mapNotNullTo(HashSet()) { interning.findHolderId(this, it) }
        val worldId =
            (filter.region?.world ?: filter.world)?.let { interning.findWorldId(this, it) ?: return@read emptyList() }
        val until = filter.until ?: Long.MAX_VALUE
        val want = filter.offset.toLong() + filter.limit

        fun accepts(record: MemorySegment): Boolean {
            if (EventKind.entries[record.i8(KIND).toInt()] !in kinds) return false
            if (record.i32(BY) in excluded) return false
            if (worldId == null) return true
            if (record.i32(WORLD) != worldId) return false
            val region = filter.region ?: return true
            return region.containsBlock(record.i32(X), record.i32(Y), record.i32(Z))
        }

        val found = ArrayList<ActorEvent>()
        fun walk(prefix: ByteArray, from: ByteArray, millisAt: Int) {
            var taken = 0L
            scan(prefix, from).use { cursor ->
                while (taken < want && cursor.next()) {
                    if (Keys.invert(cursor.keyU64(millisAt)) < (filter.since ?: Long.MIN_VALUE)) break
                    val seq = Keys.invert(cursor.keyU64(millisAt + 8))
                    val record = get(Keys.event(seq)) ?: continue
                    if (!accepts(record)) continue
                    found += decode(this, seq, record)
                    taken++
                }
            }
        }

        if (filter.holders.isEmpty()) {
            walk(Keys.tagPrefix(Keys.EVENT_TIME), Keys.eventTimeFrom(until), 1)
        } else {
            for (id in holderIds.filterNotNull()) walk(Keys.eventActorPrefix(id), Keys.eventActorFrom(id, until), 5)
            found.sortWith(compareByDescending<ActorEvent> { it.epochMillis }.thenByDescending { it.seq })
        }
        found.drop(filter.offset).take(filter.limit)
    }

    private fun decode(unit: StorageUnit, seq: Long, record: MemorySegment): ActorEvent {
        val byId = record.i32(BY)
        val worldId = record.i32(WORLD)
        val length = (record.byteSize() - HEADER_BYTES).toInt()
        return ActorEvent(
            seq = Seq(seq),
            kind = EventKind.entries[record.i8(KIND).toInt()],
            by = if (byId == 0) null else interning.resolveHolder(unit, byId),
            epochMillis = record.i64(EPOCH),
            at = if (worldId == 0) null else BlockPos(
                interning.resolveWorld(unit, worldId),
                record.i32(X),
                record.i32(Y),
                record.i32(Z)
            ),
            text = if (length == 0) null else record.readBytes(HEADER_BYTES.toLong(), length).toString(Charsets.UTF_8),
        )
    }

    companion object {
        private val NONE = ByteArray(0)
        private val NO_TEXT = ByteArray(0)

        private const val KIND = 0L
        private const val BY = 1L
        private const val WORLD = 5L
        private const val X = 9L
        private const val Y = 13L
        private const val Z = 17L
        private const val EPOCH = 21L
        private const val HEADER_BYTES = 29

        fun epochMillis(record: MemorySegment): Long = record.i64(EPOCH)

        fun worldId(record: MemorySegment): Int = record.i32(WORLD)

        fun byId(record: MemorySegment): Int = record.i32(BY)
    }
}
