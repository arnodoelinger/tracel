package com.tracel.annotations

/**
 * The annotated function returns flows that must sum to zero per item key, once
 * the `SOURCE:*` and `SINK:*` pseudo-accounts are counted. This is invariant I1,
 * the one the whole no-duplication guarantee rests on.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Balanced
