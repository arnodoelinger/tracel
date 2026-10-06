package com.tracel.storage.crash

import com.tracel.model.cause.CauseKind
import com.tracel.model.item.Quantity
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

object CrashHarness {
    const val COMMITTED = "COMMITTED "
    const val READY = "READY"

    @JvmStatic
    fun main(args: Array<String>) {
        val directory = Path.of(args[0])
        val chest = block(0, 64, 0)
        val steve = player(1)

        runBlocking {
            Stack(directory).use { stack ->
                stack.ledger.mint(chest, diamond, Quantity(TOTAL.toLong()), stack.counters.nextTxnId())
                println(READY)
                System.out.flush()

                var moved = 0
                while (moved < TOTAL) {
                    stack.gate.move(CauseKind.MACHINE, null, System.currentTimeMillis(), diamond, chest, steve, 1)
                    val drained = stack.drain()
                    if (drained == 0) continue
                    moved += drained
                    println("$COMMITTED$moved")
                    System.out.flush()
                }
            }
        }
    }

    private const val TOTAL = 100_000
}
