package com.tracel.storage.ports.ops

import com.tracel.model.log.Seq
import com.tracel.model.lot.LotId
import com.tracel.model.rollback.RollbackJobId
import com.tracel.model.transaction.TxnId
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.spi.MutationBatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import com.tracel.engine.store.Counters as CountersPort

/** Durable ID allocation, a block at a time. */
class Counters(private val storage: TracelStorage, private val blockSize: Long = DEFAULT_BLOCK_SIZE) : CountersPort {
    private class Reservation(var next: Long, var exhaustedAt: Long)

    private val lock = ReentrantLock()
    private val reserved = HashMap<Int, Reservation>()

    private val issued = AtomicLong()

    override val seqIssued: Long get() = issued.get()

    init {
        storage.afterReplace(::forget)
    }

    /** @return a new transaction ID. */
    override suspend fun nextTxnId(): TxnId = TxnId(next(TXN))

    /** @return a new sequence number. */
    override suspend fun nextSeq(): Seq = Seq(next(SEQ)).also { issued.incrementAndGet() }

    /** @return a new range of sequence numbers. */
    override suspend fun nextSeqRange(count: Int): Seq {
        require(count > 0) { "a range of $count sequences is not a range" }
        if (count == 1) return nextSeq()
        return Seq(nextRange(SEQ, count)).also { issued.addAndGet(count.toLong()) }
    }

    /** @return a new lot ID. */
    suspend fun nextLotId(): LotId = LotId(next(LOT))

    /** @return a new FIFO sequence number. */
    suspend fun nextFifoSeq(): Seq = Seq(next(PLACEMENT))

    /** @return a new rollback job ID. */
    override suspend fun nextRollbackJobId(): RollbackJobId = RollbackJobId(next(ROLLBACK_JOB))

    /** @return a new pending delivery ID. */
    suspend fun nextPendingDeliveryId(): Long = next(PENDING_DELIVERY)

    /** @return a new lot ID. */
    fun nextLotIdOn(unit: StorageUnit): LotId = LotId(nextOn(unit, LOT))

    /** @return a new FIFO sequence number, inside [unit]. */
    fun nextFifoSeqOn(unit: StorageUnit): Long = nextOn(unit, PLACEMENT)

    /** @return a new pack ID, inside [unit]. */
    fun nextPackIdOn(unit: StorageUnit): Long = nextOn(unit, PACK)

    /** @return a new sequence number. */
    override suspend fun peekTxnId(): Long {
        lock.lock()
        val cached = try {
            reserved[TXN]?.takeIf { it.next < it.exhaustedAt }?.next
        } finally {
            lock.unlock()
        }
        if (cached != null) return cached
        return storage.read {
            get(Keys.counter(TXN))?.let(Records::asLong) ?: 1L
        }
    }

    /** Drops the blocks held in memory: the store they were reserved from was replaced. */
    fun forget() {
        lock.withLock { reserved.clear() }
    }

    private suspend fun next(name: Int): Long {
        lock.lock()
        try {
            val reservation = reserved[name]
            if (reservation != null && reservation.next < reservation.exhaustedAt) return reservation.next++
        } finally {
            lock.unlock()
        }

        val start = reserve(name)

        lock.lock()
        try {
            val reservation = reserved[name]
            if (reservation != null && reservation.next < reservation.exhaustedAt) return reservation.next++
            reserved[name] = Reservation(start + 1, start + blockSize)
        } finally {
            lock.unlock()
        }
        return start
    }

    private suspend fun nextRange(name: Int, count: Int): Long = storage.write {
        val key = Keys.counter(name)
        val value = get(key)?.let(Records::asLong) ?: first(name)
        putPinned(key, Records.long(value + count))
        keepSpent(this, key, value + count)
        value
    }

    private fun nextOn(unit: StorageUnit, name: Int): Long = lock.withLock {
        val reservation = reserved[name]
        if (reservation != null && reservation.next < reservation.exhaustedAt) return reservation.next++
        val key = Keys.counter(name)
        val start = unit.get(key)?.let(Records::asLong) ?: first(name)
        unit.putPinned(key, Records.long(start + blockSize))
        keepSpent(unit, key, start + blockSize)
        reserved[name] = Reservation(start + 1, start + blockSize)
        start
    }

    private suspend fun reserve(name: Int): Long = storage.write {
        val key = Keys.counter(name)
        val value = get(key)?.let(Records::asLong) ?: first(name)
        putPinned(key, Records.long(value + blockSize))
        keepSpent(this, key, value + blockSize)
        value
    }

    private fun keepSpent(unit: StorageUnit, key: ByteArray, until: Long) {
        unit.afterAbort {
            val durable = storage.engine.snapshot().use { it.get(key)?.let(Records::asLong) } ?: 0L
            if (durable < until) storage.engine.write(
                MutationBatch().apply { put(key, Records.long(until)) },
                durable = true
            )
        }
    }

    companion object {
        const val LOT = 1
        const val PLACEMENT = 2
        const val TXN = 3
        const val SEQ = 4
        const val ROLLBACK_JOB = 5
        const val PENDING_DELIVERY = 6
        const val PACK = 7
        const val IMPORT_SEQ = 8
        const val DEFAULT_BLOCK_SIZE = 256L
        const val SEQ_BASE = 1L shl 40

        fun first(name: Int): Long = if (name == SEQ) SEQ_BASE else 1L
    }
}
