package com.tracel.api.addon

import com.tracel.api.Unstable
import com.tracel.api.access.AccessError
import com.tracel.api.access.TracelConnection
import com.tracel.api.result.Res

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

    /**
     * Writing down what no `Bukkit` event reports.
     *
     * Needs the `attribute` grant of the plugin that loaded the addon.
     */
    public val recorder: Res<AddonRecorder, AccessError>

    /**
     * Events a rollback takes back through the addon.
     *
     * Needs the `rollback` grant.
     */
    public val reversibles: Res<Reversibles, AccessError>

    /**
     * The connection of the plugin that loaded the addon: its reads, and what its grants let it do.
     *
     * Closed with the addon.
     */
    public val connection: TracelConnection
}
