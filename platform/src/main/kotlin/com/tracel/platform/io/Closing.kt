package com.tracel.platform.io

/** Closes [this] and says nothing about it failing. */
public fun AutoCloseable.closeQuietly() {
    try {
        close()
    } catch (_: Throwable) {
    }
}
