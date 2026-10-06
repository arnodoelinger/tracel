package com.tracel.storage.ports.ops

import com.tracel.engine.log.TransactionLog
import com.tracel.engine.foreign.NoRoomForImport
import com.tracel.engine.foreign.ImportRoom
import com.tracel.engine.foreign.ImportMark
import com.tracel.engine.foreign.ForeignRecord
import com.tracel.engine.foreign.ForeignHistory as ForeignHistoryPort
import com.tracel.engine.world.WorldLog
import com.tracel.model.event.ActorEvent
import com.tracel.model.holder.HolderId
import com.tracel.model.log.Seq
import com.tracel.model.transaction.Transaction
import com.tracel.model.transaction.TxnId
import com.tracel.model.world.WorldChange
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.codec.records.recordBytes
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.putI64
import com.tracel.storage.ports.event.EventLog
import java.lang.foreign.MemorySegment
import java.util.*

/** Handles history from other plugins, filed under this one's. */
class ForeignHistory(
    private val storage: TracelStorage,
    private val world: WorldLog,
    private val transactions: TransactionLog,
    private val events: EventLog,
    private val counters: Counters,
) : ForeignHistoryPort {
    /** Makes sure there is room below our own history, and says how much of [source] is already in. */
    override suspend fun room(source: Long): ImportRoom = storage.write {
        val seqKey = Keys.counter(Counters.SEQ)
        val own = get(seqKey)?.let(Records::asLong)
        if (own != null && own < Counters.SEQ_BASE) {
            if (firstEpoch(Keys.wchg(0), Records::wchgEpochMillis) != null ||
                firstEpoch(Keys.txn(0), Records::txnEpochMillis) != null
            ) throw NoRoomForImport()
            putPinned(Keys.counter(Counters.IMPORT_SEQ), Records.long(maxOf(nextImportSeq(), own)))
            putPinned(seqKey, Records.long(Counters.SEQ_BASE))
            afterCommit(counters::forget)
        }
        val ownSince = listOfNotNull(
            firstEpoch(Keys.wchg(Counters.SEQ_BASE), Records::wchgEpochMillis),
            firstEpoch(Keys.txn(Counters.SEQ_BASE), Records::txnEpochMillis),
        ).minOrNull()
        ImportRoom(ownSince, get(Keys.importMark(source))?.let(::mark))
    }

    /**
     * Files [records] in the order given, and notes that [source] was read up to [rows]. Both land together:
     * a crash leaves the mark at whatever really is in the store, so the next run takes it from there.
     *
     * @return how many records were written
     */
    override suspend fun append(source: Long, rows: List<Long>, records: List<ForeignRecord>): Int = storage.batched {
        var next = storage.read { nextImportSeq() }
        val firstTxn =
            storage.read { get(Keys.counter(Counters.TXN))?.let(Records::asLong) ?: Counters.first(Counters.TXN) }
        var nextTxn = firstTxn
        var written = 0
        fun take(count: Int): Seq {
            check(next + count <= Counters.SEQ_BASE) { "imported history ran out of sequence numbers" }
            return Seq(next).also { next += count }
        }
        for (record in records) when (record) {
            is ForeignRecord.Blocks -> written += world.appendAll(record.edits) { take(it) }
            is ForeignRecord.Change -> {
                world.append(
                    WorldChange(
                        take(1),
                        record.action,
                        record.cause,
                        record.causedBy,
                        record.epochMillis,
                        record.at,
                        record.subject
                    )
                )
                written++
            }

            is ForeignRecord.Moved -> {
                transactions.append(
                    Transaction(
                        TxnId(nextTxn++),
                        take(1),
                        record.epochMillis,
                        record.cause,
                        record.causedBy,
                        record.flows,
                        at = record.at
                    )
                )
                written++
            }

            is ForeignRecord.Happened -> {
                events.append(ActorEvent(take(1), record.kind, record.by, record.epochMillis, record.at, record.text))
                written++
            }
        }
        storage.write {
            val before = get(Keys.importMark(source))?.let(::mark)?.records ?: 0L
            putPinned(Keys.counter(Counters.IMPORT_SEQ), Records.long(next))

            // Above what the allocator has handed out, so it reads this back before it hands out more
            if (nextTxn != firstTxn) putPinned(Keys.counter(Counters.TXN), Records.long(nextTxn))
            put(Keys.importMark(source), recordBytes(Long.SIZE_BYTES * (rows.size + 1)) {
                putI64(0, before + written)
                rows.forEachIndexed { i, row -> putI64(Long.SIZE_BYTES * (i + 1L), row) }
            })
        }
        written
    }

    /**
     * The sequence number below which every record was imported, or `0` if nothing here could have been.
     *
     * Imported history is read and never rolled back because of fundamental differences between other plugins
     * and `Tracel`. For example, `CoreProtect` does not know about lots and it doesn't have ledger transactions, so
     * it's impossible to know what to leave alone when rolling back a block change.
     */
    override suspend fun importedBelow(): Long = storage.read {
        val own = get(Keys.counter(Counters.SEQ))?.let(Records::asLong) ?: Counters.SEQ_BASE
        if (own >= Counters.SEQ_BASE) Counters.SEQ_BASE else 0L
    }

    /** Says what kind of mob [entity] is, for an actor that is known by its type alone. */
    override suspend fun noteKind(entity: UUID, kind: EntityTypeKey) {
        storage.write {
            val id = storage.interning.internHolder(this, HolderId.Entity(entity))
            put(Keys.actorKind(id), Records.int(storage.interning.internEntityType(this, kind)))
        }
    }

    private fun StorageUnit.nextImportSeq(): Long =
        get(Keys.counter(Counters.IMPORT_SEQ))?.let(Records::asLong) ?: 1L

    private fun StorageUnit.firstEpoch(from: ByteArray, epoch: (MemorySegment) -> Long): Long? =
        scan(Keys.tagPrefix(from[0]), from).use { cursor -> if (cursor.next()) epoch(cursor.value()) else null }

    private fun mark(value: MemorySegment): ImportMark = ImportMark(
        rows = (1 until (value.byteSize() / Long.SIZE_BYTES).toInt()).map { value.i64(Long.SIZE_BYTES * it.toLong()) },
        records = value.i64(0),
    )
}
