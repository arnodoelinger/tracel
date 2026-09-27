package com.tracel.platform.storage

/**
 * One durable unit of storage work: everything inside [atomically] lands together, or nothing
 * inside it lands at all.
 */
public interface UnitOfWork {
    public suspend fun <T> atomically(block: suspend () -> T): T

    /** One consistent snapshot, no commit. */
    public suspend fun <T> reading(block: suspend () -> T): T = block()
}

/** Direct unit of work. */
public object DirectUnitOfWork : UnitOfWork {
    override suspend fun <T> atomically(block: suspend () -> T): T = block()
}
