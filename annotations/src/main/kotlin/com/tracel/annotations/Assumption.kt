package com.tracel.annotations

/**
 * Marks the risky half of a pair KSP backs with a generated `<name>Guarded` dispatcher, which
 * calls this function unless the assumption already failed live, falling back to [Fallback]
 * otherwise. Must be paired with [Fallback] on the same function.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
public annotation class Assumption
