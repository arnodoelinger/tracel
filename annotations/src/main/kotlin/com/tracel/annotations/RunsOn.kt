package com.tracel.annotations

/** Thread this type or function may run on. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class RunsOn(val value: ThreadContext)
