package com.tracel.annotations

/**
 * The thread a piece of `Tracel` code is allowed to run on.
 */
public enum class ThreadContext {
    /** Owns the chunks around a location. Obtained via `RegionScheduler`. */
    REGION,

    /** Follows an entity across regions and teleports. Obtained via `Entity#getScheduler`. */
    ENTITY,

    /** Server-global state. Owns no entity's region. */
    GLOBAL,

    /** Off-thread work: queries, planning, compaction, retention. */
    ASYNC,

    /** The single database writer. */
    STORAGE,
}

/** Thread this type or function may run on. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class RunsOn(val value: ThreadContext)
