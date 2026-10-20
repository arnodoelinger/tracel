package com.tracel.plugin.api.addon

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** An annotated addon method and the values it may be handed. Only the addon's own methods are reflected. */
internal class AddonMethod(private val target: Any, private val method: Method) {
    /** Name for messages. */
    val description: String get() = "${target.javaClass.name}.${method.name}"

    init {
        method.isAccessible = true
    }

    /** Checks every parameter can be filled from [available], in order; throws a message saying what fits if not. */
    fun requireParameters(annotation: String, vararg available: Class<*>) {
        val params = method.parameterTypes
        require(params.size <= available.size && params.indices.all { params[it].isAssignableFrom(available[it]) }) {
            "$description is annotated with \"@$annotation\", so it must take " +
                "(${available.joinToString { it.simpleName }}) or a leading part of it, not " +
                "(${params.joinToString { it.simpleName }})."
        }
    }

    /** Calls the method with the leading part of [args] it asks for. What the addon throws comes out as is. */
    fun call(vararg args: Any?) {
        try {
            method.invoke(target, *args.copyOf(method.parameterCount))
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }
}

/** Methods carrying [annotation] on [addon]'s class and its superclasses. */
internal fun <A : Annotation> findAnnotated(addon: Any, annotation: Class<A>): List<Pair<AddonMethod, A>> {
    val found = ArrayList<Pair<AddonMethod, A>>()
    var type: Class<*>? = addon.javaClass
    while (type != null && type != Any::class.java) {
        for (method in type.declaredMethods) {
            if (method.isSynthetic) continue
            val marked = method.getAnnotation(annotation) ?: continue
            found += AddonMethod(addon, method) to marked
        }
        type = type.superclass
    }
    return found
}
