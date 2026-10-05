package com.tracel.tests.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.modifierprovider.withPublicOrDefaultModifier
import com.lemonappdev.konsist.api.ext.list.withAnnotationOf
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import com.tracel.annotations.Observes
import com.tracel.annotations.Journaled
import com.tracel.annotations.RequiresLease
import com.tracel.annotations.Consume
import com.tracel.annotations.Reads
import com.tracel.annotations.RunsOn
import com.tracel.annotations.SingleWriter
import org.junit.jupiter.api.Test

class ArchitectureTest {
    private fun sourceFiles() = Konsist.scopeFromProject()
        .files
        .filterNot { it.name == "ArchitectureTest" }

    private fun domainScope() = Konsist.scopeFromModules(listOf("model", "engine"))

    @Test
    fun `no code touches BukkitScheduler`() {
        sourceFiles().assertFalse(testName = "no BukkitScheduler") { file ->
            file.text.contains("BukkitScheduler") || file.text.contains("getScheduler()")
        }
    }

    @Test
    fun `no code blocks a thread with runBlocking`() {
        sourceFiles()
            .filterNot { it.name == "CrashHarness" }
            .assertFalse(testName = "no runBlocking") { file -> file.text.contains("runBlocking") }
    }

    @Test
    fun `storage never touches Bukkit`() {
        Konsist.scopeFromModule("storage")
            .files
            .assertFalse(testName = "no Bukkit in storage") { file ->
                file.text.contains("org.bukkit") || file.text.contains("io.papermc")
            }
    }

    @Test
    fun `no plugin code waits on a future or sleeps a thread`() {
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
        val handlers = Konsist.scopeFromModule("plugin")
            .files
            .filter { it.path.contains("/listener/") }
            .flatMap { it.functions() }
            .filter { it.annotations.any { annotation -> annotation.name == "Observes" } }
        check(handlers.isNotEmpty()) { "no @Observes handlers in plugin/listener — the filter is wrong" }
        handlers.assertFalse(testName = "no inline unit of work in an event handler") { function ->
            function.text.contains("atomically") && !function.text.contains("launch")
        }
    }

    @Test
    fun `Observes functions declare exactly one event parameter`() {
        val observed = Konsist.scopeFromProject()
            .functions()
            .withAnnotationOf(Observes::class)
        check(observed.isNotEmpty()) { "no @Observes functions — the annotation is unused decoration" }
        observed.assertTrue(testName = "single event parameter") { it.parameters.size == 1 }
    }

    @Test
    fun `no handler is registered by reflection any more`() {
        Konsist.scopeFromModule("plugin")
            .functions()
            .assertFalse(testName = "no @EventHandler") { function ->
                function.annotations.any { it.name == "EventHandler" }
            }
    }

    @Test
    fun `Journaled functions are suspending and talk to a journal`() {
        val journaled = Konsist.scopeFromProject()
            .functions()
            .withAnnotationOf(Journaled::class)
        check(journaled.isNotEmpty()) { "no @Journaled functions — the annotation is unused decoration" }
        journaled.assertTrue(testName = "journal steps suspend") { it.hasSuspendModifier }
        journaled.assertTrue(testName = "journal steps use a journal") {
            it.text.contains("journal.")
        }
    }

    @Test
    fun `InMemory ports are SingleWriter STORAGE and writes check in`() {
        val ports = Konsist.scopeFromModule("engine")
            .classes()
            .filter { it.name.startsWith("InMemory") }
        check(ports.isNotEmpty()) { "no InMemory* classes in engine" }
        ports.assertTrue(testName = "InMemory is @SingleWriter") { it.hasAnnotationOf(SingleWriter::class) }
        ports.assertTrue(testName = "InMemory is @RunsOn(STORAGE)") { klass ->
            klass.hasAnnotationOf(RunsOn::class) &&
                    klass.annotations.any { it.text.contains("STORAGE") }
        }
        ports.flatMap { klass ->
            klass.functions(includeNested = false)
                .withPublicOrDefaultModifier()
                .filterNot { it.hasAnnotationOf(Reads::class) }
        }.assertTrue(testName = "writes call writer.checkIn()") { function ->
            function.text.contains("writer.checkIn()")
        }
    }

    @Test
    fun `Reads methods on InMemory ports do not check in`() {
        Konsist.scopeFromModule("engine")
            .classes()
            .filter { it.name.startsWith("InMemory") }
            .flatMap { it.functions(includeNested = false) }
            .withAnnotationOf(Reads::class)
            .assertFalse(testName = "@Reads does not checkIn") { it.text.contains("writer.checkIn()") }
    }

    @Test
    fun `engine never claims REGION`() {
        Konsist.scopeFromModule("engine")
            .classes()
            .withAnnotationOf(RunsOn::class)
            .assertFalse(testName = "no REGION in engine") { klass ->
                klass.annotations.any { it.text.contains("REGION") }
            }
        Konsist.scopeFromModule("engine")
            .functions()
            .withAnnotationOf(RunsOn::class)
            .assertFalse(testName = "no REGION functions in engine") { function ->
                function.annotations.any { it.text.contains("REGION") }
            }
    }

    @Test
    fun `STORAGE types never mention Bukkit`() {
        Konsist.scopeFromModule("engine")
            .classes()
            .withAnnotationOf(RunsOn::class)
            .filter { klass -> klass.annotations.any { it.text.contains("STORAGE") } }
            .assertFalse(testName = "STORAGE has no Bukkit") { klass ->
                klass.containingFile.text.contains("org.bukkit") ||
                        klass.containingFile.text.contains("io.papermc")
            }
    }

    @Test
    fun `REGION functions do not open a unit of work without launch`() {
        Konsist.scopeFromProject()
            .functions()
            .withAnnotationOf(RunsOn::class)
            .filter { function -> function.annotations.any { it.text.contains("REGION") } }
            .assertFalse(testName = "REGION no inline atomically") { function ->
                function.text.contains("atomically") && !function.text.contains("launch")
            }
    }

    @Test
    fun `takeFifo and drainFifo are Consume`() {
        Konsist.scopeFromProject()
            .functions()
            .filter { it.name == "takeFifo" || it.name == "drainFifo" }
            .assertTrue(testName = "FIFO consume is @Consume") { it.hasAnnotationOf(Consume::class) }
    }

    @Test
    fun `model and engine properties are never var`() {
        domainScope()
            .properties()
            .filterNot { it.containingFile.name == "InMemoryLotRepository" }
            .withPublicOrDefaultModifier()
            .assertFalse(testName = "no var properties") { it.isVar }
    }

    @Test
    fun `model and engine properties are never lateinit`() {
        domainScope()
            .properties()
            .assertFalse(testName = "no lateinit properties") { it.hasLateinitModifier }
    }

    @Test
    fun `model and engine never use the not-null assertion operator`() {
        domainScope()
            .files
            .assertFalse(testName = "no !!") { it.text.contains("!!") }
    }

    @Test
    fun `public functions in model and engine never return a mutable collection type`() {
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
    fun `RequiresLease functions take a LotLease first`() {
        val gated = Konsist.scopeFromModule("engine")
            .functions()
            .withAnnotationOf(RequiresLease::class)
        check(gated.isNotEmpty()) { "no @RequiresLease functions" }
        gated.assertTrue(testName = "first parameter is a LotLease") {
            it.parameters.firstOrNull()?.type?.name == "LotLease"
        }
    }

    private fun rollbackLayer(layer: String) = Konsist.scopeFromModule("plugin")
        .files
        .filter { "/src/main/kotlin/com/tracel/plugin/rollback/$layer/" in it.path }

    private fun importsRollback(text: String, vararg layers: String) = layers.any { layer ->
        Regex("""^import com\.tracel\.plugin\.rollback\.$layer\.""", RegexOption.MULTILINE).containsMatchIn(text)
    }

    @Test
    fun `rollback structure and material halves never import each other`() {
        rollbackLayer("structure").assertFalse(testName = "structure imports material") {
            importsRollback(
                it.text,
                "material"
            )
        }
        rollbackLayer("material").assertFalse(testName = "material imports structure") {
            importsRollback(
                it.text,
                "structure"
            )
        }
    }

    @Test
    fun `rollback planning results and trace stay out of both halves`() {
        rollbackLayer("planning").assertFalse(testName = "planning imports a half") {
            importsRollback(it.text, "structure", "material")
        }
        (rollbackLayer("result") + rollbackLayer("trace")).assertFalse(testName = "result or trace imports a layer above") {
            importsRollback(it.text, "structure", "material", "planning")
        }
    }

    @Test
    fun `outside rollback only the wiring touches structure and planning`() {
        Konsist.scopeFromModule("plugin")
            .files
            .filter { "/src/main/kotlin/" in it.path && "/com/tracel/plugin/rollback/" !in it.path }
            .filterNot { it.name == "TracelServices" }
            .assertFalse(testName = "rollback internals leak out") { importsRollback(it.text, "structure", "planning") }
    }
}
