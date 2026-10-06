package com.tracel.plugin.status.rollback

/** How big a rollback is, by how many records it takes back. */
enum class RollbackSize(val key: String, private val below: Long) {
    TINY("tiny", 1_024),
    SMALL("small", 10_240),
    MEDIUM("medium", 102_400),
    LARGE("large", 1_024_000),
    HUGE("huge", 8_192_000);

    companion object {
        fun of(records: Long): RollbackSize = entries.firstOrNull { records < it.below } ?: HUGE
    }
}
