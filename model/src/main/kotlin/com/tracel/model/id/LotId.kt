package com.tracel.model.id

/**
 * Identifies one lot: an immutable batch of units created by a single
 * transaction. A lot's identity never changes, even after it moves between
 * holders or gets split — only its placement (which holder, how much
 * remains) changes.
 */
@JvmInline
public value class LotId(public val raw: Long)
