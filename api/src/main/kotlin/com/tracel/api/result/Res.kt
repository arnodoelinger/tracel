package com.tracel.api.result

import com.tracel.api.Unstable

/**
 * What a call gave back: [Ok] with a value or [Err] with the reason.
 *
 * Example:
 * ```
 * when (val got = plan.apply()) {
 *     is Res.Ok -> say("Done!")
 *     is Res.Err -> say("No: ${got.error}.")
 * }
 * ```
 */
@Unstable
public sealed interface Res<out T, out E> {
    /** It worked. */
    public data class Ok<out T>(public val value: T) : Res<T, Nothing>

    /**
     * It didn't work, and [error] says why.
     *
     * Nothing was changed unless [error] says otherwise.
     */
    public data class Err<out E>(public val error: E) : Res<Nothing, E>
}

/** `true` for [Res.Ok]. */
@Unstable
public val Res<*, *>.isOk: Boolean get() = this is Res.Ok

/** The value, or `null` on [Res.Err]. */
@Unstable
public fun <T> Res<T, *>.getOrNull(): T? = (this as? Res.Ok)?.value

/** The error, or `null` on [Res.Ok]. */
@Unstable
public fun <E> Res<*, E>.errorOrNull(): E? = (this as? Res.Err)?.error

/** The value, or what [fallback] makes of the error. */
@Unstable
public inline fun <T, E> Res<T, E>.getOrElse(fallback: (E) -> T): T = when (this) {
    is Res.Ok -> value
    is Res.Err -> fallback(error)
}

/**
 * The value, or a [TracelException] carrying the error.
 *
 * For code that would rather crash than think.
 */
@Unstable
public fun <T> Res<T, *>.orThrow(): T = when (this) {
    is Res.Ok -> value
    is Res.Err -> throw TracelException(error)
}

/** Turns the value, leaves an error alone. */
@Unstable
public inline fun <T, R, E> Res<T, E>.map(transform: (T) -> R): Res<R, E> = when (this) {
    is Res.Ok -> Res.Ok(transform(value))
    is Res.Err -> this
}

/** Turns the error, leaves a value alone. */
@Unstable
public inline fun <T, E, F> Res<T, E>.mapErr(transform: (E) -> F): Res<T, F> = when (this) {
    is Res.Ok -> this
    is Res.Err -> Res.Err(transform(error))
}

/** The next step, taken only when this one worked. */
@Unstable
public inline fun <T, R, E> Res<T, E>.andThen(next: (T) -> Res<R, E>): Res<R, E> = when (this) {
    is Res.Ok -> next(value)
    is Res.Err -> this
}

/** Runs [action] on the value, if there is one. */
@Unstable
public inline fun <T, E> Res<T, E>.onOk(action: (T) -> Unit): Res<T, E> = also { if (it is Res.Ok) action(it.value) }

/** Runs [action] on the error, if there is one. */
@Unstable
public inline fun <T, E> Res<T, E>.onErr(action: (E) -> Unit): Res<T, E> = also { if (it is Res.Err) action(it.error) }

/** Thrown by [orThrow]; [error] is what [Res.Err] held. */
@Unstable
public class TracelException(public val error: Any?) : RuntimeException(error.toString())

/**
 * Result scope: [bind] hands back the value or leaves the block with the error.
 *
 * A scope lives as long as its block.
 */
@Unstable
public class ResScope<E> @PublishedApi internal constructor() {
    @PublishedApi
    internal var ended: Boolean = false

    /** The value of an [Res.Ok], or the end of the block with this [Res.Err]. */
    public fun <T> Res<T, E>.bind(): T = when (this) {
        is Res.Ok -> value
        is Res.Err -> fail(error)
    }

    /** Leaves the block with [error]. */
    public fun fail(error: E): Nothing {
        check(!ended) { "This res { } block has already ended; its scope does not outlive it." }
        throw ShortCircuit(this, error)
    }
}

/** How [ResScope.fail] leaves its block. */
@PublishedApi
internal class ShortCircuit(val scope: ResScope<*>, val error: Any?) : RuntimeException(null, null, false, false)

/**
 * Runs [block], ending early with the first error it [ResScope.bind]s or [ResScope.fail]s.
 *
 * Example:
 * ```
 * val applied = res {
 *     val ready = draft.preview().bind()
 *     if (ready.blocks > 10_000) fail(TooBig)
 *     ready.apply().bind()
 * }
 * ```
 */
@Unstable
@Suppress("UNCHECKED_CAST")
public inline fun <T, E> res(block: ResScope<E>.() -> T): Res<T, E> {
    val scope = ResScope<E>()
    return try {
        Res.Ok(scope.block())
    } catch (short: ShortCircuit) {
        if (short.scope !== scope) throw short
        Res.Err(short.error as E)
    } finally {
        scope.ended = true
    }
}
