package fr.acinq.eklair.utils

import fr.acinq.bitcoin.crypto.Pack
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
class UUID(val mostSignificantBits: Long, val leastSignificantBits: Long) : Comparable<UUID> {

    fun version(): Int =
            (mostSignificantBits shr 12 and 0xFL).toInt()

    fun variant(): Int =
            (leastSignificantBits.ushr((64L - leastSignificantBits.ushr(62)).toInt()) and (leastSignificantBits shr 63)).toInt()

    fun gregorianTimestamp(): Long =
            if (version() != 1) throw UnsupportedOperationException("Not a time-based UUID")
            else ((mostSignificantBits and 0xFFFL) shl 48) or (((mostSignificantBits shr 16) and 0xFFFFL) shl 32) or mostSignificantBits.ushr(32)

    fun unixTimestap(): Long = timestampGregorianToUnix(gregorianTimestamp())

    fun clockSequence(): Int =
            if (version() != 1) throw UnsupportedOperationException("Not a time-based UUID")
            else ((leastSignificantBits and 0x3FFF000000000000L).ushr(48)).toInt()

    fun node(): Long =
            if (version() != 1) throw UnsupportedOperationException("Not a time-based UUID")
            else leastSignificantBits and 0xFFFFFFFFFFFFL

    override fun toString(): String {
        val buf = ByteArray(36)
        formatUnsignedLong(leastSignificantBits, 4, buf, 24, 12)
        formatUnsignedLong(leastSignificantBits.ushr(48), 4, buf, 19, 4)
        formatUnsignedLong(mostSignificantBits, 4, buf, 14, 4)
        formatUnsignedLong(mostSignificantBits.ushr(16), 4, buf, 9, 4)
        formatUnsignedLong(mostSignificantBits.ushr(32), 4, buf, 0, 8)
        buf[23] = 45
        buf[18] = 45
        buf[13] = 45
        buf[8] = 45
        return buf.decodeToString()
    }

    override fun compareTo(other: UUID): Int =
            when {
                this.mostSignificantBits < other.mostSignificantBits -> -1
                this.mostSignificantBits > other.mostSignificantBits -> 1
                this.leastSignificantBits < other.leastSignificantBits -> -1
                this.leastSignificantBits > other.leastSignificantBits -> 1
                else -> 0
            }

    override fun hashCode(): Int {
        val hilo = this.mostSignificantBits xor this.leastSignificantBits
        return (hilo shr 32).toInt() xor hilo.toInt()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as UUID

        if (mostSignificantBits != other.mostSignificantBits) return false
        if (leastSignificantBits != other.leastSignificantBits) return false

        return true
    }

    companion object {
        private val digits = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z')
        private const val MIN_CLOCK_SEQ_AND_NODE = -0x7F7F7F7F7F7F7F80L
        private const val MAX_CLOCK_SEQ_AND_NODE =  0x7F7F7F7F7F7F7F7FL
        private const val START_EPOCH = -12219292800000L // 15 oct 1582 00:00:00.000
        private val MIN_UNIX_TIMESTAMP = timestampGregorianToUnix(0)
        private val MAX_UNIX_TIMESTAMP = timestampGregorianToUnix(0xFFFFFFFFFFFFFFFL)

        private fun formatUnsignedLong(value: Long, shift: Int, buf: ByteArray, offset: Int, len: Int) {
            var work = value
            var charPos = offset + len
            val radix = 1 shl shift
            val mask = radix - 1

            do {
                --charPos
                buf[charPos] = digits[work.toInt() and mask].toByte()
                work = work ushr shift
            } while (charPos > offset)
        }

        fun randomUUID(): UUID {
            val data = ByteArray(16).also { Random.secure().nextBytes(it) }
            data[6] = (data[6].toInt() and 0x0F).toByte()
            data[6] = (data[6].toInt() or 0x40).toByte()
            data[8] = (data[8].toInt() and 0x3F).toByte()
            data[8] = (data[8].toInt() or 0x80).toByte()
            return UUID(Pack.uint64BE(data, 0), Pack.uint64BE(data, 8))
        }

        fun fromString(name: String): UUID {
            val len = name.length
            require(len <= 36) { "UUID string too large" }
            val dash1 = name.indexOf(45.toChar(), 0)
            val dash2 = name.indexOf(45.toChar(), dash1 + 1)
            val dash3 = name.indexOf(45.toChar(), dash2 + 1)
            val dash4 = name.indexOf(45.toChar(), dash3 + 1)
            val dash5 = name.indexOf(45.toChar(), dash4 + 1)
            if (dash4 >= 0 && dash5 < 0) {
                var mostSigBits = name.substring(0, dash1).toLong(16) and 4294967295L
                mostSigBits = mostSigBits shl 16
                mostSigBits = mostSigBits or (name.substring(dash1 + 1, dash2).toLong(16) and 65535L)
                mostSigBits = mostSigBits shl 16
                mostSigBits = mostSigBits or (name.substring(dash2 + 1, dash3).toLong(16) and 65535L)
                var leastSigBits = name.substring(dash3 + 1, dash4).toLong(16) and 65535L
                leastSigBits = leastSigBits shl 48
                leastSigBits = leastSigBits or (name.substring(dash4 + 1, len).toLong(16) and 281474976710655L)
                return UUID(mostSigBits, leastSigBits)
            } else {
                throw IllegalArgumentException("Invalid UUID string: $name")
            }
        }

        private fun makeMsb(timestamp: Long): Long {
            var msb = 0L
            msb = msb or (0x00000000FFFFFFFFL and timestamp shl 32)
            msb = msb or (0x0000FFFF00000000L and timestamp).ushr(16)
            msb = msb or (0x0FFF000000000000L and timestamp).ushr(48)
            msb = msb or  0x0000000000001000L // sets the version to 1.
            return msb
        }

        private fun timestampUnixToGregorian(unixTimestampMillis: Long): Long {
            return (unixTimestampMillis - START_EPOCH) * 10000
        }

        private fun timestampGregorianToUnix(gregorianTimestampNano: Long): Long {
            return (gregorianTimestampNano / 10000) + START_EPOCH
        }

        fun startOf(unixTimestampMillis: Long): UUID {
            val gregorianTimestamp = timestampUnixToGregorian(unixTimestampMillis)
            return UUID(makeMsb(gregorianTimestamp), MIN_CLOCK_SEQ_AND_NODE)
        }

        fun endOf(unixTimestampMillis: Long): UUID {
            val gregorianTimestamp = timestampUnixToGregorian(unixTimestampMillis + 1) - 1
            return UUID(makeMsb(gregorianTimestamp), MAX_CLOCK_SEQ_AND_NODE)
        }

        private fun makeLsb(clockSequence: Long, node: Long): Long {
            var lsb = 0L
            lsb = lsb or (clockSequence and 0x3FFFL).shl(48)
            lsb = lsb or Long.MIN_VALUE
            lsb = lsb or node
            return lsb
        }

        fun timeUUID(unixTimestampMillis: Long = currentTimestampMillis(), clockSequence: Int = -1, node: Long = -1L): UUID {
            val random = Random.secure()
            require(unixTimestampMillis in MIN_UNIX_TIMESTAMP..MAX_UNIX_TIMESTAMP) { "Bad timestamp (must be in $MIN_UNIX_TIMESTAMP..$MAX_UNIX_TIMESTAMP)" }
            val realClockSeq = if (clockSequence == -1) random.nextLong(0x4000L) else clockSequence.toLong()
            require(realClockSeq in 0L..0x3FFFL) { "Bad clock sequence (must be in 0..0x3FFF)" }
            val realNode = if (node == -1L) random.nextLong(0x1000000000000L) else node
            require(realNode in 0L..0xFFFFFFFFFFFFL) { "Bad node (must be in 0..0xFFFFFFFFFFFF)" }
            val gregorianTimestamp = timestampUnixToGregorian(unixTimestampMillis)
            return UUID(makeMsb(gregorianTimestamp), makeLsb(realClockSeq, realNode))
        }
    }
}
