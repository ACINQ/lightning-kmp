package fr.acinq.lightning.utils

import kotlin.reflect.KProperty

/*
    Workaround for "MPP: Can't stop on breakpoint anywhere in file if it contains lazy"
    https://youtrack.jetbrains.com/issue/KT-41471
    To be removed when fixed...
 */
operator fun <T> Lazy<T>.getValue(thisRef: Any?, property: KProperty<*>) = value