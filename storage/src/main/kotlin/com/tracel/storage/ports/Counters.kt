package com.tracel.storage.ports

import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Durable ID allocation, a block at a time.
 *
 * A restart must never hand out an id a previous session already used, and a write to disk per
 * id would put an `fsync` in the middle of every lot creation. So the counter on disk always
 * points [blockSize] IDs past what has been handed out: a crash forfeits the rest of the
 * block and costs nothing but a gap in the numbering, which nothing anywhere cares about.
 */
class Counters(private val storage: TracelStorage, private val blockSize: Long = DEFAULT_BLOCK_SIZE) {
    private class Reservation(var next: Long, var exhaustedAt: Long)

    private val lock = Mutex()
    private val reserved = HashMap<Int, Reservation>()

    suspend fun nextTxnId(): TxnId = TxnId(next(TXN))

    suspend fun nextSeq(): Seq = Seq(next(SEQ))

    suspend fun nextLotId(): LotId = LotId(next(LOT))

    suspend fun nextFifoSeq(): Seq = Seq(next(PLACEMENT))

    suspend fun nextRollbackJobId(): RollbackJobId = RollbackJobId(next(ROLLBACK_JOB))

    suspend fun nextPendingDeliveryId(): Long = next(PENDING_DELIVERY)

    suspend fun forget() {
        lock.withLock { reserved.clear() }
    }

    private suspend fun next(name: Int): Long = lock.withLock {
        val reservation = reserved[name]
        if (reservation != null && reservation.next < reservation.exhaustedAt) {
            return@withLock reservation.next++
        }
        val start = reserve(name)
        reserved[name] = Reservation(start + 1, start + blockSize)
        start
    }

    private suspend fun reserve(name: Int): Long = storage.write {
        val key = Keys.counter(name)
        val value = get(key)?.let(Records::asLong) ?: 1L
        put(key, Records.long(value + blockSize))
        value
    }

    companion object {
        const val LOT = 1
        const val PLACEMENT = 2
        const val TXN = 3
        const val SEQ = 4
        const val ROLLBACK_JOB = 5
        const val PENDING_DELIVERY = 6
        const val DEFAULT_BLOCK_SIZE = 256L
    }
}
