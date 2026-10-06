package com.tracel.storage.ports.ledger

import com.tracel.model.lot.LotId
import com.tracel.model.rollback.RollbackJobId
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.util.eachRow
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.tracel.engine.rollback.lease.Leases as LeasesPort

/** The durable half of ownership. A lease survives the process dying. */
class Leases(private val storage: TracelStorage) : LeasesPort() {
    private class Held(val lots: HashSet<Long>, val acquiredAt: Long, val legacy: Boolean)

    private var loaded = false
    private val jobs = HashMap<Long, Held>()
    private val owners = HashMap<Long, Long>()

    init {
        storage.afterReplace {
            jobs.clear()
            owners.clear()
            loaded = false
        }
    }

    override suspend fun tryReserve(job: RollbackJobId, lotIds: Set<LotId>): Map<LotId, RollbackJobId> =
        storage.write {
            load()
            val conflicts = HashMap<LotId, RollbackJobId>()
            for (lotId in lotIds) {
                val owner = owners[lotId.raw] ?: continue
                if (owner != job.raw) conflicts[lotId] = RollbackJobId(owner)
            }
            if (conflicts.isNotEmpty()) return@write conflicts

            val before = jobs[job.raw]
            val lots = HashSet<Long>((before?.lots?.size ?: 0) + lotIds.size)
            before?.lots?.let(lots::addAll)
            for (lotId in lotIds) lots += lotId.raw
            val now = System.currentTimeMillis()
            put(Keys.leaseSet(job.raw), encode(now, lots))
            swap(job.raw, Held(lots, now, before?.legacy == true))
            emptyMap()
        }

    override suspend fun release(job: RollbackJobId) {
        storage.write {
            load()
            val held = jobs[job.raw] ?: return@write
            forget(job.raw, held)
            swap(job.raw, null)
        }
    }

    override suspend fun transfer(from: RollbackJobId, to: RollbackJobId): Set<LotId> = storage.write {
        load()
        val moving = jobs[from.raw] ?: return@write emptySet()
        val before = jobs[to.raw]
        val lots = HashSet<Long>(moving.lots.size + (before?.lots?.size ?: 0))
        before?.lots?.let(lots::addAll)
        lots += moving.lots
        val now = System.currentTimeMillis()
        forget(from.raw, moving)
        swap(from.raw, null)
        put(Keys.leaseSet(to.raw), encode(now, lots))
        swap(to.raw, Held(lots, now, before?.legacy == true))
        moving.lots.mapTo(HashSet(), ::LotId)
    }

    override suspend fun reapAbandoned(nowMillis: Long, maxAgeMillis: Long): Set<RollbackJobId> = storage.write {
        load()
        val threshold = nowMillis - maxAgeMillis
        val stale = jobs.filterValues { it.acquiredAt < threshold }
        for ((job, held) in stale) {
            forget(job, held)
            swap(job, null)
        }
        stale.keys.mapTo(HashSet(), ::RollbackJobId)
    }

    private fun StorageUnit.load() {
        if (loaded) return
        eachRow(Keys.tagPrefix(Keys.LEASE_SET)) { cursor ->
            val (acquiredAt, lots) = decode(cursor.value())
            adopt(KeyReader.u64(cursor.key(), 1), lots, acquiredAt, legacy = false)
        }
        eachRow(Keys.tagPrefix(Keys.LEASE)) { cursor ->
            val value = cursor.value()
            val lot = KeyReader.u64(cursor.key(), 1)
            adopt(Records.leaseJobId(value), hashSetOf(lot), Records.leaseAcquiredAt(value), legacy = true)
        }
        loaded = true
    }

    private fun adopt(job: Long, lots: HashSet<Long>, acquiredAt: Long, legacy: Boolean) {
        val had = jobs[job]
        if (had != null) lots += had.lots
        val held = Held(lots, minOf(acquiredAt, had?.acquiredAt ?: acquiredAt), legacy || had?.legacy == true)
        jobs[job] = held
        for (lot in lots) owners[lot] = job
    }

    private fun StorageUnit.forget(job: Long, held: Held) {
        delete(Keys.leaseSet(job))
        if (!held.legacy) return
        for (lot in held.lots) {
            delete(Keys.lease(lot))
            delete(Keys.leaseJob(job, lot))
        }
    }

    private fun StorageUnit.swap(job: Long, next: Held?) {
        val previous = jobs[job]
        index(job, previous, next)
        afterAbort { index(job, next, previous) }
    }

    private fun index(job: Long, from: Held?, to: Held?) {
        from?.lots?.forEach { if (owners[it] == job) owners.remove(it) }
        if (to == null) jobs.remove(job) else {
            jobs[job] = to
            for (lot in to.lots) owners[lot] = job
        }
    }

    private companion object {
        fun encode(acquiredAt: Long, lots: Set<Long>): ByteArray {
            val out = ByteBuffer.allocate(8 + 8 * lots.size).order(ByteOrder.LITTLE_ENDIAN)
            out.putLong(acquiredAt)
            for (lot in lots) out.putLong(lot)
            return out.array()
        }

        fun decode(value: MemorySegment): Pair<Long, HashSet<Long>> {
            val layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
            val count = ((value.byteSize() - 8) / 8).toInt()
            val lots = HashSet<Long>(count * 2)
            for (i in 0 until count) lots += value.get(layout, 8L + 8L * i)
            return value.get(layout, 0L) to lots
        }
    }
}
