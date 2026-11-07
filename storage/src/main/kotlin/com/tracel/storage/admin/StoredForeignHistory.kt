package com.tracel.storage.admin

import com.tracel.engine.foreign.ForeignHistory
import com.tracel.engine.foreign.ForeignRecord
import com.tracel.engine.foreign.ImportMark
import com.tracel.engine.foreign.ImportRoom
import com.tracel.engine.foreign.NoRoomForImport
import com.tracel.engine.log.TransactionLog
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
import com.tracel.storage.chrono.Chrono
import com.tracel.storage.codec.Scalar
import com.tracel.storage.codec.Tag
import com.tracel.storage.codec.Txn
import com.tracel.storage.codec.recordBytes
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.putI64
import com.tracel.storage.history.StoredEventLog
import com.tracel.storage.history.StoredTransactionLog
import com.tracel.storage.history.StoredWorldLog
import java.lang.foreign.MemorySegment
import java.util.*

/** Handles history from other plugins, filed under this one's. */
class StoredForeignHistory(
    private val storage: TracelStorage,
    private val world: StoredWorldLog,
    private val transactions: StoredTransactionLog,
    private val events: StoredEventLog,
    private val counters: StoredCounters,
) : ForeignHistory {
    private val chrono = Chrono.of(storage)

    /** Makes sure there is room below our own history, and says how much of [source] is already in. */
    override suspend fun room(source: Long): ImportRoom = storage.write {
        val seqKey = Tag.COUNTER[StoredCounters.SEQ]
        val own = get(seqKey)?.let(Scalar::asLong)
        if (own != null && own < StoredCounters.SEQ_BASE) {
            if (chrono.firstMillisFrom(this, 0L) != null ||
                firstEpoch(Tag.TXN[0], { Txn.epochMillis[it] }) != null
            ) throw NoRoomForImport()
            putPinned(Tag.COUNTER[StoredCounters.IMPORT_SEQ], Scalar.long(maxOf(nextImportSeq(), own)))
            putPinned(seqKey, Scalar.long(StoredCounters.SEQ_BASE))
            afterCommit(counters::forget)
        }
        val ownSince = listOfNotNull(
            chrono.firstMillisFrom(this, StoredCounters.SEQ_BASE),
            firstEpoch(Tag.TXN[StoredCounters.SEQ_BASE], { Txn.epochMillis[it] }),
        ).minOrNull()
        ImportRoom(ownSince, get(Tag.IMPORT_MARK[source])?.let(::mark))
    }

    /**
     * Files [records] in the order given, and notes that [source] was read up to [rows]. Both land together.
     *
     * @return how many records were written
     */
    override suspend fun append(source: Long, rows: List<Long>, records: List<ForeignRecord>): Int = storage.batched {
        var next = storage.read { nextImportSeq() }
        val firstTxn =
            storage.read { get(Tag.COUNTER[StoredCounters.TXN])?.let(Scalar::asLong) ?: StoredCounters.first(StoredCounters.TXN) }
        var nextTxn = firstTxn
        var written = 0
        fun take(count: Int): Seq {
            check(next + count <= StoredCounters.SEQ_BASE) { "imported history ran out of sequence numbers." }
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
            val before = get(Tag.IMPORT_MARK[source])?.let(::mark)?.records ?: 0L
            putPinned(Tag.COUNTER[StoredCounters.IMPORT_SEQ], Scalar.long(next))

            // Above what the allocator has handed out, so it reads this back before it hands out more
            if (nextTxn != firstTxn) putPinned(Tag.COUNTER[StoredCounters.TXN], Scalar.long(nextTxn))
            put(Tag.IMPORT_MARK[source], recordBytes(Long.SIZE_BYTES * (rows.size + 1)) {
                putI64(0, before + written)
                rows.forEachIndexed { i, row -> putI64(Long.SIZE_BYTES * (i + 1L), row) }
            })
        }
        written
    }

    /** The sequence number below which everything imported sits. */
    override suspend fun importedBelow(): Long = storage.read {
        val own = get(Tag.COUNTER[StoredCounters.SEQ])?.let(Scalar::asLong) ?: StoredCounters.SEQ_BASE
        if (own >= StoredCounters.SEQ_BASE) StoredCounters.SEQ_BASE else 0L
    }

    /** Writes down what [entity] was, for an import that learned it. */
    override suspend fun noteKind(entity: UUID, kind: EntityTypeKey) {
        storage.write {
            val id = storage.interning.internHolder(this, HolderId.Entity(entity))
            put(Tag.ACTOR_KIND[id], Scalar.int(storage.interning.internEntityType(this, kind)))
        }
    }

    private fun StorageUnit.nextImportSeq(): Long =
        get(Tag.COUNTER[StoredCounters.IMPORT_SEQ])?.let(Scalar::asLong) ?: 1L

    private fun StorageUnit.firstEpoch(from: ByteArray, epoch: (MemorySegment) -> Long): Long? =
        scan(byteArrayOf(from[0]), from).use { cursor -> if (cursor.next()) epoch(cursor.value()) else null }

    private fun mark(value: MemorySegment): ImportMark = ImportMark(
        rows = (1 until (value.byteSize() / Long.SIZE_BYTES).toInt()).map { value.i64(Long.SIZE_BYTES * it.toLong()) },
        records = value.i64(0),
    )
}
