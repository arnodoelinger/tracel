package com.tracel.api.addon

import com.tracel.api.Unstable
import com.tracel.api.history.RecordKind

/**
 * A record was written. The method takes `(HistoryRecord)`.
 *
 * Fed from the same place as `watch`, so it arrives a second or two late, off the main thread.
 */
@Unstable
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
public annotation class OnRecord(
    /** Kinds of record to hear about; every kind when left empty. */
    val only: Array<RecordKind> = [],
)

/** A rollback is about to write. The method takes `(RollbackJob)`. */
@Unstable
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
public annotation class OnRollbackStarted

/**
 * A rollback is over, done or not. The method takes `(RollbackJob, RollbackOutcome)`; the job is the one
 * `@OnRollbackStarted` got.
 */
@Unstable
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
public annotation class OnRollbackFinished

/** A rollback was taken back. The method takes `(RollbackUndone)`, which names the job. */
@Unstable
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
public annotation class OnUndo

/**
 * Someone clicked a block with the inspector. The method takes `(BlockPlace, InspectLines)`; what it adds to the lines
 * is shown under the block's history.
 *
 * Runs on the region thread that owns the block, so the block may be read right there. Keep it quick: the click waits.
 */
@Unstable
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
public annotation class OnInspect
