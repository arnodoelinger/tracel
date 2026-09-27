package com.tracel.annotations

/**
 * Marks something that behavior may still change, improve, or disappear
 * entirely in the codebase.
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FIELD,
)
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
public annotation class Unstable
