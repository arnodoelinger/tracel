package com.tracel.engine.wear

import com.tracel.model.lot.LotId

/**
 * Tool durability over time, told against the lot that holds the tool.
 *
 * An item key forgets the damage on purpose, so that a swing is not a burn and a mint. This is where the damage goes
 * instead: one mark for every change, so any moment in a lot's life has a damage that can be read back.
 */
public interface WearLog {
    /** Appends [mark] to the history of its lot. */
    public suspend fun record(mark: WearMark)

    /** Every mark of each of [lots], oldest first. Lots without one are absent. */
    public suspend fun marksOf(lots: Collection<LotId>): Map<LotId, List<WearMark>>
}

/**
 * The damage these marks, oldest first, say there was at [epochMillis]: what the last mark by then left, or, if all
 * come later, what the first of them started from. `null` without marks.
 */
public fun List<WearMark>.damageAt(epochMillis: Long): Int? =
    lastOrNull { it.epochMillis <= epochMillis }?.after ?: firstOrNull()?.before

/** The damage the newest mark left behind, or `null` without marks. */
public val List<WearMark>.damageNow: Int? get() = lastOrNull()?.after
