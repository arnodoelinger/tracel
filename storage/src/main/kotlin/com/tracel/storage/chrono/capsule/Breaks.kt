package com.tracel.storage.chrono.capsule

/**
 * Rows whose before is not the after of the change ahead of them,
 * and what that before really was.
 */
class Breaks(val rows: IntArray, val states: IntArray) {
    val size: Int get() = rows.size
}
