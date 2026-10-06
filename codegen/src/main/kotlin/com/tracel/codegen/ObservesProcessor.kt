package com.tracel.codegen

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.writeTo

private const val OBSERVES = "com.tracel.annotations.Observes"

private val LISTENER = ClassName("org.bukkit.event", "Listener")
private val EVENT = ClassName("org.bukkit.event", "Event")
private val EVENT_PRIORITY = ClassName("org.bukkit.event", "EventPriority")
private val PLUGIN = ClassName("org.bukkit.plugin", "Plugin")
private val PLUGIN_MANAGER = ClassName("org.bukkit.plugin", "PluginManager")

/** Provider. */
public class ObservesProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        ObservesProcessor(environment.codeGenerator, environment.logger)
}

/** Turns `@Observes` functions into one `registerObserved(listener, plugin)` that wires them all up. */
public class ObservesProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private var written = false

    private class Handler(
        val function: KSFunctionDeclaration,
        val owner: KSClassDeclaration,
        val event: ClassName,
        val priority: String,
        val ignoreCancelled: Boolean,
    )

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (written) return emptyList()
        val functions = resolver.getSymbolsWithAnnotation(OBSERVES).filterIsInstance<KSFunctionDeclaration>().toList()
        if (functions.isEmpty()) return emptyList()
        val handlers = functions.mapNotNull(::handlerFor)
        if (handlers.size != functions.size) return emptyList()
        write(handlers)
        written = true
        return emptyList()
    }

    private fun handlerFor(function: KSFunctionDeclaration): Handler? {
        val name = function.simpleName.asString()
        val owner = function.parentDeclaration as? KSClassDeclaration
        val event = function.parameters.singleOrNull()?.type?.resolve()?.declaration?.qualifiedName
        val problem = when {
            owner?.qualifiedName == null -> "can only annotate a member function of a class"
            Modifier.PRIVATE in function.modifiers -> "must not be private"
            event == null -> "must take exactly one parameter, the event"
            else -> null
        }
        if (problem != null) {
            logger.error("@Observes function \"$name\" $problem.", function)
            return null
        }
        val annotation =
            function.annotations.first { it.annotationType.resolve().declaration.qualifiedName?.asString() == OBSERVES }

        fun argument(name: String) = annotation.arguments.firstOrNull { it.name?.asString() == name }?.value
        val priority = (argument("priority") as? KSType)?.declaration?.simpleName?.asString()
            ?: (argument("priority") as? KSClassDeclaration)?.simpleName?.asString()
            ?: "MONITOR"
        return Handler(
            function = function,
            owner = owner!!,
            event = ClassName.bestGuess(event!!.asString()),
            priority = priority,
            ignoreCancelled = argument("ignoreCancelled") as? Boolean ?: true,
        )
    }

    private fun observeCall(h: Handler): CodeBlock = CodeBlock.builder()
        .add("manager.observe<%T>(\n", h.event)
        .indent()
        .add("listener = listener,\nplugin = plugin,\npriority = %T.%L,\n", EVENT_PRIORITY, h.priority)
        .add("ignoreCancelled = %L\n", h.ignoreCancelled)
        .unindent()
        .add(") {\n")
        .indent()
        .addStatement("listener.%L(it)", h.function.simpleName.asString())
        .unindent()
        .add("}\n")
        .build()

    private fun write(handlers: List<Handler>) {
        val register = FunSpec.builder("registerObserved")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("listener", LISTENER)
            .addParameter("plugin", PLUGIN)
            .returns(INT)
            .addKdoc("Registers every `@Observes` handler on [listener] and says how many there were.")
            .addStatement("val manager = plugin.server.pluginManager")
            .beginControlFlow("return when (listener)")
            .apply {
                val byOwner = handlers.groupBy { it.owner.qualifiedName!!.asString() }.toSortedMap()
                for ((owner, group) in byOwner) {
                    beginControlFlow("is %T ->", ClassName.bestGuess(owner))
                    for (h in group.sortedBy { it.function.simpleName.asString() }) {
                        h.function.containingFile?.let(::addOriginatingKSFile)
                        addCode(observeCall(h))
                    }
                    addStatement("%L", group.size)
                    endControlFlow()
                }
                addStatement("else -> 0")
            }
            .endControlFlow()
            .build()

        val e = TypeVariableName("E", EVENT).copy(reified = true)
        val observe = FunSpec.builder("observe")
            .addModifiers(KModifier.PRIVATE, KModifier.INLINE)
            .receiver(PLUGIN_MANAGER)
            .addTypeVariable(e)
            .addParameter("listener", LISTENER)
            .addParameter("plugin", PLUGIN)
            .addParameter("priority", EVENT_PRIORITY)
            .addParameter("ignoreCancelled", BOOLEAN)
            .addParameter(
                "handler",
                LambdaTypeName.get(parameters = listOf(ParameterSpec.unnamed(e)), returnType = UNIT),
                KModifier.CROSSINLINE
            )
            .addCode(
                CodeBlock.builder()
                    .add("registerEvent(\n")
                    .indent()
                    .add("E::class.java,\nlistener,\npriority,\n{ _, event -> if (event is E) handler(event) },\n")
                    .add("plugin,\nignoreCancelled,\n")
                    .unindent()
                    .add(")\n")
                    .build(),
            )
            .build()

        FileSpec.builder("com.tracel.plugin.listener", "ObservedEvents")
            .indent("    ")
            .addFunction(register)
            .addFunction(observe)
            .build()
            .writeTo(codeGenerator, aggregating = true)
    }
}
