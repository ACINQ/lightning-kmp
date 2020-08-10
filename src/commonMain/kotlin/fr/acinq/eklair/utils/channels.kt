package fr.acinq.eklair.utils

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlin.reflect.KProperty


@ExperimentalCoroutinesApi
operator fun <T : Any> ConflatedBroadcastChannel<T>.setValue(thisRef: Any?, property: KProperty<*>, value: T) {
    this.offer(value)
}

@ExperimentalCoroutinesApi
operator fun <T : Any> ConflatedBroadcastChannel<T>.getValue(thisRef: Any?, property: KProperty<*>): T {
    return this.value
}

