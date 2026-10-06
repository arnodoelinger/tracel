package com.tracel.annotations

/** FIFO consume: the oldest entry first, split only the last lot touched. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Consume
