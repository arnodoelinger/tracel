package com.tracel.api.addon

import com.tracel.api.Unstable
import com.tracel.api.actor.Actor
import com.tracel.api.world.NamespacedName

/** A kind of actor the addon brings: a `Citizens` NPC, a town, a bot. */
@Unstable
public interface ActorKind {
    /** What it is called; its namespace is the addon's ID. */
    public val name: NamespacedName

    /** How lookups name actor [id], or `null` to show [id] itself. */
    public fun display(id: String): String?
}

/** An [ActorKind] once registered. */
@Unstable
public interface RegisteredActorKind {
    /** What was registered. */
    public val kind: ActorKind

    /** Actor [id] of this kind, for `by = ...`. */
    public operator fun get(id: String): Actor.Addon
}

/** Where actor kinds are registered. */
@Unstable
public interface ActorKinds {
    /**
     * Registers [kind] until the addon is disabled.
     *
     * What its actors did stays after that, shown as `unknown <name>`.
     *
     * A name outside the addon's namespace, or one taken, throws.
     */
    public fun register(kind: ActorKind): RegisteredActorKind
}
