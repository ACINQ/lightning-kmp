package fr.acinq.lightning.utils


fun <T, R : T> List<T>.startsWith(prefix: List<R>): Boolean = this.take(prefix.size) == prefix

fun <T1, T2, T3, C : MutableCollection<in Triple<T1, T2, T3>>> Triple<Iterable<T1>, Iterable<T2>, Iterable<T3>>.zippedTo(destination: C): C {
    val i1 = first.iterator()
    val i2 = second.iterator()
    val i3 = third.iterator()

    while (i1.hasNext() && i2.hasNext() && i3.hasNext()) destination.add(Triple(i1.next(), i2.next(), i3.next()))
    return destination
}

fun <T1, T2, T3> Triple<Iterable<T1>, Iterable<T2>, Iterable<T3>>.zipped(): List<Triple<T1, T2, T3>> =
    zippedTo(ArrayList())
