package fr.acinq.eklair.utils


fun <T, R : T> List<T>.startsWith(prefix: List<R>): Boolean = this.take(prefix.size) == prefix
