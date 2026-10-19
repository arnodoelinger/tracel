package com.tracel.api.addon

import com.tracel.api.Unstable

/**
 * Marks a class as a `Tracel` addon.
 *
 * Register an instance with `Tracel.register(...)`; it may also implement [TracelAddon]
 * for lifecycle hooks.
 *
 * Example:
 * ```
 * @Addon(
 *    id = "my-addon",
 *    name = "My Addon",
 *    version = "1.0.0",
 *    requires = ["My Addon"]
 * )
 * class MyAddon : TracelAddon {
 *     override fun onEnable(context: AddonContext) { ... }
 * }
 * ```
 */
@Unstable
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
@MustBeDocumented
public annotation class Addon(
    /**
     * Unique ID: lowercase, digits, dashes.
     *
     * It's also the namespace of everything the addon names.
     */
    val id: String,

    /** Display name; [id] when left out. */
    val name: String = "",

    /** Version text, shown in `/tracel status`. */
    val version: String = "",

    /** One line on what it does. */
    val description: String = "",

    /**
     * Plugins that must be enabled for this addon to run. Missing one, and the addon stays off and says which;
     * its classes that touch that plugin are never loaded.
     */
    val requires: Array<String> = [],
)
