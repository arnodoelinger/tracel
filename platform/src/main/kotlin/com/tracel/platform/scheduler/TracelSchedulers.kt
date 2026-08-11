package com.tracel.platform.scheduler

import com.tracel.model.holder.HolderId
import kotlinx.coroutines.CoroutineDispatcher
import java.util.UUID

/**
 * Bridges [com.tracel.annotations.RunsOn]'s named thread contexts to real
 * [CoroutineDispatcher]s.
 *
 * [region] and [entity] are factories rather than plain properties because which thread
 * they mean depends on which region or entity — unlike [global], [async] and [storage],
 * which are always the same one thread (or thread pool) for the life of the plugin.
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

    /** The single dedicated `SQLite`-writer thread — see `com.tracel.storage.TracelDatabase`. */
    public val storage: CoroutineDispatcher
}
