package org.utbot.common

import kotlinx.collections.immutable.PersistentMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Provides lazy map with overridden "get" method.
 * Thread-safe by default if producer is thread-safe. This behaviour can change by providing not thread-safe
 * MutableMap as "map" parameter, e.g. LinkedHashMap.
 */
fun <K, V> lazyMap(
    producer: (K) -> V,
    map: MutableMap<K, V> = ConcurrentHashMap()
): Map<K, V> = LazyMap(producer, map)

private class LazyMap<K, V>(
    val producer: (K) -> V,
    val map: MutableMap<K, V>
) : Map<K, V> by map {
    override fun get(key: K): V? = map.getOrPut(key) { producer(key) }
}

fun <K, V> PersistentMap<K, V>.putIfAbsent(key: K, value: V): PersistentMap<K, V> =
    if (key in this) {
        this
    } else {
        this.put(key, value)
    }

fun Collection<*>.prettify() = joinToString("\n", "\n", "\n")

fun Map<*, *>.prettify() = entries.joinToString("\n", "\n", "\n") { (key, value) ->
    "$key -> $value"
}
