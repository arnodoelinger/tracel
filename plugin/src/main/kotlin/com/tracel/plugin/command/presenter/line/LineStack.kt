package com.tracel.plugin.command.presenter.line

/** [first] and every record that repeats it, folded: newest shown, the count and the total quantity kept. */
internal class LineStack(val first: LoggedLine) {
    var group: Long = first.millis
    var count: Int = if (first.counted) 1 else 0; private set
    var quantity: Long = first.quantity; private set
    var oldest: Long = first.millis; private set

    var net: Long = first.net?.delta ?: 0; private set
    var gained: Long = first.net?.delta?.takeIf { it > 0 } ?: 0; private set
    var lost: Long = first.net?.delta?.takeIf { it < 0 }?.let { -it } ?: 0; private set

    /** Adds a logged entry to the stack. */
    fun add(line: LoggedLine) {
        if (line.counted) count++
        quantity += line.quantity
        oldest = minOf(oldest, line.millis)
        val delta = line.net?.delta ?: return
        net += delta
        if (delta > 0) gained += delta else lost -= delta
    }
}
