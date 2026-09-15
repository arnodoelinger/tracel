package com.tracel.plugin.rollback.trace

/** A trace that is not there. Runs the work, keeps nothing. */
internal object NoTrace : RollbackTrace {
    private val nothing: () -> Unit = {}

    override suspend fun <T> span(name: String, block: suspend () -> T): T = block()

    override fun <T> measure(name: String, block: () -> T): T = block()

    override fun addNanos(name: String, nanos: Long) = Unit

    override fun note(name: String, value: Int) = Unit

    override fun noteMax(name: String, value: Int) = Unit

    override fun noteMin(name: String, value: Int) = Unit

    override fun add(name: String, value: Int) = Unit

    override fun stopwatch(name: String): () -> Unit = nothing

    override fun render(): List<String> = emptyList()
}
