package com.tracel.plugin.adapter.entity.kind

import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason
import org.bukkit.event.entity.EntityRemoveEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EntityKindsTest {
    private fun withhold(drops: MutableList<Pair<String, Int>>, cargo: List<Pair<String, Int>>) {
        val owed = HashMap<String, Long>()
        for ((key, amount) in cargo) owed.merge(key, amount.toLong(), Long::plus)
        val it = drops.listIterator()
        while (it.hasNext()) {
            val (key, amount) = it.next()
            val left = owed[key] ?: continue
            if (left <= 0L) continue
            if (amount <= left) {
                owed[key] = left - amount
                it.remove()
            } else {
                it.set(key to (amount - left.toInt()))
                owed[key] = 0L
            }
        }
    }

    @Test
    fun `a camel's saddle is dropped once, by us`() {
        val drops = mutableListOf("saddle" to 1)
        withhold(drops, listOf("saddle" to 1))
        assertEquals(emptyList<Pair<String, Int>>(), drops, "vanilla drops nothing we already took")
    }

    @Test
    fun `a mob's own loot survives — it is nobody's cargo`() {
        val drops = mutableListOf("leather" to 2, "beef" to 3)
        withhold(drops, emptyList())
        assertEquals(listOf("leather" to 2, "beef" to 3), drops)
    }

    @Test
    fun `a donkey keeps the chest it was wearing and loses only what was inside`() {
        val drops = mutableListOf("chest" to 1, "wheat" to 12)
        withhold(drops, listOf("wheat" to 12))
        assertEquals(listOf("chest" to 1), drops, "clearing the whole list ate the chest")
    }

    @Test
    fun `a stack bigger than the cargo is trimmed, not dropped whole`() {
        val drops = mutableListOf("wheat" to 20)
        withhold(drops, listOf("wheat" to 12))
        assertEquals(listOf("wheat" to 8), drops, "the eight vanilla owes on its own account stay")
    }

    private fun attaches(chested: Boolean, delta: Long, stored: Long): Boolean =
        !chested && delta > 0L

    private fun detaches(chested: Boolean, delta: Long, stored: Long): Boolean =
        chested && delta < 0L && stored < -delta

    @Test
    fun `a chest arriving at a bare donkey goes on its back`() {
        assertEquals(true, attaches(chested = false, delta = 1, stored = 0))
        assertEquals(false, detaches(chested = false, delta = 1, stored = 0))
    }

    @Test
    fun `a chest arriving at a donkey that already wears one goes in a slot`() {
        assertEquals(false, attaches(chested = true, delta = 1, stored = 0))
    }

    @Test
    fun `taking a chest back off a donkey carrying none in its slots removes the worn one`() {
        assertEquals(true, detaches(chested = true, delta = -1, stored = 0))
    }

    @Test
    fun `taking a chest back that is sitting in a slot leaves the worn one alone`() {
        assertEquals(false, detaches(chested = true, delta = -1, stored = 3))
    }

    @Test
    fun `asked for more chests than the slots hold, the last one is the worn one`() {
        assertEquals(true, detaches(chested = true, delta = -4, stored = 3))
    }

    @Test
    fun `cargo spread over several stacks is matched across all of them`() {
        val drops = mutableListOf("saddle" to 1, "saddle" to 1, "saddle" to 1)
        withhold(drops, listOf("saddle" to 1, "saddle" to 1, "saddle" to 1))
        assertEquals(emptyList<Pair<String, Int>>(), drops)
    }

    @Test
    fun `a mob a person made is a mob a rollback can take back`() {
        for (reason in listOf(
            SpawnReason.COMMAND, SpawnReason.SPAWNER_EGG, SpawnReason.DISPENSE_EGG,
            SpawnReason.EGG, SpawnReason.BREEDING, SpawnReason.BUILD_IRONGOLEM,
            SpawnReason.BUILD_WITHER, SpawnReason.CURED, SpawnReason.BUCKET,
        )) {
            assertTrue(reason.kind() != SpawnKind.World) { "$reason is somebody's doing" }
            assertTrue(shouldLogSpawn(reason)) { "$reason is somebody's doing" }
        }
    }

    @Test
    fun `wildlife wandering in is not`() {
        for (reason in listOf(
            SpawnReason.NATURAL, SpawnReason.SPAWNER,
            SpawnReason.TRIAL_SPAWNER, SpawnReason.RAID, SpawnReason.PATROL,
            SpawnReason.REINFORCEMENTS, SpawnReason.JOCKEY, SpawnReason.SLIME_SPLIT,
        )) {
            assertFalse(shouldLogSpawn(reason)) { "$reason is the world's own doing" }
        }
    }

    @Test
    fun `a parrot stepping off a shoulder is not a new parrot`() {
        assertFalse(shouldLogSpawn(SpawnReason.SHOULDER_ENTITY))
        assertFalse(shouldLogRemoval(EntityRemoveEvent.Cause.PICKUP, scenery = false, blamed = false))
    }

    @Test
    fun `a mob that walks through a portal is not a mob that died`() {
        assertFalse(shouldLogRemoval(EntityRemoveEvent.Cause.DISCARD, scenery = false, blamed = false))
        assertFalse(shouldLogSpawn(SpawnReason.NETHER_PORTAL))
    }

    @Test
    fun `every way of dying is worth recording`() {
        for (cause in listOf(
            EntityRemoveEvent.Cause.DEATH, EntityRemoveEvent.Cause.EXPLODE,
            EntityRemoveEvent.Cause.OUT_OF_WORLD, EntityRemoveEvent.Cause.PLUGIN,
        )) {
            assertTrue(shouldLogRemoval(cause, scenery = false, blamed = false)) {
                "$cause is a mob that is not coming back on its own"
            }
        }
    }

    @Test
    fun `a squid swimming out of range is not, but a boat vanishing is`() {
        assertFalse(shouldLogRemoval(EntityRemoveEvent.Cause.DESPAWN, scenery = false, blamed = false))
        assertTrue(shouldLogRemoval(EntityRemoveEvent.Cause.DESPAWN, scenery = true, blamed = false))
    }

    @Test
    fun `only a transformation somebody caused is undone`() {
        val cause = EntityRemoveEvent.Cause.TRANSFORMATION
        assertFalse(shouldLogRemoval(cause, scenery = false, blamed = false)) { "a zombie drowning" }
        assertTrue(shouldLogRemoval(cause, scenery = false, blamed = true)) { "a cured villager" }
    }
}
