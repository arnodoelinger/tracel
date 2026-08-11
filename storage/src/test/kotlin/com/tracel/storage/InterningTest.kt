package com.tracel.storage

import com.tracel.model.id.Quantity
import com.tracel.model.id.TxnId
import com.tracel.storage.ledger.SqliteLotRepository
import com.tracel.storage.schema.HoldersTable
import com.tracel.storage.schema.ItemKeysTable
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The entire point of [com.tracel.storage.intern.Interning]: repeating the same item key or
 * holder across many lots / placements must not repeat the row, only the reference to it.
 */
class InterningTest {
    @Test
    fun `the same item key across many lots interns to exactly one row`(@TempDir dir: Path) {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val repo = SqliteLotRepository(db.exposed)
            repeat(20) { repo.createLot(diamond, Quantity(1), TxnId(1)) }

            val itemKeyRows = transaction(db.exposed) { ItemKeysTable.selectAll().count() }
            assertEquals(1L, itemKeyRows, "20 lots of the same item key must intern to one item_keys row")
        }
    }

    @Test
    fun `the same holder across many placements interns to exactly one row`(@TempDir dir: Path) {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val repo = SqliteLotRepository(db.exposed)
            val chest = block(0, 64, 0)
            repeat(20) { repo.place(chest, repo.createLot(diamond, Quantity(1), TxnId(1)).id, Quantity(1)) }

            val holderRows = transaction(db.exposed) { HoldersTable.selectAll().count() }
            assertEquals(1L, holderRows, "20 placements at the same holder must intern to one holders row")
        }
    }

    @Test
    fun `distinct item keys and holders each get their own row`(@TempDir dir: Path) {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val repo = SqliteLotRepository(db.exposed)
            val chest = block(0, 64, 0)
            val steve = player(1)

            repo.place(chest, repo.createLot(diamond, Quantity(1), TxnId(1)).id, Quantity(1))
            repo.place(steve, repo.createLot(diamond, Quantity(1), TxnId(1)).id, Quantity(1))

            val holderRows = transaction(db.exposed) { HoldersTable.selectAll().count() }
            assertEquals(2L, holderRows, "two distinct holders must not collapse into one")
        }
    }
}
