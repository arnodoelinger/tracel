package com.tracel.model.id

/** Identifies one transaction in the append-only log. */
@JvmInline
public value class TxnId(public val raw: Long)
