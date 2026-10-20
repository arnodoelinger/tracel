package com.tracel.storage.ports.job

import com.tracel.engine.rollback.outside.OutsideStep
import com.tracel.storage.support.Stack
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class OutsideStepsTest {
    private val paid = OutsideStep(10, "eco:paid", "@1! alice bob 100")
    private val claimed = OutsideStep(11, "claims:made", "@2! spawn")

    @Test
    fun `what an addon was told survives a restart`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val steps = OutsideSteps(stack.storage)
            steps.stage(listOf(paid, claimed))
            steps.bind(7, longArrayOf(10))
            steps.told(10, undone = true, generation = 1)
        }
        Stack(dir).use { stack ->
            val steps = OutsideSteps(stack.storage)
            assertEquals(listOf(paid.copy(undone = true, generation = 1, job = 7)), steps.ofJob(7))
            assertEquals(listOf(claimed), steps.ofKind("claims:made"))
        }
    }

    @Test
    fun `staging again does not forget what was told`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val steps = OutsideSteps(stack.storage)
            steps.stage(listOf(paid))
            steps.told(10, undone = true, generation = 1)
            steps.stage(listOf(paid))
            assertEquals(listOf(paid.copy(undone = true, generation = 1)), steps.ofKind("eco:paid"))
        }
    }

    @Test
    fun `an older telling never overwrites a newer one`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val steps = OutsideSteps(stack.storage)
            steps.stage(listOf(paid))
            steps.told(10, undone = false, generation = 2)
            steps.told(10, undone = true, generation = 1)
            val kept = steps.ofKind("eco:paid").single()
            assertEquals(false, kept.undone)
            assertEquals(2, kept.generation)
            assertNull(kept.job)
        }
    }
}
