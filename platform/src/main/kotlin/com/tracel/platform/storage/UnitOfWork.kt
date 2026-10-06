package com.tracel.platform.storage

/**
 * One durable unit of storage work: everything inside [atomically] lands together, or nothing
 * inside it lands at all.
 */
public interface UnitOfWork {
    /** Runs [block] inside a unit of work, committing it all at once. */
    public suspend fun <T> atomically(block: suspend () -> T): T

    /** One consistent snapshot, no commit. */
    public suspend fun <T> reading(block: suspend () -> T): T = block()

    /** A point inside the open unit of work that [rollbackTo] can return to. */
    public suspend fun mark(): Int = 0

    /** Forgets [mark], keeping what was written since. */
    public suspend fun release(mark: Int) {}

    /** Drops what was written since [mark], and the mark with it. */
    public suspend fun rollbackTo(mark: Int) {}
}

/** Direct unit of work. */
public object DirectUnitOfWork : UnitOfWork {
    override suspend fun <T> atomically(block: suspend () -> T): T = block()
}
