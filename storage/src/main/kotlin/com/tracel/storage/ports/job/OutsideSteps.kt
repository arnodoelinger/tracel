package com.tracel.storage.ports.job

import com.tracel.engine.rollback.outside.OutsideStep
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Keys
import com.tracel.storage.util.eachRow
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import com.tracel.engine.rollback.outside.OutsideSteps as OutsideStepsPort

/**
 * One row per step, keyed by its sequence number.
 *
 * Steps are few, only what addons mark as reversible, so finding one by
 * job or kind scans them all.
 */
class OutsideSteps(private val storage: TracelStorage) : OutsideStepsPort {
    override suspend fun stage(steps: List<OutsideStep>) {
        if (steps.isEmpty()) return
        storage.write {
            for (step in steps) {
                val key = Keys.outsideStep(step.seq)
                if (get(key) == null) put(key, encode(step))
            }
        }
    }

    override suspend fun bind(job: Long, seqs: LongArray) {
        if (seqs.isEmpty()) return
        storage.write {
            for (seq in seqs) {
                val key = Keys.outsideStep(seq)
                val kept = get(key)?.let { decode(seq, it) } ?: continue
                put(key, encode(kept.copy(job = job)))
            }
        }
    }

    override suspend fun ofJob(job: Long): List<OutsideStep> = all().filter { it.job == job }

    override suspend fun ofKind(kind: String): List<OutsideStep> = all().filter { it.kind == kind }

    override suspend fun told(seq: Long, undone: Boolean, generation: Int) {
        storage.write {
            val key = Keys.outsideStep(seq)
            val kept = get(key)?.let { decode(seq, it) } ?: return@write
            if (generation <= kept.generation) return@write
            put(key, encode(kept.copy(undone = undone, generation = generation)))
        }
    }

    private suspend fun all(): List<OutsideStep> = storage.read {
        val out = ArrayList<OutsideStep>()
        eachRow(Keys.outsideStepPrefix()) { cursor -> out += decode(cursor.keyU64(1), cursor.value()) }
        out
    }

    private companion object {
        const val NO_JOB = -1L

        fun encode(step: OutsideStep): ByteArray {
            val kind = step.kind.encodeToByteArray()
            val text = step.text.encodeToByteArray()
            return ByteBuffer.allocate(4 + kind.size + 4 + text.size + 1 + 4 + 8)
                .putInt(kind.size).put(kind)
                .putInt(text.size).put(text)
                .put(if (step.undone) 1 else 0)
                .putInt(step.generation)
                .putLong(step.job ?: NO_JOB)
                .array()
        }

        fun decode(seq: Long, value: MemorySegment): OutsideStep = decode(seq, value.toArray(ValueLayout.JAVA_BYTE))

        fun decode(seq: Long, value: ByteArray): OutsideStep {
            val buffer = ByteBuffer.wrap(value)
            val kind = ByteArray(buffer.getInt()).also(buffer::get).decodeToString()
            val text = ByteArray(buffer.getInt()).also(buffer::get).decodeToString()
            val undone = buffer.get() == 1.toByte()
            val generation = buffer.getInt()
            val job = buffer.getLong().takeIf { it != NO_JOB }
            return OutsideStep(seq, kind, text, undone, generation, job)
        }
    }
}
