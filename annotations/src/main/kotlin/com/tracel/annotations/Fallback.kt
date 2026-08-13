package com.tracel.annotations

/**
 * Names the always-safe alternative for a function marked [Assumption], in the same class, to
 * switch to once that assumption is known to have failed live. [to] must name another function
 * in the same class, with the exact same parameter list (same types, same order) as the
 * annotated function.
 *
 * ```
 * @Assumption
 * @Fallback("onBreakFallback")
 * internal fun onBreak(event: BlockBreakEvent, holder: HolderId.Block, causedBy: HolderId.Player, epochMillis: Long) { ... }
 *
 * internal fun onBreakFallback(event: BlockBreakEvent, holder: HolderId.Block, causedBy: HolderId.Player, epochMillis: Long) { ... }
 * ```
 * generates, in the same package:
 * ```
 * internal const val ContainerBreakListener_onBreakRiskyAssumptionId: String = "ContainerBreakListener.onBreak"
 *
 * internal fun ContainerBreakListener.onBreakRiskyGuarded(event: BlockBreakEvent, holder: HolderId.Block, causedBy: HolderId.Player, epochMillis: Long) { ... }
 * ```
 * Call `onBreakRiskyGuarded` from the real `@EventHandler` entry point instead of `onBreak`
 * directly. The generated id constant is there so the risky function's own body can `watch()`
 * under the exact id the generated dispatcher checks, without hand-duplicating a string literal
 * that could drift out of sync.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
@MustBeDocumented
public annotation class Fallback(
    val to: String,
)
