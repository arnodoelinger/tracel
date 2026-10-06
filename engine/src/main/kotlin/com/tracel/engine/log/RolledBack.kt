package com.tracel.engine.log

/** Which log records a rollback took back, and when. */
public interface RolledBack {
    /** Notes that job [job] took back [seqs] at [millis]. */
    public suspend fun mark(job: Long, seqs: LongArray, millis: Long)

    /** Forgets what job [job] took back: its rollback was undone. */
    public suspend fun restore(job: Long)

    /** When each of [seqs] was taken back, for those that were. */
    public suspend fun of(seqs: Collection<Long>): Map<Long, Long>
}
