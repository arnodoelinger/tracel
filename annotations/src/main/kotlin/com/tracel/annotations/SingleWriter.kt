package com.tracel.annotations

/**
 * In-memory port: one thread may mutate. Public methods that write must call
 * `writer.checkIn()`. Public methods that only read are marked [Reads].
 *
 * Enforced by `Konsist` on classes named `InMemory*`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class SingleWriter
