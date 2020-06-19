package fr.acinq.eklair.channel

import fr.acinq.eklair.CltvExpiryDelta


object Channel {
    // this is defined in BOLT 11
    val MIN_CLTV_EXPIRY_DELTA = CltvExpiryDelta(9)
    val MAX_CLTV_EXPIRY_DELTA = CltvExpiryDelta(7 * 144) // one week

}