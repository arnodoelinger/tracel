package com.tracel.storage.codec.records

import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.putI32
import com.tracel.storage.ffm.Bytes.putI64
import java.lang.foreign.MemorySegment

object Job {
    fun lease(jobId: Long, acquiredAtMillis: Long): ByteArray =
        recordBytes(16) { putI64(0, jobId); putI64(8, acquiredAtMillis) }

    fun leaseJobId(v: MemorySegment): Long = v.i64(0)
    fun leaseAcquiredAt(v: MemorySegment): Long = v.i64(8)

    fun pending(itemKeyId: Int, delta: Long, jobId: Long, createdMillis: Long): ByteArray =
        recordBytes(28) { putI32(0, itemKeyId); putI64(4, delta); putI64(12, jobId); putI64(20, createdMillis) }

    fun pendingItemKeyId(v: MemorySegment): Int = v.i32(0)
    fun pendingDelta(v: MemorySegment): Long = v.i64(4)
    fun pendingJobId(v: MemorySegment): Long = v.i64(12)

    fun rbJob(restoreToHolderId: Int, stepCount: Int, createCount: Int, destroyCount: Int): ByteArray =
        recordBytes(16) { putI32(0, restoreToHolderId); putI32(4, stepCount); putI32(8, createCount); putI32(12, destroyCount) }

    fun rbJobRestoreTo(v: MemorySegment): Int = v.i32(0)
    fun rbJobStepCount(v: MemorySegment): Int = v.i32(4)
    fun rbJobCreateCount(v: MemorySegment): Int = v.i32(8)
    fun rbJobDestroyCount(v: MemorySegment): Int = v.i32(12)
}
