package com.tracel.annotations

/** First parameter must be a `Lease`. `Konsist` enforces it. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class RequiresLease
