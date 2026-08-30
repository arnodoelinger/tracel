package com.tracel.annotations

/** Mirrors `Bukkit`'s event priority without depending on `Bukkit`. */
public enum class Priority { LOWEST, LOW, NORMAL, HIGH, HIGHEST, MONITOR }

/** Why a transaction happened. Recorded on every transaction. */
public enum class CauseKind {
    PLAYER_ACTION,
    EXPLOSION,
    HOPPER,
    CRAFT,
    BLOCK_BREAK,
    ROLLBACK,
    INVOLUTION,
    ENTITY_ACTION,
    WORLD,
    PLUGIN,
    UNKNOWN,
}

/**
 * Rollback and undo bookkeeping. The job record is the reversible layer; lookup must not treat
 * these as ordinary history, or every undo / rollback round-trip would re-read itself.
 */
public val CauseKind.isBookkeeping: Boolean get() = this == CauseKind.ROLLBACK || this == CauseKind.INVOLUTION

/** Which holders the generated listener marks dirty for the end-of-tick diff. */
public enum class Tracked {
    TOP_INVENTORY,
    BOTTOM_INVENTORY,
    PLAYER_INVENTORY,
    SOURCE_INVENTORY,
    DESTINATION_INVENTORY,
    CLICKED_BLOCK,
    ENTITY_INVENTORY,
    EXPLODED_BLOCKS,
}

/**
 * Marks a function as an event capture point.
 *
 * The generated code covers the `Listener` class, its registration, the
 * cancelled-event filter, marking [tracks] holders dirty, and opening a
 * transaction with [cause]. The annotated function is left with only the part
 * that is actually specific to the event.
 *
 * ```
 * @Observes(priority = Priority.LOWEST, cause = CauseKind.PLAYER_ACTION,
 *           tracks = [Tracked.TOP_INVENTORY, Tracked.PLAYER_INVENTORY])
 * fun onClick(event: InventoryClickEvent) { /* Only the specifics */ }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Observes(
    val priority: Priority = Priority.MONITOR,
    val cause: CauseKind = CauseKind.UNKNOWN,
    val tracks: Array<Tracked> = [],
    val ignoreCancelled: Boolean = true,
)
