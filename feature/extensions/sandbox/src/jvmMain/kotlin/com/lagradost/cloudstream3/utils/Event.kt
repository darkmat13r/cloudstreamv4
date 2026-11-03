package com.lagradost.cloudstream3.utils

/** `:app`'s event, with the same synchronous delivery. Nothing outside a host's own plugin raises one. */
class Event<T> {
    private val observers = mutableSetOf<(T) -> Unit>()

    val size: Int get() = synchronized(observers) { observers.size }

    operator fun plusAssign(observer: (T) -> Unit) {
        synchronized(observers) { observers += observer }
    }

    operator fun minusAssign(observer: (T) -> Unit) {
        synchronized(observers) { observers -= observer }
    }

    operator fun invoke(value: T) {
        synchronized(observers) { observers.toList() }.forEach { it(value) }
    }
}
