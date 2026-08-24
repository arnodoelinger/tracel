package com.tracel.storage

import com.tracel.engine.rollback.LotContribution
import com.tracel.engine.rollback.RollbackJobRecord
import com.tracel.engine.rollback.RollbackPlan
import com.tracel.engine.rollback.RollbackStep
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.TxnId
import com.tracel.storage.rollback.SqliteRollbackJobRepository
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class SqliteRollbackJobRepositoryTest {
    @Test
    fun `a job with every step variant round-trips through the database exactly`(@TempDir dir: Path) = runTest {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val repo = SqliteRollbackJobRepository(db.storage)
            val chest = block(0, 64, 0)
            val steve = player(1)

            val plan = RollbackPlan(
                listOf(
                    RollbackStep.Take(LotId(1), Quantity(3), chest),
                    RollbackStep.Mint(LotId(2), Quantity(4), SinkKind.LAVA),
                    RollbackStep.Debt(LotId(3), Quantity(5), UUID(0L, 99)),
                    RollbackStep.Unmake(
                        outputLot = LotId(4),
                        inputs = listOf(LotContribution(LotId(5), Quantity(2)), LotContribution(LotId(6), Quantity(7))),
                        craftedBy = TxnId(42),
                        holder = steve,
                    ),
                )
            )
            val record = RollbackJobRecord(RollbackJobId(1), plan, restoreTo = chest)

            repo.save(record)

            assertEquals(record, repo.find(RollbackJobId(1)))
        }
    }

    @Test
    fun `an unknown job is not found`(@TempDir dir: Path) = runTest {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val repo = SqliteRollbackJobRepository(db.storage)
            assertNull(repo.find(RollbackJobId(404)))
        }
    }

    @Test
    fun `a saved job survives reopening the database`(@TempDir dir: Path) = runTest {
        val path = dir.resolve("db.sqlite")
        val chest = block(0, 64, 0)
        val plan = RollbackPlan(listOf(RollbackStep.Take(LotId(1), Quantity(1), chest)))
        val record = RollbackJobRecord(RollbackJobId(7), plan, restoreTo = chest)

        TracelDatabase.open(path).use { db -> SqliteRollbackJobRepository(db.storage).save(record) }

        TracelDatabase.open(path).use { db ->
            assertEquals(record, SqliteRollbackJobRepository(db.storage).find(RollbackJobId(7)))
        }
    }
}
