package com.tracel.annotations

/**
 * Exposes a function as a `/tracel` subcommand.
 *
 * `Brigadier` registration, argument parsing, the permission check and tab
 * completion are all generated from the Kotlin signature: parameter names become
 * argument names, default values become optional arguments, and suggestions come
 * from the parameter's type.
 *
 * ```
 * @Command("rollback preview", perm = "tracel.rollback")
 * suspend fun preview(actor: player, radius: Int = 16, since: Duration = 1.hours): PlanId
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Command(
    val value: String,
    val perm: String,
    val async: Boolean = true,
)
