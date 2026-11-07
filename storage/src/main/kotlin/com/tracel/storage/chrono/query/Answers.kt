package com.tracel.storage.chrono.query

import com.tracel.storage.chrono.Rows
import com.tracel.storage.chrono.Tile

/** Changes found in one tile, as rows, in ascending sequence. */
class Found(val tile: Tile, val rows: Rows)

/**
 * What one tile looked like before a moment and what it looks like now, cell by cell;
 * only the cells that changed since, everything else stood still.
 */
class Rewound(
    val tile: Tile,
    val size: Int,
    val cells: IntArray,
    val then: IntArray,
    val thenExtras: Array<ByteArray?>,
    val now: IntArray,
    val nowExtras: Array<ByteArray?>,
)

/**
 * Changes that did not start where the change before them left the cell:
 * something touched it without the log seeing.
 */
class Gaps(
    val tile: Tile,
    val size: Int,
    val seq: LongArray,
    val millis: LongArray,
    val cells: IntArray,
    val expected: IntArray,
    val found: IntArray,
)
