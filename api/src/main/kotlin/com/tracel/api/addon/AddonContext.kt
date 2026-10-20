package com.tracel.api.addon

import com.tracel.api.Unstable

/**
 * What an addon gets when it is enabled, and the owner of whatever it starts.
 *
 * Everything registered through it belongs to it, and goes away when the addon
 * is disabled. History it wrote stays.
 */
@Unstable
public interface AddonContext {
    /**
     * Addon ID.
     *
     * @return the addon's [Addon.id]
     */
    public val id: String

    /**
     * Makes [resource] close together with the addon, and returns it.
     *
     * @return [resource]
     */
    public fun <T : AutoCloseable> own(resource: T): T

    /** Containers of the addon's own: backpacks, machines, vaults. */
    public val holders: HolderTypes

    /** Names for custom blocks. */
    public val lenses: Lenses

    /** Events of the addon's own, with a schema. */
    public val eventTypes: EventTypes

    /** Who acts besides players and entities: NPCs, towns, bots. */
    public val actorKinds: ActorKinds

    /** Writing down what no `Bukkit` event reports. */
    public val recorder: AddonRecorder
}
