package com.tracel.storage.crash

import com.tracel.model.transaction.CauseKind
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class KillNineRecoveryTest {
    @Test
    fun `every acknowledged commit survives a SIGKILL, and the store reopens clean`(@TempDir dir: Path) = runTest {
        val ledgerDir = dir.resolve("ledger")
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"),
            "--enable-native-access=ALL-UNNAMED",
            CrashHarness::class.java.name,
            ledgerDir.toString(),
        )
            .redirectError(dir.resolve("harness.err").toFile())
            .start()

        var acknowledged = 0L
        try {
            process.inputStream.bufferedReader().use { reader ->
                val deadline = System.currentTimeMillis() + 60_000
                while (System.currentTimeMillis() < deadline) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith(CrashHarness.COMMITTED)) continue
                    acknowledged = line.removePrefix(CrashHarness.COMMITTED).trim().toLong()
                    if (acknowledged >= 4_000) break
                }
            }
            assertTrue(acknowledged >= 4_000, "the harness only got to $acknowledged commits before dying on its own")
        } finally {
            process.destroyForcibly()
            process.waitFor(30, TimeUnit.SECONDS)
        }

        assertTrue(process.exitValue() != 0, "the process was supposed to be killed, not to finish")

        Stack(ledgerDir).use { stack ->
            val survived = stack.ledger.totalAt(player(1), diamond)?.raw ?: 0L
            assertTrue(
                survived >= acknowledged,
                "the process acknowledged $acknowledged durable moves but only $survived survived — " +
                        "that is data loss after an fsync returned",
            )
            assertEquals(
                100_000L,
                stack.ledger.census(diamond),
                "no unit may be created or lost by a crash: the census must still be exactly what was minted",
            )

            val before = stack.ledger.totalAt(player(1), diamond)?.raw ?: 0L
            stack.gate.move(
                CauseKind.HOPPER,
                null,
                System.currentTimeMillis(),
                diamond,
                com.tracel.tests.support.Fixtures.block(0, 64, 0),
                player(1),
                1,
            )
            stack.drain()
            assertEquals(
                before + 1,
                stack.ledger.totalAt(player(1), diamond)?.raw,
                "the recovered store must still accept writes"
            )
        }
    }
}
