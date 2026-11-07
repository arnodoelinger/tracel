package com.tracel.storage.chrono.diagenesis

/**
 * Chronochunk generations, from new fragments to old history.
 *
 * Each generation is a different kind of capsule, with different size and age limits.
 */
enum class Generation {
    /** New fragments, still waiting to be merged. */
    NEW,

    /** Some fragments have been merged into a capsule, but the window is still open. */
    SILT,

    /** A closed window, pressed into as few capsules as fit. */
    STRATUM,

    /** Old windows pressed together, a span of days to a capsule. */
    GLACIER,

    /** Glaciers pressed together again, months to a capsule. */
    PERMAFROST,
}
