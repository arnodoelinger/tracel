package com.tracel.api.addon

import com.tracel.api.Unstable
import com.tracel.api.actor.Actor
import com.tracel.api.history.EventRecord
import com.tracel.api.result.Res
import com.tracel.api.world.BlockPlace
import com.tracel.api.world.NamespacedName
import com.tracel.api.write.WriteError
import net.kyori.adventure.text.Component

/**
 * An event of the addon's own, with a schema: a quest done, a crate opened. History keeps it as text; this says how
 * to write it, read it back, and show it.
 *
 * Change what [encode] writes, and raise [version]; [decode] gets the version each record was written with, so old
 * records still read.
 */
@Unstable
public interface CustomEventType<T : Any> {
    /** What it is called; its namespace is the addon's ID. */
    public val name: NamespacedName

    /** Schema version, written with every record. At least 1. */
    public val version: Int

    /** [value] as text. */
    public fun encode(value: T): String

    /** What [encode] wrote under [version], or `null` when it cannot be read. */
    public fun decode(version: Int, text: String): T?

    /** What a lookup shows after `Steve did`: `quest Dragon Slayer`. */
    public fun line(value: T): Component
}

/** A [CustomEventType] once registered. */
@Unstable
public interface RegisteredEvent<T : Any> {
    /** What was registered. */
    public val type: CustomEventType<T>

    /**
     * Writes [value] down as [by]'s doing, the addon's own when left out.
     *
     * It is not a change to the world, so a rollback never touches it.
     */
    public suspend fun record(value: T, by: Actor? = null, at: BlockPlace? = null): Res<Unit, WriteError>

    /** The value [record] holds, or `null` when it is not one of these or does not decode. */
    public fun read(record: EventRecord): T?
}

/** Where event types are registered. */
@Unstable
public interface EventTypes {
    /**
     * Registers [type] until the addon is disabled. Its records stay after that, and lookups show them as
     * `unknown <name>`.
     *
     * A name outside the addon's namespace, one taken, or a version under 1 throws.
     */
    public fun <T : Any> register(type: CustomEventType<T>): RegisteredEvent<T>
}
