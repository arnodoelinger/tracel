package com.tracel.api.world

import com.tracel.api.Unstable
import com.tracel.api.result.getOrNull

/**
 * A namespaced name, e.g., `backpacks:backpack`. Addons name everything they add with one, so two addons never fight
 * over `backpack`.
 */
@Unstable
public data class NamespacedName(public val namespace: String, public val value: String) {
    init {
        require(NAMESPACE.matches(namespace)) { "Namespace \"$namespace\" must match ${NAMESPACE.pattern}." }
        require(VALUE.matches(value)) { "Value \"$value\" must match ${VALUE.pattern}." }
    }

    override fun toString(): String = "$namespace:$value"

    public companion object {
        private val NAMESPACE = Regex("[a-z0-9_.-]+")
        private val VALUE = Regex("[a-z0-9_./-]+")

        @JvmStatic
        public fun parse(text: String): NamespacedName? {
            val colon = text.indexOf(':')
            if (colon <= 0 || colon == text.lastIndex) return null
            return runCatching { NamespacedName(text.substring(0, colon), text.substring(colon + 1)) }.getOrNull()
        }
    }
}
