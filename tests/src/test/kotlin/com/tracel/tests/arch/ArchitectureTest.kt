package com.tracel.tests.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withAnnotationOf
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import com.tracel.annotations.Journaled
import com.tracel.annotations.Observes
import org.junit.jupiter.api.Test

class ArchitectureTest {
    private fun sourceFiles() = Konsist.scopeFromProject()
        .files
        .filterNot { it.name == "ArchitectureTest" }

    @Test
    fun `no code touches BukkitScheduler`() {
        // BukkitScheduler does not exist on Folia
        sourceFiles().assertFalse(testName = "no BukkitScheduler") { file ->
            file.text.contains("BukkitScheduler") || file.text.contains("getScheduler()")
        }
    }

    @Test
    fun `no code blocks a thread with runBlocking`() {
        // runBlocking on a region thread stalls that region's tick, which on a busy server reads as a TPS collapse
        sourceFiles().assertFalse(testName = "no runBlocking") { file ->
            file.text.contains("runBlocking")
        }
    }

    @Test
    fun `Observes functions declare exactly one event parameter`() {
        // The annotation deliberately carries no event class: the type is read from the signature
        Konsist.scopeFromProject()
            .functions()
            .withAnnotationOf(Observes::class)
            .assertTrue(testName = "single event parameter") { it.parameters.size == 1 }
    }

    @Test
    fun `Journaled functions are suspending`() {
        // A journal step schedules onto a region or entity thread and awaits a barrier
        Konsist.scopeFromProject()
            .functions()
            .withAnnotationOf(Journaled::class)
            .assertTrue(testName = "journal steps suspend") { it.hasSuspendModifier }
    }
}
