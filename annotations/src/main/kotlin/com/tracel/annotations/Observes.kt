package com.tracel.annotations

/**
 * Marks a function as an event capture point. Replaces `Bukkit`'s `@EventHandler`.
 *
 * ```
 * @Observes
 * fun onPlace(event: BlockPlaceEvent) { /* specifics */ }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Observes(
    val priority: Priority = Priority.MONITOR,
    val ignoreCancelled: Boolean = true,
)
