package com.tracel.engine.rollback.outside

/**
 * A logged record whose effect lives outside the world, in someone else's hands.
 *
 * Whether a rollback took it back is what the log's rolled-back marks say. What its
 * owner was last told is [undone]; the two disagree only between a rollback, or its undo,
 * and the owner hearing of it, and then the owner is told.
 */
public data class OutsideStep(
    public val seq: Long,
    public val kind: String,
    public val text: String,
    public val undone: Boolean = false,
    public val generation: Int = 0,
    public val job: Long? = null,
)

/** What owners of [OutsideStep]s were told, kept across restarts. */
public interface OutsideSteps {
    /** Keeps [steps] that are not kept yet, before a rollback that may take them back runs. */
    public suspend fun stage(steps: List<OutsideStep>)

    /** Notes that rollback [job] took back the steps at [seqs]. */
    public suspend fun bind(job: Long, seqs: LongArray)

    /** The steps rollback [job] took back. */
    public suspend fun ofJob(job: Long): List<OutsideStep>

    /** Every step of [kind]. */
    public suspend fun ofKind(kind: String): List<OutsideStep>

    /** Notes that the owner of the step at [seq] was told [undone], for the [generation]th time. */
    public suspend fun told(seq: Long, undone: Boolean, generation: Int)
}
