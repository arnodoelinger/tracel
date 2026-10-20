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
