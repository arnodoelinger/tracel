package com.tracel.storage.ports.ledger

import com.tracel.model.lot.LotId
import com.tracel.model.rollback.RollbackJobId
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.util.eachRow
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.tracel.engine.rollback.lease.Leases as LeasesPort

/** The durable half of ownership. A lease survives the process dying. */
class Leases(private val storage: TracelStorage) : LeasesPort() {
    private class Held(val lots: LongOpenHashSet, val acquiredAt: Long, val legacy: Boolean)

    private var loaded = false
    private val jobs = Long2ObjectOpenHashMap<Held>()
    private val owners = Long2LongOpenHashMap().apply { defaultReturnValue(NOBODY) }

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
                val owner = owners.get(lotId.raw)
                if (owner != NOBODY && owner != job.raw) conflicts[lotId] = RollbackJobId(owner)
            }
            if (conflicts.isNotEmpty()) return@write conflicts

            val before = jobs[job.raw]
            val lots = LongOpenHashSet((before?.lots?.size ?: 0) + lotIds.size)
            before?.lots?.let(lots::addAll)
            for (lotId in lotIds) lots.add(lotId.raw)
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
        val lots = LongOpenHashSet(moving.lots.size + (before?.lots?.size ?: 0))
        before?.lots?.let(lots::addAll)
        lots.addAll(moving.lots)
        val now = System.currentTimeMillis()
        forget(from.raw, moving)
        swap(from.raw, null)
        put(Keys.leaseSet(to.raw), encode(now, lots))
        swap(to.raw, Held(lots, now, before?.legacy == true))
        moving.lots.toLongArray().mapTo(HashSet(), ::LotId)
    }

    override suspend fun reapAbandoned(nowMillis: Long, maxAgeMillis: Long): Set<RollbackJobId> = storage.write {
        load()
        val threshold = nowMillis - maxAgeMillis
        val stale = Long2ObjectOpenHashMap<Held>()
        for (entry in jobs.long2ObjectEntrySet()) if (entry.value.acquiredAt < threshold) stale.put(entry.longKey, entry.value)
        for (entry in stale.long2ObjectEntrySet()) {
            forget(entry.longKey, entry.value)
            swap(entry.longKey, null)
        }
        stale.keys.toLongArray().mapTo(HashSet(), ::RollbackJobId)
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
            adopt(Records.leaseJobId(value), LongOpenHashSet().apply { add(lot) }, Records.leaseAcquiredAt(value), legacy = true)
        }
        loaded = true
    }

    private fun adopt(job: Long, lots: LongOpenHashSet, acquiredAt: Long, legacy: Boolean) {
        val had = jobs[job]
        if (had != null) lots.addAll(had.lots)
        val held = Held(lots, minOf(acquiredAt, had?.acquiredAt ?: acquiredAt), legacy || had?.legacy == true)
        jobs.put(job, held)
        for (lot in lots.toLongArray()) owners.put(lot, job)
    }

    private fun StorageUnit.forget(job: Long, held: Held) {
        delete(Keys.leaseSet(job))
        if (!held.legacy) return
        for (lot in held.lots.toLongArray()) {
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
        if (from != null) for (lot in from.lots.toLongArray()) if (owners.get(lot) == job) owners.remove(lot)
        if (to == null) jobs.remove(job) else {
            jobs.put(job, to)
            for (lot in to.lots.toLongArray()) owners.put(lot, job)
        }
    }

    private companion object {
        const val NOBODY = Long.MIN_VALUE

        fun encode(acquiredAt: Long, lots: LongOpenHashSet): ByteArray {
            val out = ByteBuffer.allocate(8 + 8 * lots.size).order(ByteOrder.LITTLE_ENDIAN)
            out.putLong(acquiredAt)
            for (lot in lots.toLongArray()) out.putLong(lot)
            return out.array()
        }

        fun decode(value: MemorySegment): Pair<Long, LongOpenHashSet> {
            val layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
            val count = ((value.byteSize() - 8) / 8).toInt()
            val lots = LongOpenHashSet(count)
            for (i in 0 until count) lots.add(value.get(layout, 8L + 8L * i))
            return value.get(layout, 0L) to lots
        }
    }
}
