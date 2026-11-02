package com.tracel.plugin.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import java.util.concurrent.CompletableFuture

/** What [this] said, or its class name when it said nothing. */
internal fun Throwable.messageOrName(): String = message ?: javaClass.simpleName

/** [work]'s result, or the failure it threw. A cancellation is no failure: it goes on up. */
internal suspend inline fun <T> attempt(work: suspend () -> T): Result<T> = try {
    Result.success(work())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    Result.failure(failure)
}

/** [work], or what [onFailure] makes of the exception it threw. A cancellation goes on up. */
internal suspend inline fun <T> rescue(onFailure: (Exception) -> T, work: suspend () -> T): T = try {
    work()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    onFailure(failure)
}

/** [rescue] that catches errors too. */
internal suspend inline fun <T> rescueAll(onFailure: (Throwable) -> T, work: suspend () -> T): T = try {
    work()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    onFailure(failure)
}

/**
 * The value of [this], or null when it failed. Unlike `runCatching`, a
 * canceled caller is not told it was a failure.
 */
internal suspend fun <T> CompletableFuture<T>.awaitOrNull(): T? = try {
    await()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}
