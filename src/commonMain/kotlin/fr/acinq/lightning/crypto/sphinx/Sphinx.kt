package fr.acinq.lightning.crypto.sphinx

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Script.tail
import fr.acinq.bitcoin.crypto.Digest
import fr.acinq.bitcoin.crypto.hmac
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.lightning.crypto.ChaCha20
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.*
import fr.acinq.secp256k1.Hex
import kotlin.native.concurrent.ThreadLocal

/**
 * Decrypting an onion packet yields a payload for the current node and the encrypted packet for the next node.
 *
 * @param payload      decrypted payload for this node.
 * @param nextPacket   packet for the next node.
 * @param sharedSecret shared secret for the sending node, which we will need to return failure messages.
 */
data class DecryptedPacket(val payload: ByteVector, val nextPacket: OnionRoutingPacket, val sharedSecret: ByteVector32) {
    val isLastPacket: Boolean = nextPacket.hmac == ByteVector32.Zeroes
}

data class SharedSecrets(val perHopSecrets: List<Pair<ByteVector32, PublicKey>>)

data class PacketAndSecrets(val packet: OnionRoutingPacket, val sharedSecrets: SharedSecrets)

/**
 * see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md
 */
object Sphinx {
    // We use HMAC-SHA256 which returns 32-bytes message authentication codes.
    const val MacLength = 32

    /** Secp256k1's base point. */
    private val CurveG = PublicKey(ByteVector("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"))

    fun mac(key: ByteArray, message: ByteArray): ByteVector32 = Digest.sha256().hmac(key, message, 64).toByteVector32()

    private fun mac(key: ByteVector, message: ByteVector): ByteVector32 = mac(key.toByteArray(), message.toByteArray())

    private fun generateKey(keyType: ByteArray, secret: ByteVector32): ByteVector32 = mac(keyType, secret.toByteArray())

    fun generateKey(keyType: String, secret: ByteVector32): ByteVector32 = generateKey(keyType.encodeToByteArray(), secret)

    fun zeroes(length: Int): ByteArray = ByteArray(length)

    fun generateStream(key: ByteVector32, length: Int): ByteArray = ChaCha20.encrypt(zeroes(length), key.toByteArray(), zeroes(12))

    private fun computeSharedSecret(pub: PublicKey, secret: PrivateKey): ByteVector32 = Crypto.sha256(pub.times(secret).value).toByteVector32()

    private fun computeBlindingFactor(pub: PublicKey, secret: ByteVector): ByteVector32 = Crypto.sha256(pub.value + secret).toByteVector32()

    fun blind(pub: PublicKey, blindingFactor: ByteVector32): PublicKey = pub.times(PrivateKey(blindingFactor))

    fun blind(pub: PublicKey, blindingFactors: List<ByteVector32>): PublicKey = blindingFactors.fold(pub) { a, b -> blind(a, b) }

    /** When an invalid onion is received, its hash should be included in the failure message. */
    fun hash(onion: OnionRoutingPacket): ByteVector32 = Crypto.sha256(OnionRoutingPacketSerializer(onion.payload.size()).write(onion)).toByteVector32()

    /**
     * Compute the ephemeral public keys and shared secrets for all nodes on the route.
     *
     * @param sessionKey this node's session key.
     * @param publicKeys public keys of each node on the route.
     * @return a tuple (ephemeral public keys, shared secrets).
     */
    fun computeEphemeralPublicKeysAndSharedSecrets(sessionKey: PrivateKey, publicKeys: List<PublicKey>): Pair<List<PublicKey>, List<ByteVector32>> {
        val ephemeralPublicKey0 = blind(CurveG, sessionKey.value)
        val secret0 = computeSharedSecret(publicKeys.first(), sessionKey)
        val blindingFactor0 = computeBlindingFactor(ephemeralPublicKey0, secret0)
        return computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys.drop(1), listOf(ephemeralPublicKey0), listOf(blindingFactor0), listOf(secret0))
    }

    private tailrec fun computeEphemeralPublicKeysAndSharedSecrets(
        sessionKey: PrivateKey,
        publicKeys: List<PublicKey>,
        ephemeralPublicKeys: List<PublicKey>,
        blindingFactors: List<ByteVector32>,
        sharedSecrets: List<ByteVector32>
    ): Pair<List<PublicKey>, List<ByteVector32>> {
        return if (publicKeys.isEmpty())
            Pair(ephemeralPublicKeys, sharedSecrets)
        else {
            val ephemeralPublicKey = blind(ephemeralPublicKeys.last(), blindingFactors.last())
            val secret = computeSharedSecret(blind(publicKeys.first(), blindingFactors), sessionKey)
            val blindingFactor = computeBlindingFactor(ephemeralPublicKey, secret)
            computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys.drop(1), ephemeralPublicKeys + ephemeralPublicKey, blindingFactors + blindingFactor, sharedSecrets + secret)
        }
    }

    /**
     * The 1.1 BOLT spec changed the onion frame format to use variable-length per-hop payloads.
     * The first bytes contain a varint encoding the length of the payload data (not including the trailing mac).
     * That varint is considered to be part of the payload, so the payload length includes the number of bytes used by
     * the varint prefix.
     *
     * @param payload payload buffer, which starts with our own cleartext payload followed by the rest of the encrypted onion payload .
     * our payload start with an encoded size that matches the TLV length format
     * @return the size of our payload
     */
    private fun decodePayloadLength(payload: ByteArray): Int {
        val input = ByteArrayInput(payload)
        val size = LightningCodecs.bigSize(input)
        val sizeLength = payload.size - input.availableBytes
        return size.toInt() + sizeLength
    }

    /**
     * Peek at the first bytes of the per-hop payload to extract its length.
     */
    fun peekPayloadLength(payload: ByteArray): Int {
        return when (payload.first()) {
            0.toByte() ->
                // The 1.0 BOLT spec used 65-bytes frames inside the onion payload.
                // The first byte of the frame (called `realm`) is set to 0x00, followed by 32 bytes of per-hop data, followed by a 32-bytes mac.
                65
            else ->
                // The 1.1 BOLT spec changed the frame format to use variable-length per-hop payloads.
                // The first bytes contain a varint encoding the length of the payload data (not including the trailing mac).
                // Since messages are always smaller than 65535 bytes, this varint will either be 1 or 3 bytes long.
                MacLength + decodePayloadLength(payload)
        }
    }

    /**
     * Generate a deterministic filler to prevent intermediate nodes from knowing their position in the route.
     * See https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#filler-generation
     *
     * @param keyType       type of key used (depends on the onion we're building).
     * @param sharedSecrets shared secrets for all the hops.
     * @param payloads      payloads for all the hops.
     * @param packetLength  length of the onion-encrypted payload (1300 for payment onions, 400 for trampoline onions).
     * @return filler bytes.
     */
    fun generateFiller(keyType: String, sharedSecrets: List<ByteVector32>, payloads: List<ByteArray>, packetLength: Int): ByteArray {
        require(sharedSecrets.size == payloads.size) { "the number of secrets should equal the number of payloads" }

        return (sharedSecrets zip payloads).fold(ByteArray(0)) { padding, secretAndPayload ->
            val (secret, perHopPayload) = secretAndPayload
            val perHopPayloadLength = peekPayloadLength(perHopPayload)
            require(perHopPayloadLength == perHopPayload.size + MacLength) { "invalid payload: length isn't correctly encoded: $perHopPayload" }
            val key = generateKey(keyType, secret)
            val padding1 = padding + ByteArray(perHopPayloadLength)
            val stream = generateStream(key, packetLength + perHopPayloadLength).takeLast(padding1.size).toByteArray()
            padding1.xor(stream)
        }
    }

    /**
     * Decrypt the incoming packet, extract the per-hop payload and build the packet for the next node.
     *
     * @param privateKey     this node's private key.
     * @param associatedData associated data.
     * @param packet         packet received by this node.
     * @param packetLength   length of the onion-encrypted payload (1300 for payment onions, 400 for trampoline onions).
     * @return a DecryptedPacket(payload, packet, shared secret) object where:
     *         - payload is the per-hop payload for this node.
     *         - packet is the next packet, to be forwarded using the info that is given in the payload.
     *         - shared secret is the secret we share with the node that sent the packet. We need it to propagate
     *         failure messages upstream.
     *         or a BadOnion error containing the hash of the invalid onion.
     */
    fun peel(privateKey: PrivateKey, associatedData: ByteVector, packet: OnionRoutingPacket, packetLength: Int): Either<FailureMessage, DecryptedPacket> = when (packet.version) {
        0 -> {
            when (val result = runTrying { PublicKey(packet.publicKey) }) {
                is Try.Success -> {
                    val packetEphKey = result.result
                    val sharedSecret = computeSharedSecret(packetEphKey, privateKey)
                    val mu = generateKey("mu", sharedSecret)
                    val check = mac(mu, packet.payload + associatedData)
                    if (check == packet.hmac) {
                        val rho = generateKey("rho", sharedSecret)
                        // Since we don't know the length of the per-hop payload (we will learn it once we decode the first bytes),
                        // we have to pessimistically generate a long cipher stream.
                        val stream = generateStream(rho, 2 * packetLength)
                        val bin = (packet.payload.toByteArray() + ByteArray(packetLength)) xor stream

                        val perHopPayloadLength = peekPayloadLength(bin)
                        val perHopPayload = bin.take(perHopPayloadLength - MacLength).toByteArray().toByteVector()

                        val hmac = ByteVector32(bin.slice(perHopPayloadLength - MacLength..perHopPayloadLength).toByteArray())
                        val nextOnionPayload = bin.drop(perHopPayloadLength).take(packetLength).toByteArray().toByteVector()
                        val nextPubKey = blind(packetEphKey, computeBlindingFactor(packetEphKey, sharedSecret))

                        Either.Right(DecryptedPacket(perHopPayload, OnionRoutingPacket(0, nextPubKey.value, nextOnionPayload, hmac), sharedSecret))
                    } else {
                        Either.Left(InvalidOnionHmac(hash(packet)))
                    }
                }
                else -> Either.Left(InvalidOnionKey(hash(packet)))
            }
        }
        else -> Either.Left(InvalidOnionVersion(hash(packet)))
    }

    /**
     * Wrap the given packet in an additional layer of onion encryption, adding an encrypted payload for a specific
     * node.
     *
     * Packets are constructed in reverse order:
     * - you first create the packet for the final recipient
     * - then you call wrap(...) until you've built the final onion packet that will be sent to the first node in the
     * route
     *
     * @param payload            per-hop payload for the target node.
     * @param associatedData     associated data.
     * @param ephemeralPublicKey ephemeral key shared with the target node.
     * @param sharedSecret       shared secret with this hop.
     * @param packet             current packet or random bytes if the packet hasn't been initialized.
     * @param packetLength       length of the onion-encrypted payload (1300 for payment onions, 400 for trampoline onions).
     * @param onionPayloadFiller optional onion payload filler, needed only when you're constructing the last packet.
     * @return the next packet.
     */
    private fun wrap(
        payload: ByteArray,
        associatedData: ByteVector32,
        ephemeralPublicKey: PublicKey,
        sharedSecret: ByteVector32,
        packet: Either<ByteVector, OnionRoutingPacket>,
        packetLength: Int,
        onionPayloadFiller: ByteVector = ByteVector.empty
    ): OnionRoutingPacket {
        require(payload.size <= packetLength - MacLength) { "packet payload cannot exceed ${packetLength - MacLength} bytes" }

        val (currentMac, currentPayload) = when (packet) {
            // Packet construction starts with an empty mac and random payload.
            is Either.Left -> {
                require(packet.value.size() == packetLength) { "invalid initial random bytes length" }
                Pair(ByteVector32.Zeroes, packet.value)

            }
            is Either.Right -> Pair(packet.value.hmac, packet.value.payload)
        }

        val nextOnionPayload = run {
            val onionPayload1 = payload.toByteVector() + currentMac + currentPayload.dropRight(payload.size + MacLength)
            val onionPayload2 = onionPayload1.toByteArray() xor generateStream(generateKey("rho", sharedSecret), packetLength)
            onionPayload2.dropLast(onionPayloadFiller.size()).toByteArray() + onionPayloadFiller.toByteArray()
        }

        val nextHmac = mac(generateKey("mu", sharedSecret), nextOnionPayload.toByteVector() + associatedData)
        return OnionRoutingPacket(0, ephemeralPublicKey.value, nextOnionPayload.toByteVector(), nextHmac)
    }

    /**
     * Create an encrypted onion packet that contains payloads for all nodes in the list.
     *
     * @param sessionKey     session key.
     * @param publicKeys     node public keys (one per node).
     * @param payloads       payloads (one per node).
     * @param associatedData associated data.
     * @param packetLength   length of the onion-encrypted payload (1300 for payment onions, 400 for trampoline onions).
     * @return An onion packet with all shared secrets. The onion packet can be sent to the first node in the list, and
     *         the shared secrets (one per node) can be used to parse returned failure messages if needed.
     */
    fun create(sessionKey: PrivateKey, publicKeys: List<PublicKey>, payloads: List<ByteArray>, associatedData: ByteVector32, packetLength: Int): Try<PacketAndSecrets> = runTrying {
        require(payloads.sumOf { it.size + MacLength } <= packetLength) { "packet per-hop payloads cannot exceed $packetLength bytes" }

        val (ephemeralPublicKeys, sharedsecrets) = computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys)
        val filler = generateFiller("rho", sharedsecrets.dropLast(1), payloads.dropLast(1), packetLength)

        // We deterministically-derive the initial payload bytes: see https://github.com/lightningnetwork/lightning-rfc/pull/697
        val startingBytes = generateStream(generateKey("pad", sessionKey.value), packetLength)
        val lastPacket = wrap(payloads.last(), associatedData, ephemeralPublicKeys.last(), sharedsecrets.last(), Either.Left(startingBytes.toByteVector()), packetLength, filler.toByteVector())

        tailrec fun loop(hopPayloads: List<ByteArray>, ephKeys: List<PublicKey>, sharedSecrets: List<ByteVector32>, packet: OnionRoutingPacket): OnionRoutingPacket {
            return if (hopPayloads.isEmpty()) packet else {
                val nextPacket = wrap(hopPayloads.last(), associatedData, ephKeys.last(), sharedSecrets.last(), Either.Right(packet), packetLength)
                loop(hopPayloads.dropLast(1), ephKeys.dropLast(1), sharedSecrets.dropLast(1), nextPacket)
            }
        }

        val packet = loop(payloads.dropLast(1), ephemeralPublicKeys.dropLast(1), sharedsecrets.dropLast(1), lastPacket)
        PacketAndSecrets(packet, SharedSecrets(sharedsecrets.zip(publicKeys)))
    }
}

/**
 * A properly decrypted failure from a node in the route.
 *
 * @param originNode     public key of the node that generated the failure.
 * @param failureMessage friendly failure message.
 */
data class DecryptedFailurePacket(val originNode: PublicKey, val failureMessage: FailureMessage)

/**
 * An onion-encrypted failure packet from an intermediate node:
 * +----------------+----------------------------------+-----------------+----------------------+-----+
 * | HMAC(32 bytes) | failure message length (2 bytes) | failure message | pad length (2 bytes) | pad |
 * +----------------+----------------------------------+-----------------+----------------------+-----+
 * with failure message length + pad length = 256
 */
@ThreadLocal
object FailurePacket {

    private val logger by lightningLogger()

    private const val MaxPayloadLength = 256
    private const val PacketLength = Sphinx.MacLength + MaxPayloadLength + 2 + 2

    fun encode(failure: FailureMessage, macKey: ByteVector32): ByteArray {
        val out = ByteArrayOutput()
        val failureMessageBin = FailureMessage.encode(failure)
        require(failureMessageBin.size <= MaxPayloadLength) { "encoded failure message overflows onion" }
        LightningCodecs.writeU16(failureMessageBin.size, out)
        LightningCodecs.writeBytes(failureMessageBin, out)
        val padLen = MaxPayloadLength - failureMessageBin.size
        LightningCodecs.writeU16(padLen, out)
        LightningCodecs.writeBytes(ByteArray(padLen), out)
        val packet = out.toByteArray()
        return Sphinx.mac(macKey.toByteArray(), packet).toByteArray() + packet
    }

    fun decode(input: ByteArray, macKey: ByteVector32): Try<FailureMessage> {
        if (input.size != PacketLength) {
            return Try.Failure(IllegalArgumentException("invalid error packet length: ${Hex.encode(input)}"))
        }
        val mac = input.take(32).toByteArray().toByteVector32()
        val payload = input.drop(32).toByteArray()
        if (Sphinx.mac(macKey.toByteArray(), payload) != mac) {
            return Try.Failure(IllegalArgumentException("invalid error packet mac: ${Hex.encode(input)}"))
        }
        val stream = ByteArrayInput(payload)
        return runTrying { FailureMessage.decode(LightningCodecs.bytes(stream, LightningCodecs.u16(stream))) }
    }

    /**
     * Create a failure packet that will be returned to the sender.
     * Each intermediate hop will add a layer of encryption and forward to the previous hop.
     * Note that malicious intermediate hops may drop the packet or alter it (which breaks the mac).
     *
     * @param sharedSecret destination node's shared secret that was computed when the original onion for the HTLC
     *                     was created or forwarded: see OnionPacket.create() and OnionPacket.wrap().
     * @param failure      failure message.
     * @return a failure packet that can be sent to the destination node.
     */
    fun create(sharedSecret: ByteVector32, failure: FailureMessage): ByteArray {
        val um = Sphinx.generateKey("um", sharedSecret)
        val packet = encode(failure, um)
        return wrap(packet, sharedSecret)
    }

    /**
     * Wrap the given packet in an additional layer of onion encryption for the previous hop.
     *
     * @param packet       failure packet.
     * @param sharedSecret destination node's shared secret.
     * @return an encrypted failure packet that can be sent to the destination node.
     */
    fun wrap(packet: ByteArray, sharedSecret: ByteVector32): ByteArray {
        if (packet.size != PacketLength) {
            logger.warning { "invalid error packet length ${packet.size}, must be $PacketLength (malicious or buggy downstream node)" }
        }
        val key = Sphinx.generateKey("ammag", sharedSecret)
        val stream = Sphinx.generateStream(key, PacketLength)
        // If we received a packet with an invalid length, we trim and pad to forward a packet with a normal length upstream.
        // This is a poor man's attempt at increasing the likelihood of the sender receiving the error.
        return packet.take(PacketLength).toByteArray().leftPaddedCopyOf(PacketLength) xor stream
    }

    /**
     * Decrypt a failure packet. Node shared secrets are applied until the packet's MAC becomes valid, which means that
     * it was sent by the corresponding node.
     * Note that malicious nodes in the route may have altered the packet, triggering a decryption failure.
     *
     * @param packet        failure packet.
     * @param sharedSecrets nodes shared secrets.
     * @return Success(secret, failure message) if the origin of the packet could be identified and the packet
     *         decrypted, Failure otherwise.
     */
    fun decrypt(packet: ByteArray, sharedSecrets: SharedSecrets): Try<DecryptedFailurePacket> {
        require(packet.size == PacketLength) { "invalid error packet length ${packet.size}, must be $PacketLength" }

        fun loop(packet: ByteArray, secrets: List<Pair<ByteVector32, PublicKey>>): Try<DecryptedFailurePacket> {
            return if (secrets.isEmpty()) {
                val ex = IllegalArgumentException("couldn't parse error packet=$packet with sharedSecrets=$secrets")
                logger.warning(ex)
                Try.Failure(ex)
            } else {
                val (secret, pubkey) = secrets.first()
                val packet1 = wrap(packet, secret)
                val um = Sphinx.generateKey("um", secret)
                when (val error = decode(packet1, um)) {
                    is Try.Failure -> loop(packet1, secrets.tail())
                    is Try.Success -> Try.Success(DecryptedFailurePacket(pubkey, error.result))
                }
            }
        }

        return loop(packet, sharedSecrets.perHopSecrets)
    }
}
