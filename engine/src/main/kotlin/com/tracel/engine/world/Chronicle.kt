package com.tracel.engine.world

import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.model.log.Seq
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import com.tracel.model.world.block.BlockShape

/**
 * History by place: what an area looked like at any moment,
 * answered without replaying the changes in between.
 */
public interface Chronicle {
    /**
     * Every cell of [region] that changed at or after [since]: what stood there just before,
     * and what the log says stands there now.
     *
     * A rollback of the whole area to [since] puts back [Rewound.then] wherever the world still
     * shows [Rewound.now].
     */
    public suspend fun rewind(region: LookupRegion, since: Long): List<Rewound>

    /** One [rewind] per moment of [moments], oldest moment first: the frames of a timelapse. */
    public suspend fun frames(region: LookupRegion, moments: List<Long>): List<List<Rewound>>

    /** Changes in [region] at or after [since] that did not start where the change before them left the cell. */
    public suspend fun unlogged(region: LookupRegion, since: Long): List<Unlogged>

    /** Files away what [world]'s tile at [tileX], [tileZ] has: nothing will change there for a while. */
    public suspend fun quiet(world: WorldId, tileX: Int, tileZ: Int)

    /** Pulls the history of [world]'s tile at [tileX], [tileZ] close: somebody is about to look. */
    public suspend fun warm(world: WorldId, tileX: Int, tileZ: Int)

    /** Where the chronicle stands. */
    public suspend fun stats(): ChronicleStats
}

/** One cell: [then], before the moment asked about, and [now]. */
public data class Rewound(public val at: BlockPos, public val then: BlockShape, public val now: BlockShape)

/**
 * A change that started from [found] where the change before it left [expected]:
 * something unlogged came between.
 */
public data class Unlogged(
    public val at: BlockPos,
    public val seq: Seq,
    public val epochMillis: Long,
    public val expected: BlockShape,
    public val found: BlockShape,
)

/** Capsules still in memory, capsules filed, and how big they came out. */
public data class ChronicleStats(
    public val liveCapsules: Int,
    public val liveChanges: Long,
    public val liveBytes: Long,
    public val filedCapsules: Long,
    public val filedChanges: Long,
    public val filedBytes: Long,
    public val unlogged: Long,
)
