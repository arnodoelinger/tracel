package com.tracel.engine.wear

import com.tracel.model.id.LotId

/** One change to a tool's durability, told against the lot the ledger holds it as. */
public data class WearMark(
    public val lotId: LotId,
    public val epochMillis: Long,
    public val before: Int,
    public val after: Int,
)

/**
 * Tool durability over time.
 *
 * The item key forgot the damage on purpose, so a swing is not a burn and a mint. This is where the damage went instead.
 */
public interface WearLog {
    /** Records [mark]. */
    public suspend fun record(mark: WearMark)

    /** Every mark of each of [lots], oldest first. Lots without one are absent. */
    public suspend fun marksOf(lots: Collection<LotId>): Map<LotId, List<WearMark>>
}

/** Damage at [epochMillis]: what the last mark by then left, or what the first later one started from. */
public fun List<WearMark>.damageAt(epochMillis: Long): Int? =
    lastOrNull { it.epochMillis <= epochMillis }?.after ?: firstOrNull()?.before

/** Damage the newest mark left behind. */
public val List<WearMark>.damageNow: Int? get() = lastOrNull()?.after
