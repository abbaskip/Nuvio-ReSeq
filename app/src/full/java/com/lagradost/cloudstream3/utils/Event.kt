package com.lagradost.cloudstream3.utils

import java.util.concurrent.CopyOnWriteArraySet

/** CloudStream event ABI, including subscriptions made by independently compiled extensions. */
class Event<T> {
    private val observers = CopyOnWriteArraySet<(T) -> Unit>()
    val size: Int get() = observers.size
    operator fun plusAssign(observer: (T) -> Unit) { observers.add(observer) }
    operator fun minusAssign(observer: (T) -> Unit) { observers.remove(observer) }
    operator fun invoke(value: T) { observers.forEach { it(value) } }
}
