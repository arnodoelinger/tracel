package com.tracel.engine.event

import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.model.event.ActorEvent
import com.tracel.model.event.EventKind

/** The event log: what was said, what was typed, who joined and disconnected. */
public interface EventLog {
    /** Appends [event]. The log is append-only, so a sequence number that is taken is an error. */
    public suspend fun append(event: ActorEvent)

    /**
     * The events of [kinds] that [filter] lets through, newest first. Only who, when and where are asked of the
     * filter: an event is not a block or an item, and a filter that names one matches no event.
     */
    public suspend fun query(filter: LookupFilter, kinds: Set<EventKind>): List<ActorEvent>
}
