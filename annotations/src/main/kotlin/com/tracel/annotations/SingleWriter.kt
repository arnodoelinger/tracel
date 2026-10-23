package com.tracel.annotations

/**
 * In-memory port: one thread may mutate.
 *
 * Enforced by `Konsist` on classes named `InMemory*`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class SingleWriter
