package fr.acinq.lightning.payment

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Script.tail
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.LightningCodecs
import kotlin.experimental.and

data class PaymentRequest(
    val prefix: String,
    val amount: MilliSatoshi?,
    val timestampSeconds: Long,
    val nodeId: PublicKey,
    val tags: List<TaggedField>,
    val signature: ByteVector
) {
    val paymentHash: ByteVector32 = tags.find { it is TaggedField.PaymentHash }!!.run { (this as TaggedField.PaymentHash).hash }

    val paymentSecret: ByteVector32 = tags.find { it is TaggedField.PaymentSecret }!!.run { (this as TaggedField.PaymentSecret).secret }

    val paymentMetadata: ByteVector? = tags.find { it is TaggedField.PaymentMetadata }?.run { (this as TaggedField.PaymentMetadata).data }

    val description: String? = tags.find { it is TaggedField.Description }?.run { (this as TaggedField.Description).description }

    val descriptionHash: ByteVector32? = tags.find { it is TaggedField.DescriptionHash }?.run { (this as TaggedField.DescriptionHash).hash }

    val expirySeconds: Long? = tags.find { it is TaggedField.Expiry }?.run { (this as TaggedField.Expiry).expirySeconds }

    val minFinalExpiryDelta: CltvExpiryDelta? = tags.find { it is TaggedField.MinFinalCltvExpiry }?.run { CltvExpiryDelta((this as TaggedField.MinFinalCltvExpiry).cltvExpiry.toInt()) }

    val fallbackAddress: String? = tags.find { it is TaggedField.FallbackAddress }?.run { (this as TaggedField.FallbackAddress).toAddress(prefix) }

    val features: ByteVector = tags.find { it is TaggedField.Features }.run { (this as TaggedField.Features).bits }

    val routingInfo: List<TaggedField.RoutingInfo> = tags.filterIsInstance<TaggedField.RoutingInfo>()

    init {
        val f = Features(features).invoiceFeatures()
        require(f.hasFeature(Feature.VariableLengthOnion)) { "${Feature.VariableLengthOnion.rfcName} must be supported" }
        require(f.hasFeature(Feature.PaymentSecret)) { "${Feature.PaymentSecret.rfcName} must be supported" }
        require(Features.validateFeatureGraph(f) == null)

        require(amount == null || amount > 0.msat) { "amount is not valid" }
        require(tags.filterIsInstance<TaggedField.PaymentHash>().size == 1) { "there must be exactly one payment hash tag" }
        require(tags.filterIsInstance<TaggedField.PaymentSecret>().size == 1) { "there must be exactly one payment secret tag" }
        require(description != null || descriptionHash != null) { "there must be exactly one description tag or one description hash tag" }
    }

    fun isExpired(currentTimestampSeconds: Long = currentTimestampSeconds()): Boolean = when (expirySeconds) {
        null -> timestampSeconds + DEFAULT_EXPIRY_SECONDS <= currentTimestampSeconds
        else -> timestampSeconds + expirySeconds <= currentTimestampSeconds
    }

    private fun hrp() = prefix + encodeAmount(amount)

    private fun rawData(): List<Int5> {
        val data5 = ArrayList<Int5>()
        data5.addAll(encodeTimestamp(timestampSeconds))
        tags.forEach {
            val encoded = it.encode()
            val len = encoded.size
            data5.add(it.tag)
            data5.add((len / 32).toByte())
            data5.add((len.rem(32)).toByte())
            data5.addAll(encoded)
        }
        return data5
    }

    private fun signedPreimage(): ByteArray {
        val stream = BitStream()
        rawData().forEach {
            val bits = toBits(it)
            stream.writeBits(bits)
        }
        return hrp().encodeToByteArray() + stream.getBytes()
    }

    private fun signedHash(): ByteVector32 = Crypto.sha256(signedPreimage()).toByteVector32()

    /**
     * Sign a payment request.
     *
     * @param privateKey private key, which must match the payment request's node id
     * @return a signature (64 bytes) plus a recovery id (1 byte)
     */
    fun sign(privateKey: PrivateKey): PaymentRequest {
        require(privateKey.publicKey() == nodeId) { "private key does not match node id" }
        val msg = signedHash()
        val sig = Crypto.sign(msg, privateKey)
        val (pub1, _) = Crypto.recoverPublicKey(sig, msg.toByteArray())
        val recid = if (nodeId == pub1) 0.toByte() else 1.toByte()
        return this.copy(signature = sig.concat(recid))
    }

    fun write(): String {
        val stream = BitStream()
        rawData().forEach {
            val bits = toBits(it)
            stream.writeBits(bits)
        }
        stream.writeBytes(signature.toByteArray().toList())

        fun read5(): Int5 {
            val bits = stream.readBits(5)
            val value = (if (bits[0]) 1 shl 4 else 0) + (if (bits[1]) 1 shl 3 else 0) + (if (bits[2]) 1 shl 2 else 0) + (if (bits[3]) 1 shl 1 else 0) + (if (bits[4]) 1 shl 0 else 0)
            return value.toByte()
        }

        val int5s = ArrayList<Int5>()
        while (stream.bitCount() >= 5) int5s.add(read5())

        return Bech32.encode(hrp(), int5s.toTypedArray(), Bech32.Encoding.Bech32)
    }

    companion object {
        const val DEFAULT_EXPIRY_SECONDS = 3600
        val DEFAULT_MIN_FINAL_EXPIRY_DELTA = CltvExpiryDelta(18)

        private val prefixes = mapOf(
            Block.RegtestGenesisBlock.hash to "lnbcrt",
            Block.TestnetGenesisBlock.hash to "lntb",
            Block.LivenetGenesisBlock.hash to "lnbc"
        )

        fun create(
            chainHash: ByteVector32,
            amount: MilliSatoshi?,
            paymentHash: ByteVector32,
            privateKey: PrivateKey,
            description: Either<String, ByteVector32>,
            minFinalCltvExpiryDelta: CltvExpiryDelta,
            features: Features,
            paymentSecret: ByteVector32 = randomBytes32(),
            paymentMetadata: ByteVector? = null,
            expirySeconds: Long? = null,
            extraHops: List<List<TaggedField.ExtraHop>> = listOf(),
            timestampSeconds: Long = currentTimestampSeconds()
        ): PaymentRequest {
            val prefix = prefixes[chainHash] ?: error("unknown chain hash")
            val tags = mutableListOf(
                TaggedField.PaymentHash(paymentHash),
                TaggedField.MinFinalCltvExpiry(minFinalCltvExpiryDelta.toLong()),
                TaggedField.PaymentSecret(paymentSecret),
                // We remove unknown features which could make the invoice too big.
                TaggedField.Features(features.invoiceFeatures().copy(unknown = setOf()).toByteArray().toByteVector())
            )
            description.left?.let { tags.add(TaggedField.Description(it)) }
            description.right?.let { tags.add(TaggedField.DescriptionHash(it)) }
            paymentMetadata?.let { tags.add(TaggedField.PaymentMetadata(it)) }
            expirySeconds?.let { tags.add(TaggedField.Expiry(it)) }
            if (extraHops.isNotEmpty()) {
                extraHops.forEach { tags.add(TaggedField.RoutingInfo(it)) }
            }

            return PaymentRequest(
                prefix = prefix,
                amount = amount,
                timestampSeconds = timestampSeconds,
                nodeId = privateKey.publicKey(),
                tags = tags,
                signature = ByteVector.empty
            ).sign(privateKey)
        }

        private fun decodeTimestamp(input: List<Int5>): Long = input.take(7).fold(0L) { a, b -> 32 * a + b }

        fun encodeTimestamp(input: Long): List<Int5> {
            tailrec fun loop(value: Long, acc: List<Int5>): List<Int5> = if (acc.size == 7) acc.reversed() else loop(value / 32, acc + value.rem(32).toByte())
            return loop(input, listOf())
        }

        fun read(input: String): PaymentRequest {
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
                    when (tag) {
                        TaggedField.PaymentHash.tag -> tags.add(kotlin.runCatching { TaggedField.PaymentHash.decode(value) }.getOrDefault(TaggedField.InvalidTag(tag, value)))
                        TaggedField.PaymentSecret.tag -> tags.add(kotlin.runCatching { TaggedField.PaymentSecret.decode(value) }.getOrDefault(TaggedField.InvalidTag(tag, value)))
                        TaggedField.PaymentMetadata.tag -> tags.add(kotlin.runCatching { TaggedField.PaymentMetadata.decode(value) }.getOrDefault(TaggedField.InvalidTag(tag, value)))
                        TaggedField.Description.tag -> tags.add(kotlin.runCatching { TaggedField.Description.decode(value) }.getOrDefault(TaggedField.InvalidTag(tag, value)))
                        TaggedField.DescriptionHash.tag -> tags.add(kotlin.runCatching { TaggedField.DescriptionHash.decode(value) }.getOrDefault(TaggedField.InvalidTag(tag, value)))
                        TaggedField.Expiry.tag -> tags.add(kotlin.runCatching { TaggedField.Expiry.decode(value) }.getOrDefault(TaggedField.InvalidTag(tag, value)))
                        TaggedField.MinFinalCltvExpiry.tag -> tags.add(kotlin.runCatching { TaggedField.MinFinalCltvExpiry.decode(value) }.getOrDefault(TaggedField.InvalidTag(tag, value)))
                        TaggedField.FallbackAddress.tag -> tags.add(kotlin.runCatching { TaggedField.FallbackAddress.decode(value) }.getOrDefault(TaggedField.InvalidTag(tag, value)))
                        TaggedField.Features.tag -> tags.add(kotlin.runCatching { TaggedField.Features.decode(value) }.getOrDefault(TaggedField.InvalidTag(tag, value)))
                        TaggedField.RoutingInfo.tag -> tags.add(kotlin.runCatching { TaggedField.RoutingInfo.decode(value) }.getOrDefault(TaggedField.InvalidTag(tag, value)))
                        else -> tags.add(TaggedField.UnknownTag(tag, value))
                    }
                    loop(input.drop(3 + len))
                }
            }

            loop(data.drop(7).dropLast(104))
            val pr = PaymentRequest(prefix, amount, timestamp, nodeId, tags, sigandrecid.toByteVector())
            require(pr.signedPreimage().contentEquals(tohash))
            return pr
        }

        fun decodeAmount(input: String): MilliSatoshi? {
            val amount = when {
                input.isEmpty() -> null
                input.last() == 'p' -> {
                    require(input.endsWith("0p")) { "invalid sub-millisatoshi precision" }
                    MilliSatoshi(input.dropLast(1).toLong() / 10L)
                }

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
        fun unit(amount: MilliSatoshi): Char? {
            val pico = amount.toLong() * 10
            return when {
                pico.rem(1_000) > 0 -> 'p'
                pico.rem(1_000_000) > 0 -> 'n'
                pico.rem(1_000_000_000) > 0 -> 'u'
                pico.rem(1_000_000_000_000) > 0 -> 'm'
                else -> null
            }
        }

        fun encodeAmount(amount: MilliSatoshi?): String {
            return when {
                amount == null -> ""
                unit(amount) == 'p' -> "${amount.toLong() * 10}p" // 1 pico-bitcoin == 10 milli-satoshis
                unit(amount) == 'n' -> "${amount.toLong() / 100}n"
                unit(amount) == 'u' -> "${amount.toLong() / 100_000}u"
                unit(amount) == 'm' -> "${amount.toLong() / 100_000_000}m"
                unit(amount) == null -> "${amount.toLong() / 100_000_000_000}"
                else -> throw IllegalArgumentException("invalid amount $amount")
            }
        }

        fun toBits(value: Int5): List<Boolean> = listOf(
            (value and 16) != 0.toByte(),
            (value and 8) != 0.toByte(),
            (value and 4) != 0.toByte(),
            (value and 2) != 0.toByte(),
            (value and 1) != 0.toByte()
        )
    }

    sealed class TaggedField {
        abstract val tag: Int5
        abstract fun encode(): List<Int5>

        /** @param description a free-format string that will be included in the payment request */
        data class Description(val description: String) : TaggedField() {
            override val tag: Int5 = Description.tag
            override fun encode(): List<Int5> = Bech32.eight2five(description.encodeToByteArray()).toList()

            companion object {
                const val tag: Int5 = 13
                fun decode(input: List<Int5>): Description = Description(Bech32.five2eight(input.toTypedArray(), 0).decodeToString())
            }
        }

        /** @param hash sha256 hash of an associated description */
        data class DescriptionHash(val hash: ByteVector32) : TaggedField() {
            override val tag: Int5 = DescriptionHash.tag
            override fun encode(): List<Int5> = Bech32.eight2five(hash.toByteArray()).toList()

            companion object {
                const val tag: Int5 = 23
                fun decode(input: List<Int5>): DescriptionHash {
                    require(input.size == 52)
                    return DescriptionHash(Bech32.five2eight(input.toTypedArray(), 0).toByteVector32())
                }
            }
        }

        /** @param hash payment hash */
        data class PaymentHash(val hash: ByteVector32) : TaggedField() {
            override val tag: Int5 = PaymentHash.tag
            override fun encode(): List<Int5> = Bech32.eight2five(hash.toByteArray()).toList()

            companion object {
                const val tag: Int5 = 1
                fun decode(input: List<Int5>): PaymentHash {
                    require(input.size == 52)
                    return PaymentHash(Bech32.five2eight(input.toTypedArray(), 0).toByteVector32())
                }
            }
        }

        /** @param secret payment secret */
        data class PaymentSecret(val secret: ByteVector32) : TaggedField() {
            override val tag: Int5 = PaymentSecret.tag
            override fun encode(): List<Int5> = Bech32.eight2five(secret.toByteArray()).toList()

            companion object {
                const val tag: Int5 = 16
                fun decode(input: List<Int5>): PaymentSecret {
                    require(input.size == 52)
                    return PaymentSecret(Bech32.five2eight(input.toTypedArray(), 0).toByteVector32())
                }
            }
        }


        data class PaymentMetadata(val data: ByteVector) : TaggedField() {
            override val tag: Int5 = PaymentMetadata.tag
            override fun encode(): List<Int5> = Bech32.eight2five(data.toByteArray()).toList()

            companion object {
                const val tag: Int5 = 27
                fun decode(input: List<Int5>): PaymentMetadata = PaymentMetadata(Bech32.five2eight(input.toTypedArray(), 0).toByteVector())
            }
        }

        /** @param expirySeconds payment expiry (in seconds) */
        data class Expiry(val expirySeconds: Long) : TaggedField() {
            override val tag: Int5 = Expiry.tag
            override fun encode(): List<Int5> {
                tailrec fun loop(value: Long, acc: List<Int5>): List<Int5> = if (value == 0L) acc.reversed() else {
                    loop(value / 32, acc + (value.rem(32)).toByte())
                }
                return loop(expirySeconds, listOf())
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

        /** @param cltvExpiry minimum final expiry delta */
        data class MinFinalCltvExpiry(val cltvExpiry: Long) : TaggedField() {
            override val tag: Int5 = MinFinalCltvExpiry.tag
            override fun encode(): List<Int5> {
                tailrec fun loop(value: Long, acc: List<Int5>): List<Int5> = if (value == 0L) acc.reversed() else {
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

        /** Fallback on-chain payment address to be used if LN payment cannot be processed */
        data class FallbackAddress(val version: Byte, val data: ByteVector) : TaggedField() {
            override val tag: Int5 = FallbackAddress.tag
            override fun encode(): List<Int5> = listOf(version) + Bech32.eight2five(data.toByteArray()).toList()

            fun toAddress(prefix: String): String = when (version.toInt()) {
                17 -> when (prefix) {
                    "lnbc" -> Base58Check.encode(Base58.Prefix.PubkeyAddress, data)
                    else -> Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, data)
                }

                18 -> when (prefix) {
                    "lnbc" -> Base58Check.encode(Base58.Prefix.ScriptAddress, data)
                    else -> Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, data)
                }

                else -> when (prefix) {
                    "lnbc" -> Bech32.encodeWitnessAddress("bc", version, data.toByteArray())
                    "lntb" -> Bech32.encodeWitnessAddress("tb", version, data.toByteArray())
                    "lnbcrt" -> Bech32.encodeWitnessAddress("bcrt", version, data.toByteArray())
                    else -> throw IllegalArgumentException("unknown prefix $prefix")
                }
            }

            companion object {
                const val tag: Int5 = 9
                fun decode(input: List<Int5>): FallbackAddress = FallbackAddress(input.first().toByte(), Bech32.five2eight(input.tail().toTypedArray(), 0).toByteVector())
            }
        }


        data class Features(val bits: ByteVector) : TaggedField() {
            override val tag: Int5 = Features.tag

            override fun encode(): List<Int5> {
                // We pad left to a multiple of 5
                val padded = bits.toByteArray().toMutableList()
                while (padded.size * 8 % 5 != 0) {
                    padded.add(0, 0)
                }
                // Then we remove leading 0 bytes
                return Bech32.eight2five(padded.toByteArray()).dropWhile { it == 0.toByte() }
            }

            companion object {
                const val tag: Int5 = 5
                fun decode(input: List<Int5>): Features {
                    // We pad left to a multiple of 8
                    val padded = input.toMutableList()
                    while (padded.size * 5 % 8 != 0) {
                        padded.add(0, 0)
                    }
                    // Then we remove leading 0 bytes
                    val features = Bech32.five2eight(padded.toTypedArray(), 0).dropWhile { it == 0.toByte() }
                    return Features(features.toByteArray().toByteVector())
                }
            }
        }

        /**
         * Extra hop contained in RoutingInfoTag
         *
         * @param nodeId start of the channel
         * @param shortChannelId channel id
         * @param feeBase node fixed fee
         * @param feeProportionalMillionths node proportional fee
         * @param cltvExpiryDelta node cltv expiry delta
         */
        data class ExtraHop(
            val nodeId: PublicKey,
            val shortChannelId: ShortChannelId,
            val feeBase: MilliSatoshi,
            val feeProportionalMillionths: Long,
            val cltvExpiryDelta: CltvExpiryDelta
        )

        /** @param hints extra routing information for a private route */
        data class RoutingInfo(val hints: List<ExtraHop>) : TaggedField() {
            override val tag: Int5 = RoutingInfo.tag

            override fun encode(): List<Int5> {
                val out = ByteArrayOutput()
                hints.forEach {
                    LightningCodecs.writeBytes(it.nodeId.value, out)
                    LightningCodecs.writeU64(it.shortChannelId.toLong(), out)
                    LightningCodecs.writeU32(it.feeBase.toLong().toInt(), out)
                    LightningCodecs.writeU32(it.feeProportionalMillionths.toInt(), out)
                    LightningCodecs.writeU16(it.cltvExpiryDelta.toInt(), out)
                }
                return Bech32.eight2five(out.toByteArray()).toList()
            }

            companion object {
                const val tag: Int5 = 3

                fun decode(input: List<Int5>): RoutingInfo {
                    val stream = ByteArrayInput(Bech32.five2eight(input.toTypedArray(), 0))
                    val hints = ArrayList<ExtraHop>()
                    while (stream.availableBytes >= 51) {
                        val hint = ExtraHop(
                            PublicKey(LightningCodecs.bytes(stream, 33)),
                            ShortChannelId(LightningCodecs.u64(stream)),
                            MilliSatoshi(LightningCodecs.u32(stream).toLong()),
                            LightningCodecs.u32(stream).toLong(),
                            CltvExpiryDelta(LightningCodecs.u16(stream))
                        )
                        hints.add(hint)
                    }
                    return RoutingInfo(hints)
                }
            }
        }

        /** Unknown tag (may or may not be valid) */
        data class UnknownTag(override val tag: Int5, val value: List<Int5>) : TaggedField() {
            override fun encode(): List<Int5> = value.toList()
        }

        /** Tag that we know is not valid (value is of the wrong length for example) */
        data class InvalidTag(override val tag: Int5, val value: List<Int5>) : TaggedField() {
            override fun encode(): List<Int5> = value.toList()
        }
    }
}