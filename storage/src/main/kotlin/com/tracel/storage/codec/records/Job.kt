package com.tracel.storage.codec.records

import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.i8
import com.tracel.storage.ffm.Bytes.putI32
import com.tracel.storage.ffm.Bytes.putI64
import com.tracel.storage.ffm.Bytes.putI8
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

    fun rbJob(
        restoreToHolderId: Int,
        stepCount: Int,
        createCount: Int,
        destroyCount: Int,
        targetTimeMillis: Long,
        hasTargetTime: Boolean,
        executedAtMillis: Long,
    ): ByteArray =
        recordBytes(33) {
            putI32(0, restoreToHolderId)
            putI32(4, stepCount)
            putI32(8, createCount)
            putI32(12, destroyCount)
            putI64(16, targetTimeMillis)
            putI8(24, if (hasTargetTime) 1 else 0)
            putI64(25, executedAtMillis)
        }

    fun rbJobRestoreTo(v: MemorySegment): Int = v.i32(0)
    fun rbJobStepCount(v: MemorySegment): Int = v.i32(4)
    fun rbJobCreateCount(v: MemorySegment): Int = v.i32(8)
    fun rbJobDestroyCount(v: MemorySegment): Int = v.i32(12)

    /**
     * @return the target time, or `null` for jobs without one.
     *
     * The size check keeps this compatible with older records.
     */
    fun rbJobTargetTime(v: MemorySegment): Long? = if (v.byteSize() < 33 || v.i8(24) == 0.toByte()) null else v.i64(16)

    /**
     * @return when the rollback was executed.
     *
     * Older records without this field return `0`.
     */
    fun rbJobExecutedAt(v: MemorySegment): Long = if (v.byteSize() < 33) 0L else v.i64(25)
}
