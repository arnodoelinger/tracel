package com.tracel.storage

/**
 * Something that gathers writes during a unit of work and hands them to the batch in one go,
 * just before it commits.
 *
 * Joined through [StorageUnit.participant]; storage thread only, like the unit.
 */
interface UnitParticipant {
    /** Where the participant is now, to come back to. */
    fun savepoint(): Int

    /** Back to [savepoint]: everything gathered after it is forgotten. */
    fun rollbackTo(savepoint: Int)

    /**
     * Writes what was gathered into [unit].
     *
     * Called once, after the unit's last write and before its batch lands.
     */
    fun beforeCommit(unit: StorageUnit)
}
