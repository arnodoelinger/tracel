package com.tracel.annotations

/**
 * Marks a rollback step that must survive the server dying halfway through it.
 *
 * Generated around the annotated function:
 *  - A journal row written before and after the step
 *  - A skip when the journal already records the step as done, so a resumed job
 *    never applies a step twice
 *  - Registration of a crash-injection point
 *
 * The phase follows from the step type, and idempotency is not an option:
 * a journal step that cannot be replayed safely is a bug.
 *
 * Note that structured concurrency does not make a step recoverable. Coroutines
 * cancel and propagate failures inside one process; they do not survive the JVM
 * dying. That is exactly the gap this annotation fills.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Journaled
