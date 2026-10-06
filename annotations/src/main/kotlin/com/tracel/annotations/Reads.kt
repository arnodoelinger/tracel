package com.tracel.annotations

/**
 * This public method on a [SingleWriter] type does not mutate.
 *
 * It must not call `checkIn()`.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Reads
