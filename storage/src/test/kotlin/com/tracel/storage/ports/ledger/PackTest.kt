package com.tracel.storage.ports.ledger

import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.support.Stack
import com.tracel.storage.util.eachRow
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PackTest {
    private suspend fun Stack.rows(tag: Byte): Map<String, String> = storage.read {
        val out = LinkedHashMap<String, String>()
        eachRow(Keys.tagPrefix(tag)) { cursor ->
            out[cursor.key().joinToString("") { "%02x".format(it) }] = Records.asLong(cursor.value()).toString()
        }
        out
    }

    @Test
    fun `a thousand stolen lots are four packs, and taking them all back rewrites no lot`(@TempDir dir: Path) =
        runTest {
            Stack(dir).use { stack ->
                val chest = block(0, 64, 0)
                val thief = player(1)
                val lots =
                    (1..1_000).map { stack.mint(chest, diamond, 1).id }
                stack.move(chest, thief, diamond, 1_000)

                assertEquals(4, stack.rows(Keys.PACK_AT).size, "1000 lots in packs of at most 256")
                val pointers = stack.rows(Keys.LOT_PACK)
                assertEquals(1_000, pointers.size)

                val moved = stack.ledger.moveExactAll(thief, chest, lots)
                assertEquals(1_000, moved.size)
                assertEquals(pointers, stack.rows(Keys.LOT_PACK), "whole packs move; the lots inside them do not")
                assertEquals(1_000L, stack.ledger.totalAt(chest, diamond)?.raw)
                assertEquals(null, stack.ledger.totalAt(thief, diamond))
                assertEquals(
                    lots,
                    stack.repo.accountQueue(chest, diamond).map { it.lot.id },
                    "same order they were taken in"
                )
            }
        }
}
