package com.tracel.storage.util

import com.tracel.storage.StorageUnit
import com.tracel.storage.spi.EngineCursor

/** Walking a key family. */
internal inline fun StorageUnit.eachRow(prefix: ByteArray, body: (EngineCursor) -> Unit) {
    scan(prefix).use { cursor ->
        while (cursor.next()) body(cursor)
    }
}

/** Walking a key family, starting at [from]. */
internal inline fun StorageUnit.eachRow(prefix: ByteArray, from: ByteArray, body: (EngineCursor) -> Unit) {
    scan(prefix, from).use { cursor ->
        while (cursor.next()) body(cursor)
    }
}
