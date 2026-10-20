package com.tracel.api

/**
 * For `Tracel` itself.
 *
 * An addon that opts in here is writing `unsafe` and owns whatever breaks.
 */
@RequiresOptIn(
    message = "This is Tracel's own wiring, not API. Addons must not touch it.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@MustBeDocumented
public annotation class Internal
