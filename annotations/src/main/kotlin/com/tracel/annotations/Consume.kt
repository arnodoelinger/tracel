package com.tracel.annotations

/**
 * FIFO consume: oldest [Fifo] entry first, split only the last lot touched.
 *
 * Marks `takeFifo` / `drainFifo` / `withdraw` / `drain` so property tests can
 * find every implementation (in-memory and disk). The loop is not generated.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Consume
