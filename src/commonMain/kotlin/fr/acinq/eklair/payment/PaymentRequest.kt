package fr.acinq.eklair.payment

import fr.acinq.bitcoin.*
import fr.acinq.eklair.CltvExpiryDelta
import fr.acinq.eklair.MilliSatoshi
import fr.acinq.eklair.ShortChannelId
import fr.acinq.eklair.utils.BitStream
import fr.acinq.eklair.utils.toByteVector32
import fr.acinq.eklair.utils.toByteVector64
import fr.acinq.secp256k1.Hex
import kotlin.experimental.and

data class PaymentRequest(val prefix: String, val amount: MilliSatoshi?, val timestamp: Long, val nodeId: PublicKey, val tags: List<TaggedField>, val signature: ByteVector) {
    val paymentHash: ByteVector32? = tags.find { it is TaggedField.PaymentHash } ?.run { (this as TaggedField.PaymentHash).hash }

    val description: String? = tags.find { it is TaggedField.Description } ?.run { (this as TaggedField.Description).description }

    companion object {
        const val DEFAULT_EXPIRY_SECONDS = 3600

        val prefixes = mapOf(Block.RegtestGenesisBlock.hash to "lnbcrt", Block.TestnetGenesisBlock.hash to "lntb", Block.LivenetGenesisBlock.hash to "lnbc")

        fun read(input: String):  PaymentRequest {
            val (hrp, data) = Bech32.decode(input)
            val prefix = prefixes.values.find { hrp.startsWith(it) } ?: throw IllegalArgumentException("unknown prefix $hrp")
            val amount = decodeAmount(hrp.drop(prefix.length))
            val timestamp = data.take(7).fold(0L){ a, b -> 32 * a + b}
            val stream = BitStream()
            data.forEach { stream.writeBits(toBits(it)) }
            val sigandrecid = stream.popBytes(65).reversed().toByteArray()
            val sig = sigandrecid.dropLast(1).toByteArray().toByteVector64()
            val recid = sigandrecid.last()
            val data1 = stream.getBytes()
            println("data1: ${Hex.encode(data1)}")

            val msg = Crypto.sha256(hrp.encodeToByteArray() + data1)
            val nodeId = Crypto.recoverPublicKey(sig, msg, recid.toInt())
            val check = Crypto.verifySignature(msg, sig, nodeId)
            println(check)

            val tags = ArrayList<TaggedField>()

            tailrec fun loop(input: List<Int5>) {
                if (input.isNotEmpty()) {
                    val tag = input[0]
                    val len = 32 * input[1] + input[2]
                    val value = input.drop(3).take(len)
                    when(tag.toInt()) {
                        1 -> tags.add(TaggedField.PaymentHash.decode(value))
                        6 -> tags.add(TaggedField.Expiry.decode(value))
                        16 -> tags.add(TaggedField.PaymentSecret.decode(value))
                        13 -> tags.add(TaggedField.Description.decode(value))
                        24 -> tags.add(TaggedField.MinFinalCltvExpiry.decode(value))
                        else -> tags.add(TaggedField.UnknownTag(tag, value))
                    }
                    loop(input.drop(3 + len))
                }
            }

            loop(data.drop(7).dropLast(104))
            return PaymentRequest(prefix, amount, timestamp, nodeId, tags, sig)
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

        fun toBits(value: Int5): List<Boolean> = listOf(
            (value and 16) != 0.toByte(),
            (value and 8) != 0.toByte(),
            (value and 4) != 0.toByte(),
            (value and 2) != 0.toByte(),
            (value and 1) != 0.toByte())

        sealed class TaggedField {
            /**
             * Description
             *
             * @param description a free-format string that will be included in the payment request
             */
            data class Description(val description: String) : TaggedField() {
                companion object {
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
                companion object {
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
                companion object {
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
                companion object {
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
                companion object {
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
            data class ExtraHop(val nodeId: PublicKey, val shortChannelId: ShortChannelId, val feeBase: MilliSatoshi, val feeProportionalMillionths: Long, val cltvExpiryDelta: CltvExpiryDelta)

            /**
             * Routing Info
             *
             * @param path one or more entries containing extra routing information for a private route
             */
            data class RoutingInfo(val path: List<ExtraHop>) : TaggedField()

            data class UnknownTag(val tag: Int5, val value: List<Int5>) : TaggedField()
        }
    }
}