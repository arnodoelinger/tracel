import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build if a Minecraft platform dependency reaches a module that is
 * supposed to stay pure `Kotlin`.
 *
 * Walks the resolved dependency graph rather than the resolved artifacts, so it
 * never forces upstream jars to be built and stays compatible with the
 * configuration cache.
 */
abstract class VerifyNoBukkit : DefaultTask() {
    @get:Input
    abstract val moduleName: Property<String>

    @get:Internal
    abstract val rootComponent: Property<ResolvedComponentResult>

    @TaskAction
    fun verify() {
        val seen = LinkedHashSet<String>()
        collect(rootComponent.get(), seen)

        val offenders = seen.filter { id -> BANNED.any { it in id.lowercase() } }
        check(offenders.isEmpty()) {
            buildString {
                appendLine("Module '\"{moduleName.get()}\" must not depend on the Minecraft platform.")
            }
        }
    }

    private fun collect(component: ResolvedComponentResult, into: MutableSet<String>) {
        if (!into.add(component.id.displayName)) return
        component.dependencies
            .filterIsInstance<ResolvedDependencyResult>()
            .forEach { collect(it.selected, into) }
    }

    private companion object {
        val BANNED = listOf("paper-api", "bukkit", "spigot", "folia")
    }
}
