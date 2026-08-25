package com.tracel.tests.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.modifierprovider.withPublicOrDefaultModifier
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

    private fun domainScope() = Konsist.scopeFromModules(listOf("model", "engine"))

    @Test
    fun `no code touches BukkitScheduler`() {
        // BukkitScheduler does not exist on Folia
        sourceFiles().assertFalse(testName = "no BukkitScheduler") { file ->
            file.text.contains("BukkitScheduler") || file.text.contains("getScheduler()")
        }
    }

    @Test
    fun `no code blocks a thread with runBlocking`() {
        // runBlocking on a region thread stalls that region's tick, which on a busy server
        // reads as a TPS collapse.
        sourceFiles()
            .filterNot { it.name == "CrashHarness" }
            .assertFalse(testName = "no runBlocking") { file -> file.text.contains("runBlocking") }
    }

    @Test
    fun `storage never touches Bukkit`() {
        // Storage threads own no world state and must never be the ones asking for it.
        Konsist.scopeFromModule("storage")
            .files
            .assertFalse(testName = "no Bukkit in storage") { file ->
                file.text.contains("org.bukkit") || file.text.contains("io.papermc")
            }
    }

    @Test
    fun `no plugin code waits on a future or sleeps a thread`() {
        // Both are the same bug wearing different clothes: a region thread that stops ticking
        // until storage catches up. The ring exists so that this is never necessary.
        Konsist.scopeFromModule("plugin")
            .files
            .assertFalse(testName = "no blocking waits in plugin") { file ->
                file.text.contains("Thread.sleep") ||
                    file.text.contains(".getNow(") ||
                    Regex("""\bfutures?\.get\(""").containsMatchIn(file.text) ||
                    file.text.contains("CountDownLatch")
            }
    }

    @Test
    fun `capture listeners do not open a unit of work on the event thread`() {
        // A listener may enqueue into the ring, or launch onto the async scope. What it may not
        // do is call atomically { } inline, which runs storage work on whatever thread the event
        // arrived on — and for a Folia region event, that is the region thread.
        Konsist.scopeFromModule("plugin")
            .files
            .filter { it.path.contains("/listener/") }
            .flatMap { it.functions() }
            .filter { it.annotations.any { annotation -> annotation.name == "EventHandler" } }
            .assertFalse(testName = "no inline unit of work in an event handler") { function ->
                function.text.contains("atomically") && !function.text.contains("launch")
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

    @Test
    fun `model and engine properties are never var`() {
        // A var is an aliasing hazard: two references disagreeing about a value. Local vars inside
        // a function body (like a loop counter) are not properties, so they are unaffected by this.
        domainScope()
            .properties()
            .assertFalse(testName = "no var properties") { it.isVar }
    }

    @Test
    fun `model and engine properties are never lateinit`() {
        // lateinit is a promise the compiler cannot check — the field is either uninitialized-and-crashing
        // or already var (which the rule above forbids), so this stays as an explicit, defense-in-depth check.
        domainScope()
            .properties()
            .assertFalse(testName = "no lateinit properties") { it.hasLateinitModifier }
    }

    @Test
    fun `model and engine never use the not-null assertion operator`() {
        // !! turns a type-level guarantee into a runtime coin flip; a null here should be handled
        // explicitly (sealed result, require / check with a message) instead of silently crashing.
        domainScope()
            .files
            .assertFalse(testName = "no !!") { it.text.contains("!!") }
    }

    @Test
    fun `public functions in model and engine never return a mutable collection type`() {
        // A caller across a module boundary owns what it's handed; if the return type is MutableList,
        // it can mutate ledger-owned state without the ledger ever knowing.
        domainScope()
            .functions()
            .withPublicOrDefaultModifier()
            .assertFalse(testName = "no mutable return type") { it.returnType?.isMutableType == true }
    }

    @Test
    fun `public function parameters in model and engine never accept a mutable collection type`() {
        domainScope()
            .functions()
            .withPublicOrDefaultModifier()
            .flatMap { it.parameters }
            .assertFalse(testName = "no mutable parameter type") { it.type.isMutableType }
    }

    @Test
    fun `public properties in model and engine never expose a mutable collection type`() {
        domainScope()
            .properties()
            .withPublicOrDefaultModifier()
            .assertFalse(testName = "no mutable property type") { it.type?.isMutableType == true }
    }

    @Test
    fun `JournalExecutor and InvolutionExecutor cannot be called without a LotLease`() {
        val gated = Konsist.scopeFromModule("engine")
            .classes()
            .filter { it.name == "JournalExecutor" || it.name == "InvolutionExecutor" }
            .flatMap { it.functions() }
            .filter { it.name == "execute" || it.name == "apply" }

        gated.assertTrue(testName = "first parameter is a LotLease") {
            it.parameters.firstOrNull()?.type?.name == "LotLease"
        }
    }
}
