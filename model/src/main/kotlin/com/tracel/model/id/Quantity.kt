package com.tracel.model.id

/**
 * A positive count of units of one item key.
 *
 * Wrapping `Long` in its own type turns "quantity went negative somewhere" from
 * a silent bug into a crash at the exact call site that caused it — Kotlin's
 * `require` runs in the constructor, before the bad value can spread anywhere.
 */
@JvmInline
public value class Quantity(public val raw: Long) : Comparable<Quantity> {
    init {
        require(raw > 0) { "quantity must be positive, was $raw" }
    }

    override fun compareTo(other: Quantity): Int = raw.compareTo(other.raw)

    public operator fun plus(other: Quantity): Quantity = Quantity(raw + other.raw)

    /** Null if `other` is not strictly smaller — there would be nothing left. */
    public operator fun minus(other: Quantity): Quantity? =
        (raw - other.raw).takeIf { it > 0 }?.let(::Quantity)

    public companion object {
        public val ONE: Quantity = Quantity(1)
    }
}
