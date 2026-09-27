package com.tracel.tests.support

inline fun <reified T : Throwable> assertFails(block: () -> Unit): T {
    try {
        block()
    } catch (thrown: Throwable) {
        if (thrown is T) return thrown
        throw AssertionError(
            "expected ${T::class.simpleName}, got ${thrown::class.simpleName}: ${thrown.message}",
            thrown
        )
    }
    throw AssertionError("expected ${T::class.simpleName}, but nothing was thrown")
}
