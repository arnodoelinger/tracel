package com.tracel.annotations

/**
 * Marks the bag of maps an in-memory store shows to the world.
 *
 * One writer mutates, many threads read. They must not share a `HashMap` the writer
 * is still changing — a lookup would see a chest with the items gone and the
 * total still there. So the writer finishes a change, then swaps in this whole
 * object at once. Readers always get a consistent picture, even a slightly old one.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Snapshot

/** Holder / item -> small int, so account keys stay two packed numbers. Reads never intern. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Intern

/** Extra lookup (holder -> items, item -> holders). Drop the entry when the set is empty. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Index

/**
 * Oldest-first queue. [orderBy] is the field that defines "oldest" (`fifoSeq`).
 * Taking and splitting the head stays handwritten.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Fifo(val orderBy: String)
