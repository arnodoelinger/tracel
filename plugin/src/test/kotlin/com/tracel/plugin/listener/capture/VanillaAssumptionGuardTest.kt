package com.tracel.plugin.listener.capture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class VanillaAssumptionGuardTest {
    private val world = UUID.randomUUID()

    @Test
    fun `a spawn right where a watch is active trips the breaker`() {
        val guard = VanillaAssumptionGuard()
        guard.watch("container-clear", world, 10, 64, 10)

        assertFalse(guard.isTripped("container-clear"), "watching alone must not trip anything")
        guard.checkSurpriseSpawn(world, 10.5, 64.5, 10.5)
        assertTrue(guard.isTripped("container-clear"), "a surprise spawn right at the watched block must trip it")
    }

    @Test
    fun `a spawn far from any watch does not trip anything`() {
        val guard = VanillaAssumptionGuard()
        guard.watch("container-clear", world, 10, 64, 10)

        guard.checkSurpriseSpawn(world, 500.0, 64.0, 500.0)
        assertFalse(guard.isTripped("container-clear"), "unrelated, far-away spawns are not evidence of anything")
    }

    @Test
    fun `assumptions are tracked independently`() {
        val guard = VanillaAssumptionGuard()
        guard.watch("container-clear", world, 10, 64, 10)
        guard.watch("block-place-self-drop", world, 20, 64, 20)

        guard.checkSurpriseSpawn(world, 10.0, 64.0, 10.0)

        assertTrue(guard.isTripped("container-clear"))
        assertFalse(guard.isTripped("block-place-self-drop"), "tripping one assumption must not trip an unrelated one")
    }

    @Test
    fun `a spawn in a different world never trips a watch from another world`() {
        val guard = VanillaAssumptionGuard()
        guard.watch("container-clear", world, 10, 64, 10)

        guard.checkSurpriseSpawn(UUID.randomUUID(), 10.0, 64.0, 10.0)
        assertFalse(guard.isTripped("container-clear"))
    }
}
