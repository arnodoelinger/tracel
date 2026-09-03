package com.tracel.platform.scheduler

import com.tracel.annotations.RunsOn
import com.tracel.model.holder.HolderId
import kotlinx.coroutines.CoroutineDispatcher
import java.util.UUID

/**
 * Bridges [RunsOn]'s named thread contexts to real
 * [CoroutineDispatcher]s.
 */
public interface TracelSchedulers {
    /** The region thread that currently owns [location]. */
    public fun region(location: HolderId.Block): CoroutineDispatcher

    /** Whichever thread currently owns [entity] — follows it across regions and teleports. */
    public fun entity(entity: UUID): CoroutineDispatcher

    /** Server-global state, owned by no entity's region. */
    public val global: CoroutineDispatcher

    /** Off any region thread entirely: queries, planning, compaction, retention. */
    public val async: CoroutineDispatcher

    /** The single dedicated storage-writer thread. */
    public val storage: CoroutineDispatcher
}
