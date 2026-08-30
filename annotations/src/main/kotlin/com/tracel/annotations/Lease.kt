package com.tracel.annotations

/**
 * Marker for the lot-lease protocol. KSP emits `LotLease`, `LeaseAcquisition`, and
 * `LotLeaseRegistry` (`acquire` / `extend` / `holdingFor`) in the same package.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Lease

/**
 * In-memory exclusive map for [Lease]. KSP emits a superclass with `tryReserve`,
 * `release`, `transfer`, `reapAbandoned` — each already checked in. The annotated class
 * is empty and extends `*Store`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class LeaseStore

/** First parameter must be a `LotLease`. `Konsist` enforces it. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class RequiresLease
