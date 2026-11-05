package com.tracel.storage.spi

/**
 * [KeyValueEngine.write] refused: an I / O error stopped the engine, and it takes writes again
 * once it has put what it holds somewhere safe.
 */
class WritesStopped(val since: Long, cause: Throwable, val fatal: Boolean = false) :
    RuntimeException("storage stopped taking writes after an I / O error: ${cause.message ?: cause.javaClass.simpleName}.", cause)
