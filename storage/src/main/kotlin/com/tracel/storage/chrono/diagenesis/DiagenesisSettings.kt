package com.tracel.storage.chrono.diagenesis

/** How the collector weighs its work. */
data class DiagenesisSettings(
    val lingerMillis: Long = 20 * 60_000L,
    val hardAgeMillis: Long = 60 * 60_000L,
    val hardFragments: Int = 64,
    val payoff: Double = 8.0,
    val tier: Int = 2,
    val burstHoldMillis: Long = 60_000L,
    val tilesPerPass: Int = 256,
    val sweepGroups: Int = 128,
    val glacierAfterWindows: Int = 7,
    val glacierSpanWindows: Int = 7,
    val permafrostAfterWindows: Int = 35,
    val permafrostSpanWindows: Int = 28,
    val dustBytes: Int = 2048,
)
