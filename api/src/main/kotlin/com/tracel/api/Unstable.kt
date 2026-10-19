package com.tracel.api

/** Part of the API that may still change or go away before 1.0.0. */
@RequiresOptIn(
    message = "Tracel API is unstable before 1.0.0: it may change without any warning.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
@MustBeDocumented
public annotation class Unstable
