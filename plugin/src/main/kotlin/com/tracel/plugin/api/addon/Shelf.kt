package com.tracel.plugin.api.addon

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Things registered by name, one per name. Each [put] hands back what takes it off again. */
internal class Shelf<V : Any>(private val what: String) {
    private val items = ConcurrentHashMap<String, V>()

    operator fun get(key: String): V? = items[key]

    /** Everything on the shelf, in no particular order. */
    val values: Collection<V> get() = items.values

    operator fun contains(key: String): Boolean = items.containsKey(key)

    /** Puts [value] under [key]; a taken [key] throws. */
    fun put(key: String, value: V): AutoCloseable {
        require(items.putIfAbsent(key, value) == null) { "$what \"$key\" is already registered." }
        return AutoCloseable { items.remove(key, value) }
    }
}

/** Things registered in order, any number of them. [onChange] runs after every add and removal. */
internal class Rack<V : Any>(private val onChange: () -> Unit = {}) {
    val all: List<V>; field = CopyOnWriteArrayList<V>()

    fun add(value: V): AutoCloseable {
        all += value
        onChange()
        return AutoCloseable {
            if (all.removeIf { it === value }) onChange()
        }
    }
}
