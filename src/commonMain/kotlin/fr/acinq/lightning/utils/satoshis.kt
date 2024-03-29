package fr.acinq.lightning.utils

import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.MilliSatoshi


// sumByLong does not exist :(
fun Iterable<Satoshi>.sum(): Satoshi {
    var sum: Long = 0
    for (element in this) {
        sum += element.sat
    }
    return Satoshi(sum)
}

fun Iterable<MilliSatoshi>.sum(): MilliSatoshi {
    var sum: Long = 0
    for (element in this) {
        sum += element.msat
    }
    return MilliSatoshi(sum)
}

fun Satoshi.toMilliSatoshi() = MilliSatoshi(sat * 1_000L)

operator fun MilliSatoshi.compareTo(other: Satoshi) = toLong().compareTo(other.toLong() * 1_000L)

private const val Coin = 100_000_000L
private const val MCoin = Coin / 1_000L

val Long.btc get() = Satoshi(this * Coin)
val Int.btc get() = toLong().btc
val Long.mbtc get() = Satoshi(this * MCoin)
val Int.mbtc get() = toLong().mbtc
val Long.sat get() = Satoshi(this)
val Int.sat get() = toLong().sat
val Long.msat get() = MilliSatoshi(this)
val Int.msat get() = toLong().msat
