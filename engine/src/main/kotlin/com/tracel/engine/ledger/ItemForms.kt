package com.tracel.engine.ledger

import com.tracel.engine.store.KeyedTable
import com.tracel.model.item.ContentHash

/**
 * The bytes behind a [ContentHash]: what a decorated item actually is.
 *
 * A hash written once keeps its bytes.
 */
public typealias ItemForms = KeyedTable<ContentHash, ByteArray>
