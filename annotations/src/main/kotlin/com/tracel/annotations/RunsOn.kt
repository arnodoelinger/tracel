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

    /** The single `SQLite` writer. */
    STORAGE,
}

/**
 * Declares which thread a function may execute on.
 *
 * This cannot be expressed by a type or a function, because the property being
 * checked is about the call graph: an [ThreadContext.ASYNC] function must never
 * reach a [ThreadContext.REGION] one without going through a scheduler.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class RunsOn(val value: ThreadContext)
