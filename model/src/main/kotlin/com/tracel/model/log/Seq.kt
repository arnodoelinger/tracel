package com.tracel.model.log

/**
 * Position of a transaction in the append-only log.
 *
 * Assigned on the region thread at capture time, so transactions touching the
 * same account are always totally ordered.
 */
@JvmInline
public value class Seq(public val raw: Long) : Comparable<Seq> {
    override fun compareTo(other: Seq): Int = raw.compareTo(other.raw)

    /** Increments the sequence number. */
    public operator fun inc(): Seq = Seq(raw + 1)
}
