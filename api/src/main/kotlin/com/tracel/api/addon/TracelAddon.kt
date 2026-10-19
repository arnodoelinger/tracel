package com.tracel.api.addon

import com.tracel.api.Unstable

/**
 * Optional lifecycle hooks of an [Addon].
 *
 * An addon that only needs what it registers does not need this.
 */
@Unstable
public interface TracelAddon {
    /** The addon was enabled. */
    public fun onEnable(context: AddonContext) {}

    /** The addon is being disabled. */
    public fun onDisable() {}
}

/** What an addon gets when it is enabled, and the owner of whatever it starts. */
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
}
