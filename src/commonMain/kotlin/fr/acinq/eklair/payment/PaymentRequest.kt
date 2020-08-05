package fr.acinq.eklair.payment

import fr.acinq.bitcoin.*
import fr.acinq.eklair.CltvExpiryDelta
import fr.acinq.eklair.MilliSatoshi
import fr.acinq.eklair.ShortChannelId
import fr.acinq.eklair.utils.BitStream
import fr.acinq.eklair.utils.toByteVector32
import fr.acinq.eklair.utils.toByteVector64
import kotlinx.serialization.Serializable
import kotlin.experimental.and

data class PaymentRequest(val prefix: String, val amount: MilliSatoshi?, val timestamp: Long, val nodeId: PublicKey, val tags: List<TaggedField>, val signature: ByteVector) {
    val paymentHash: ByteVector32? = tags.find { it is TaggedField.PaymentHash } ?.run { (this as TaggedField.PaymentHash).hash }

    val description: String? = tags.find { it is TaggedField.Description } ?.run { (this as TaggedField.Description).description }

    private fun rawData() : List<Int5> {
        val data5 = ArrayList<Int5>()
        data5.addAll(encodeTimestamp(timestamp))
        tags.forEach {
            val encoded = it.encode()
            val len = it.encode().size
            data5.add(it.tag)
            data5.add((len / 32).toByte())
            data5.add((len.rem(32)).toByte())
            data5.addAll(encoded)
        }
        return data5
    }

    private fun preimage(): ByteArray {
        val stream = BitStream()
        rawData().forEach {
            val bits = toBits(it)
            stream.writeBits(bits)
        }
        return hrp().encodeToByteArray() + stream.getBytes()
    }

    private fun hrp() = prefix + encodeAmount(amount)

    fun hash(): ByteVector32 = Crypto.sha256(preimage()).toByteVector32()

    /**
     * Sign a paymwent request
     * @param privateKey private key, which must match the payment request's node id
     * @return a signature (64 bytes) plus a recovery id (1 byte)
     */
    fun sign(privateKey: PrivateKey) : PaymentRequest {
        require(privateKey.publicKey() == nodeId){ "private key does not match node id" }
        val msg = hash()
        val sig = Crypto.sign(msg, privateKey)
        val (pub1, _) = Crypto.recoverPublicKey(sig, msg.toByteArray())
        val recid = if (nodeId == pub1) 0.toByte() else 1.toByte()
        return this.copy(signature = sig.concat(recid))
    }

    fun write() : String {
        val stream = BitStream()
        rawData().forEach {
            val bits = toBits(it)
            stream.writeBits(bits)
        }
        stream.writeBytes(signature.toByteArray().toList())

        fun read5() : Int5 {
            val bits = stream.readBits(5)
            val value = (if (bits[0]) 1 shl 4 else 0) + (if (bits[1]) 1 shl 3 else 0) + (if (bits[2]) 1 shl 2 else 0) + (if (bits[3]) 1 shl 1 else 0) + (if (bits[4]) 1 shl 0 else 0)
            return value.toByte()
        }

        val int5s = ArrayList<Int5>()
        while(stream.bitCount() >= 5) int5s.add(read5())

        return Bech32.encode(hrp(), int5s.toByteArray())
    }

    companion object {
        const val DEFAULT_EXPIRY_SECONDS = 3600

        val prefixes = mapOf(Block.RegtestGenesisBlock.hash to "lnbcrt", Block.TestnetGenesisBlock.hash to "lntb", Block.LivenetGenesisBlock.hash to "lnbc")

        fun decodeTimestamp(input: List<Int5>): Long = input.take(7).fold(0L){ a, b -> 32 * a + b }

        fun encodeTimestamp(input: Long) : List<Int5> {
            tailrec fun loop(value: Long, acc: List<Int5>): List<Int5> = if (acc.size == 7) acc.reversed() else loop(value / 32, acc + value.rem(32).toByte())
            return loop(input, listOf())
        }

        fun read(input: String):  PaymentRequest {
            val (hrp, data) = Bech32.decode(input)
            val prefix = prefixes.values.find { hrp.startsWith(it) } ?: throw IllegalArgumentException("unknown prefix $hrp")
            val amount = decodeAmount(hrp.drop(prefix.length))
            val timestamp = decodeTimestamp(data.toList())
            val stream = BitStream()
            data.forEach { stream.writeBits(toBits(it)) }
            val sigandrecid = stream.popBytes(65).reversed().toByteArray()
            val sig = sigandrecid.dropLast(1).toByteArray().toByteVector64()
            val recid = sigandrecid.last()
            val data1 = stream.getBytes()
            val tohash = hrp.encodeToByteArray() + data1
            val msg = Crypto.sha256(tohash)
            val nodeId = Crypto.recoverPublicKey(sig, msg, recid.toInt())
            val check = Crypto.verifySignature(msg, sig, nodeId)
            require(check) { "invalid signature" }

            val tags = ArrayList<TaggedField>()

            tailrec fun loop(input: List<Int5>) {
                if (input.isNotEmpty()) {
                    val tag = input[0]
                    val len = 32 * input[1] + input[2]
                    val value = input.drop(3).take(len)
                    when(tag) {
                        TaggedField.PaymentHash.tag -> tags.add(kotlin.runCatching { TaggedField.PaymentHash.decode(value) }.getOrDefault(TaggedField.InvalidTag(tag, value)))
                        TaggedField.Expiry.tag -> tags.add(kotlin.runCatching { TaggedField.Expiry.decode(value) }.getOrDefault(TaggedField.InvalidTag(tag, value)))
                        TaggedField.PaymentSecret.tag -> tags.add(kotlin.runCatching { TaggedField.PaymentSecret.decode(value) }.getOrDefault(TaggedField.InvalidTag(tag, value)))
                        TaggedField.Description.tag -> tags.add(kotlin.runCatching { TaggedField.Description.decode(value) }.getOrDefault(TaggedField.InvalidTag(tag, value)))
                        TaggedField.MinFinalCltvExpiry.tag -> tags.add(kotlin.runCatching { TaggedField.MinFinalCltvExpiry.decode(value) }.getOrDefault(TaggedField.InvalidTag(tag, value)))
                        else -> tags.add(TaggedField.UnknownTag(tag, value))
                    }
                    loop(input.drop(3 + len))
                }
            }

            loop(data.drop(7).dropLast(104))
            val pr =  PaymentRequest(prefix, amount, timestamp, nodeId, tags, sig)
            require(pr.preimage().contentEquals(tohash))
            return pr
        }

        fun decodeAmount(input: String): MilliSatoshi? {
            val amount =  when {
                input.isEmpty() -> null
                input.last() == 'p' -> MilliSatoshi(input.dropLast(1).toLong() / 10L)
                input.last() == 'n' -> MilliSatoshi(input.dropLast(1).toLong() * 100L)
                input.last() == 'u' -> MilliSatoshi(input.dropLast(1).toLong() * 100000L)
                input.last() == 'm' -> MilliSatoshi(input.dropLast(1).toLong() * 100000000L)
                else -> MilliSatoshi(input.toLong() * 100000000000L)
            }
            return if (amount == MilliSatoshi(0)) null else amount
        }

        /**
         * @return the unit allowing for the shortest representation possible
         */
        fun unit(amount: MilliSatoshi): Char {
            val pico = amount.toLong() * 10
            return when {
                pico.rem(1000) > 0 -> 'p'
                pico.rem(1000000) > 0 -> 'n'
                pico.rem(1000000000) > 0 -> 'u'
                else -> 'm'
            }
        }

        fun encodeAmount(amount: MilliSatoshi?): String {
            return when {
                amount == null -> ""
                unit(amount) == 'p' -> "${amount.toLong() * 10L}p" // 1 pico-bitcoin == 10 milli-satoshis
                unit(amount) == 'n' -> "${amount.toLong() / 100L}n"
                unit(amount) == 'u' -> "${amount.toLong() / 100000L}u"
                unit(amount) == 'm' -> "${amount.toLong() / 100000000L}m"
                else -> throw IllegalArgumentException("invalid amount $amount")
            }
        }

        fun toBits(value: Int5): List<Boolean> = listOf(
            (value and 16) != 0.toByte(),
            (value and 8) != 0.toByte(),
            (value and 4) != 0.toByte(),
            (value and 2) != 0.toByte(),
            (value and 1) != 0.toByte())

        sealed class TaggedField {
            abstract val tag: Int5
            abstract fun encode() : List<Int5>
            /**
             * Description
             *
             * @param description a free-format string that will be included in the payment request
             */
            data class Description(val description: String) : TaggedField() {
                override val tag: Int5 = Description.tag

                override fun encode(): List<Int5> {
                    return Bech32.eight2five(description.encodeToByteArray().toTypedArray()).toList()
                }

                companion object {
                    const val tag: Int5 = 13
                    fun decode(input: List<Int5>): Description {
                        val description = Bech32.five2eight(input.toTypedArray(), 0).toByteArray().decodeToString()
                        return Description(description)
                    }
                }
            }

            /**
             * Payment Hash
             *
             * @param hash payment hash
             */
            data class PaymentHash(val hash: ByteVector32) : TaggedField() {
                override val tag: Int5 = PaymentHash.tag

                override fun encode(): List<Int5> {
                    return Bech32.eight2five(hash.toByteArray().toTypedArray()).toList()
                }

                companion object {
                    const val tag: Int5 = 1

                    fun decode(input: List<Int5>): PaymentHash {
                        require(input.size == 52)
                        val hash = Bech32.five2eight(input.toTypedArray(), 0)
                        return PaymentHash(hash.toByteArray().toByteVector32())
                    }
                }
            }

            /**
             * Payment Secret
             *
             * @param secret payment secret
             */
            data class PaymentSecret(val secret: ByteVector32) : TaggedField() {
                override val tag: Int5 = PaymentSecret.tag

                override fun encode(): List<Int5> {
                    return Bech32.eight2five(secret.toByteArray().toTypedArray()).toList()
                }

                companion object {
                    const val tag: Int5 = 16

                    fun decode(input: List<Int5>): PaymentSecret {
                        require(input.size == 52)
                        val secret = Bech32.five2eight(input.toTypedArray(), 0)
                        return PaymentSecret(secret.toByteArray().toByteVector32())
                    }
                }
            }

            /**
             * Payment expiry (in seconds)
             *
             * @param expiry payment expiry
             */
            data class Expiry(val expiry: Long) : TaggedField() {
                override val tag: Int5 = Expiry.tag

                override fun encode(): List<Int5> {
                    tailrec fun loop(value: Long, acc: List<Int5>) : List<Int5> = if (value == 0L) acc.reversed() else {
                        loop(value / 32, acc + (value.rem(32)).toByte())
                    }
                    return loop(expiry, listOf())
                }

                companion object {
                    const val tag: Int5 = 6

                    fun decode(input: List<Int5>): Expiry {
                        var expiry = 0L
                        input.forEach { expiry = expiry * 32 + it }
                        return Expiry(expiry)
                    }
                }
            }

            /**
             * Payment expiry (in seconds)
             *
             * @param expiry payment expiry
             */
            data class MinFinalCltvExpiry(val cltvExpiry: Long) : TaggedField() {
                override val tag: Int5 = MinFinalCltvExpiry.tag

                override fun encode(): List<Int5> {
                    tailrec fun loop(value: Long, acc: List<Int5>) : List<Int5> = if (value == 0L) acc.reversed() else {
                        loop(value / 32, acc + (value.rem(32)).toByte())
                    }
                    return loop(cltvExpiry, listOf())
                }
                companion object {
                    const val tag: Int5 = 24

                    fun decode(input: List<Int5>): MinFinalCltvExpiry {
                        var expiry = 0L
                        input.forEach { expiry = expiry * 32 + it }
                        return MinFinalCltvExpiry(expiry)
                    }
                }
            }

            /**
             * Extra hop contained in RoutingInfoTag
             *
             * @param nodeId                    start of the channel
             * @param shortChannelId            channel id
             * @param feeBase                   node fixed fee
             * @param feeProportionalMillionths node proportional fee
             * @param cltvExpiryDelta           node cltv expiry delta
             */
            @Serializable
            data class ExtraHop(val nodeId: PublicKey, val shortChannelId: ShortChannelId, val feeBase: MilliSatoshi, val feeProportionalMillionths: Long, val cltvExpiryDelta: CltvExpiryDelta)

            /**
             * Routing Info
             *
             * @param path one or more entries containing extra routing information for a private route
             */
            data class RoutingInfo(val path: List<ExtraHop>) : TaggedField() {
                override val tag: Int5
                    get() = TODO("Not yet implemented")

                override fun encode(): List<Int5> {
                    TODO("Not yet implemented")
                }
            }

            /**
             * Unknown tag (may or may not be valid)
             */
            data class UnknownTag(override val tag: Int5, val value: List<Int5>) : TaggedField() {
                override fun encode(): List<Int5>  = value.toList()
            }

            /**
             * Tag that we know is not valid (value is of the wrong length for example)
             */
            data class InvalidTag(override val tag: Int5, val value: List<Int5>) : TaggedField() {
                override fun encode(): List<Int5>  = value.toList()
            }
        }
    }
}