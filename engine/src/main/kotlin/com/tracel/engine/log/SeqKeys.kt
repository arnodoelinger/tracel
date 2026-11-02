package com.tracel.engine.log

/** A key that sorts newest first: bigger sequence numbers get smaller keys. */
internal fun descKey(seq: Long): Long = Long.MAX_VALUE - seq
