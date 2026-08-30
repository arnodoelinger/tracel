package com.tracel.annotations

/**
 * Crash-recoverable job runner: writes journal progress so a resumed job never
 * applies a finished step twice.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Journaled
