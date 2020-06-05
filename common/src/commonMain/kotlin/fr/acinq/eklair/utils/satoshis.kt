package fr.acinq.eklair.utils

import fr.acinq.bitcoin.Satoshi
import fr.acinq.eklair.MilliSatoshi


// sumByLong does not exist :(
fun Iterable<Satoshi>.sum(): Satoshi {
    var sum: Long = 0
    for (element in this) {
        sum += element.sat
    }
    return Satoshi(sum)
}

operator fun MilliSatoshi.compareTo(other: Satoshi) = toLong().compareTo(other.toLong() * 1000L)

val Long.sat get() = Satoshi(this)
val Int.sat get() = toLong().sat
val Long.msat get() = MilliSatoshi(this)
val Int.msat get() = toLong().msat
