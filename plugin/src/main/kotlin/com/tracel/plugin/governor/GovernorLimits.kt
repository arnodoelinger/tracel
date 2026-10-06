package com.tracel.plugin.governor

/** Calls to [Throttle.spent] per clock read. A power of two. */
internal const val CLOCK_EVERY = 32

/** A server tick, in milliseconds. */
internal const val TICK_MILLIS = 50.0

/** How much of what is left of a tick one restore may take. */
internal const val HEADROOM_SHARE = 0.8

/** Without a reading, take what a quiet region would give. */
internal const val BLIND_MILLIS = 5.0

internal const val NANOS_PER_MILLI = 1_000_000.0

/** A slice of work asked for with less than this left of the turn is given this much, so it always gets something done. */
internal const val MIN_SLICE_NANOS = 1_000_000L
