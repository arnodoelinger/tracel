package com.tracel.plugin.command.action

import com.tracel.plugin.services.TracelServices
import com.tracel.plugin.util.text.send
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import java.util.concurrent.atomic.AtomicBoolean

/** `/tracel developer`: settling the store, to measure it beside another logger's. */
class DeveloperAction(private val services: TracelServices) {
    private val busy = AtomicBoolean(false)
    private val admin get() = services.storage.store

    /** Waits for the captures, then merges and presses everything as if today were over. */
    fun press(sender: CommandSender) = run(sender) {
        flush()
        bench().press()
        sender.send("developer.pressed")
    }

    /** Writes memory out and compacts every file. */
    fun compact(sender: CommandSender) = run(sender) {
        flush()
        bench().compact()
        sender.send("developer.compacted")
    }

    /** Wait, press, compact: the state to measure. */
    fun settle(sender: CommandSender) = run(sender) {
        flush()
        bench().press()
        bench().compact()
        sender.send("developer.settled")
    }

    private fun run(sender: CommandSender, work: suspend () -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            sender.send("developer.busy")
            return
        }
        services.scope.launch {
            try {
                work()
            } catch (failure: Throwable) {
                sender.send("developer.failed", "reason" to (failure.message ?: failure.javaClass.simpleName))
            } finally {
                busy.set(false)
            }
        }
    }

    private suspend fun flush() {
        services.capturing.flushCapture()
        admin.capture.awaitApplied(10_000L)
    }

    private fun bench() = admin.workbench ?: error("this store has no workbench")
}
