package fr.acinq.eclair.crypto.sphinx

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.crypto.HMac
import fr.acinq.bitcoin.crypto.Sha256
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.eclair.crypto.ChaCha20
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import fr.acinq.eclair.wire.OnionRoutingPacket

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

data class PacketAndSecrets(val packet: OnionRoutingPacket, val sharedSecrets: List<Pair<ByteVector32, PublicKey>>)

sealed class OnionRoutingPacket<T : PacketType> {
    /**
     * Supported packet version. Note that since this value is outside of the onion encrypted payload, intermediate
     * nodes may or may not use this value when forwarding the packet to the next node.
     */
    val Version = 0

    /**
     * Length of the encrypted onion payload.
     */
    abstract val PayloadLength: Int
}

/**
 * see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md
 */
object Sphinx {
    // We use HMAC-SHA256 which returns 32-bytes message authentication codes.
    val MacLength = 32

    private val Generator = PrivateKey(ByteVector32("0000000000000000000000000000000000000000000000000000000000000001")).publicKey()

    fun mac(key: ByteArray, message: ByteArray): ByteVector32 = HMac.hmac(key, message, Sha256(), 64).toByteVector32()

    fun mac(key: ByteVector, message: ByteVector): ByteVector32 = mac(key.toByteArray(), message.toByteArray())

    fun generateKey(keyType: ByteArray, secret: ByteVector32): ByteVector32 = mac(keyType, secret.toByteArray())

    fun generateKey(keyType: String, secret: ByteVector32): ByteVector32 = generateKey(keyType.encodeToByteArray(), secret)

    fun zeroes(length: Int): ByteArray = ByteArray(length)

    fun generateStream(key: ByteVector32, length: Int): ByteArray = ChaCha20.encrypt(zeroes(length), key.toByteArray(), zeroes(12))

    fun computeSharedSecret(pub: PublicKey, secret: PrivateKey): ByteVector32 = Crypto.sha256(pub.times(secret).value).toByteVector32()

    fun computeBlindingFactor(pub: PublicKey, secret: ByteVector): ByteVector32 = Crypto.sha256(pub.value + secret).toByteVector32()

    fun blind(pub: PublicKey, blindingFactor: ByteVector32): PublicKey = pub.times(PrivateKey(blindingFactor))

    fun blind(pub: PublicKey, blindingFactors: List<ByteVector32>): PublicKey = blindingFactors.fold(pub){ a, b -> blind(a, b)}

    /**
     * When an invalid onion is received, its hash should be included in the failure message.
     */
    fun hash(onion: OnionRoutingPacket): ByteVector32 = Crypto.sha256(OnionRoutingPacketSerializer(1300).write(onion)).toByteVector32()

    /**
     * Compute the ephemeral public keys and shared secrets for all nodes on the route.
     *
     * @param sessionKey this node's session key.
     * @param publicKeys public keys of each node on the route.
     * @return a tuple (ephemeral public keys, shared secrets).
     */
    fun computeEphemeralPublicKeysAndSharedSecrets(sessionKey: PrivateKey, publicKeys: List<PublicKey>): Pair<List<PublicKey>, List<ByteVector32>> {
        val ephemeralPublicKey0 = blind(Generator, sessionKey.value)
        val secret0 = computeSharedSecret(publicKeys.first(), sessionKey)
        val blindingFactor0 = computeBlindingFactor(ephemeralPublicKey0, secret0)
        return computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys.drop(1), listOf(ephemeralPublicKey0), listOf(blindingFactor0), listOf(secret0))
    }

    private tailrec fun computeEphemeralPublicKeysAndSharedSecrets(sessionKey: PrivateKey, publicKeys: List<PublicKey>, ephemeralPublicKeys: List<PublicKey>, blindingFactors: List<ByteVector32>, sharedSecrets: List<ByteVector32>): Pair<List<PublicKey>, List<ByteVector32>> {
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
    fun decodePayloadLength(payload: ByteArray): Int {
        val input = ByteArrayInput(payload)
        val size = LightningSerializer.bigSize(input)
        val sizeLength = payload.size - input.availableBytes
        return size.toInt() + sizeLength
    }

    /**
     * Peek at the first bytes of the per-hop payload to extract its length.
     */
    fun peekPayloadLength(payload: ByteArray): Int {
        return when(payload.first()) {
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
     * @return filler bytes.
     */
    fun generateFiller(keyType: String, sharedSecrets: List<ByteVector32>, payloads: List<ByteArray>, payloadLength: Int): ByteArray {
        require(sharedSecrets.size == payloads.size){ "the number of secrets should equal the number of payloads" }

        return (sharedSecrets zip payloads).fold(ByteArray(0)) { padding, secretAndPayload ->
            val (secret, perHopPayload) = secretAndPayload
            val perHopPayloadLength = peekPayloadLength(perHopPayload)
            require(perHopPayloadLength == perHopPayload.size + MacLength){ "invalid payload: length isn't correctly encoded: $perHopPayload" }
            val key = generateKey(keyType, secret)
            val padding1 = padding + ByteArray(perHopPayloadLength)
            val stream = generateStream(key, payloadLength + perHopPayloadLength).takeLast(padding1.size).toByteArray()
            padding1.xor(stream)
        }
    }

    /**
     * Decrypt the incoming packet, extract the per-hop payload and build the packet for the next node.
     *
     * @param privateKey     this node's private key.
     * @param associatedData associated data.
     * @param packet         packet received by this node.
     * @return a DecryptedPacket(payload, packet, shared secret) object where:
     *         - payload is the per-hop payload for this node.
     *         - packet is the next packet, to be forwarded using the info that is given in the payload.
     *         - shared secret is the secret we share with the node that sent the packet. We need it to propagate
     *         failure messages upstream.
     *         or a BadOnion error containing the hash of the invalid onion.
     */
    fun peel(privateKey: PrivateKey, associatedData: ByteVector, packet: OnionRoutingPacket, PayloadLength: Int): Either<BadOnion, DecryptedPacket> = when(packet.version) {
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
                        val stream = generateStream(rho, 2 * PayloadLength)
                        val bin = (packet.payload.toByteArray() + ByteArray(PayloadLength)) xor stream

                        val perHopPayloadLength = peekPayloadLength(bin)
                        val perHopPayload = bin.take(perHopPayloadLength - MacLength).toByteArray().toByteVector()

                        val hmac = ByteVector32(bin.slice(perHopPayloadLength - MacLength..perHopPayloadLength).toByteArray())
                        val nextOnionPayload = bin.drop(perHopPayloadLength).take(PayloadLength).toByteArray().toByteVector()
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
     * @param onionPayloadFiller optional onion payload filler, needed only when you're constructing the last packet.
     * @return the next packet.
     */
    fun wrap(payload: ByteArray, associatedData: ByteVector32, ephemeralPublicKey: PublicKey, sharedSecret: ByteVector32, packet: Either<ByteVector, OnionRoutingPacket>, PayloadLength: Int, onionPayloadFiller: ByteVector = ByteVector.empty): OnionRoutingPacket {
        require(payload.size <= PayloadLength - MacLength){"packet payload cannot exceed ${PayloadLength - MacLength} bytes"}

        val (currentMac, currentPayload) = when (packet) {
            // Packet construction starts with an empty mac and random payload.
            is Either.Left -> {
                require(packet.value.size() == PayloadLength){ "invalid initial random bytes length" }
                Pair(ByteVector32.Zeroes, packet.value)

            }
            is Either.Right -> Pair(packet.value.hmac, packet.value.payload)
        }

        val nextOnionPayload = run {
            val onionPayload1 = payload.toByteVector() + currentMac + currentPayload.dropRight(payload.size + MacLength)
            val onionPayload2 = onionPayload1.toByteArray() xor generateStream(generateKey("rho", sharedSecret), PayloadLength)
            onionPayload2.dropLast(onionPayloadFiller.size()).toByteArray() + onionPayloadFiller.toByteArray()
        }

        val nextHmac = mac(generateKey("mu", sharedSecret), nextOnionPayload.toByteVector() + associatedData)
        val nextPacket = OnionRoutingPacket(0, ephemeralPublicKey.value, nextOnionPayload.toByteVector(), nextHmac)
        return nextPacket
    }

    /**
     * Create an encrypted onion packet that contains payloads for all nodes in the list.
     *
     * @param sessionKey     session key.
     * @param publicKeys     node public keys (one per node).
     * @param payloads       payloads (one per node).
     * @param associatedData associated data.
     * @return An onion packet with all shared secrets. The onion packet can be sent to the first node in the list, and
     *         the shared secrets (one per node) can be used to parse returned failure messages if needed.
     */
    fun create(sessionKey: PrivateKey, publicKeys: List<PublicKey>, payloads: List<ByteArray>, associatedData: ByteVector32, PayloadLength: Int): PacketAndSecrets {
        val (ephemeralPublicKeys, sharedsecrets) = computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys)
        val filler = generateFiller("rho", sharedsecrets.dropLast(1), payloads.dropLast(1), PayloadLength)

        // We deterministically-derive the initial payload bytes: see https://github.com/lightningnetwork/lightning-rfc/pull/697
        val startingBytes = generateStream(generateKey("pad", sessionKey.value), PayloadLength)
        val lastPacket = wrap(payloads.last(), associatedData, ephemeralPublicKeys.last(), sharedsecrets.last(), Either.Left(startingBytes.toByteVector()), PayloadLength, filler.toByteVector())

        tailrec fun loop(hopPayloads: List<ByteArray>, ephKeys: List<PublicKey>, sharedSecrets: List<ByteVector32>, packet:OnionRoutingPacket): OnionRoutingPacket {
            return if (hopPayloads.isEmpty()) packet else {
                val nextPacket = wrap(hopPayloads.last(), associatedData, ephKeys.last(), sharedSecrets.last(), Either.Right(packet), PayloadLength)
                loop(hopPayloads.dropLast(1), ephKeys.dropLast(1), sharedSecrets.dropLast(1), nextPacket)
            }
        }

        val packet = loop(payloads.dropLast(1), ephemeralPublicKeys.dropLast(1), sharedsecrets.dropLast(1), lastPacket)
        return PacketAndSecrets(packet, sharedsecrets.zip(publicKeys))
    }
}

/**
 * A properly decrypted failure from a node in the route.
 *
 * @param originNode     public key of the node that generated the failure.
 * @param failureMessage friendly failure message.
 */
data class DecryptedFailurePacket(val originNode: PublicKey, val failureMessage: FailureMessage)

object FailurePacket {

    val MaxPayloadLength = 256
    val PacketLength = Sphinx.MacLength + MaxPayloadLength + 2 + 2

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
    fun create(sharedSecret: ByteVector32, @Suppress("UNUSED_PARAMETER") failure: FailureMessage): ByteArray {
//        val um = Sphinx.generateKey("um", sharedSecret)
        val packet = ByteArray(PacketLength)// TODO: implement FailureMessage serialization FailureMessageCodecs.failureOnionCodec(Hmac256(um)).encode(failure).require.toByteVector
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
            // TODO logger.warn(s"invalid error packet length ${packet.length}, must be $PacketLength (malicious or buggy downstream node)")
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
    fun decrypt(packet: ByteArray, sharedSecrets: List<Pair<ByteVector32, PublicKey>>): DecryptedFailurePacket {
        require(packet.size == PacketLength){ "invalid error packet length ${packet.size}, must be $PacketLength" }

        // TODO: implement this properly
        fun loop(packet: ByteArray, secrets: List<Pair<ByteVector32, PublicKey>>): DecryptedFailurePacket {
            if (secrets.isEmpty())
                throw IllegalArgumentException("couldn't parse error packet=$packet with sharedSecrets=$secrets")
            else {
                val (_, pubkey) = secrets.first()
//                val packet1 = wrap(packet, secret)
//                val um = Sphinx.generateKey("um", secret)
                return DecryptedFailurePacket(pubkey, InvalidRealm)
            }
        }

        return loop(packet, sharedSecrets)
    }
}
