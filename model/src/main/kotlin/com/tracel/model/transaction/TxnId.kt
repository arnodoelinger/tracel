package com.tracel.model.transaction

/** Identifies one transaction in the append-only log. */
@JvmInline
public value class TxnId(public val raw: Long)
