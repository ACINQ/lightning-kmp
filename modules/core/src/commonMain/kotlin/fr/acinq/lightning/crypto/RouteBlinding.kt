package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.EncodedNodeId
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.wire.CannotDecodeTlv
import fr.acinq.lightning.wire.InvalidTlvPayload
import fr.acinq.lightning.wire.OnionPaymentPayloadTlv

object RouteBlinding {

    /**
     * @param nodeId first node's id (which cannot be blinded since the sender need to find a route to it).
     * @param blindedPublicKey blinded public key, which hides the real public key.
     * @param pathKey blinding tweak that can be used by the receiving node to derive the private key that matches the blinded public key.
     * @param encryptedPayload encrypted payload that can be decrypted with the introduction node's private key and the path key.
     */
    data class FirstHop(
        val nodeId: EncodedNodeId,
        val blindedPublicKey: PublicKey,
        val pathKey: PublicKey,
        val encryptedPayload: ByteVector
    )

    /**
     * @param blindedPublicKey blinded public key, which hides the real public key.
     * @param encryptedPayload encrypted payload that can be decrypted with the receiving node's private key and the path key.
     */
    data class BlindedHop(val blindedPublicKey: PublicKey, val encryptedPayload: ByteVector)

    /**
     * @param firstNodeId the first node, not blinded so that the sender can locate it.
     * @param firstPathKey blinding tweak that can be used by the introduction node to derive the private key that matches the blinded public key.
     * @param blindedHops blinded nodes (including the introduction node).
     */
    data class BlindedRoute(
        val firstNodeId: EncodedNodeId,
        val firstPathKey: PublicKey,
        val blindedHops: List<BlindedHop>
    ) {
        val firstHop: FirstHop = FirstHop(
            firstNodeId,
            blindedHops.first().blindedPublicKey,
            firstPathKey,
            blindedHops.first().encryptedPayload
        )
        val subsequentHops: List<BlindedHop> = blindedHops.drop(1)
        val blindedNodeIds: List<PublicKey> = blindedHops.map { it.blindedPublicKey }
        val encryptedPayloads: List<ByteVector> = blindedHops.map { it.encryptedPayload }
    }

    /**
     * @param route blinded route.
     * @param lastPathKey path key for the last node, which can be used to derive the blinded private key.
     */
    data class BlindedRouteDetails(val route: BlindedRoute, val lastPathKey: PublicKey) {
        /** @param nodeKey private key associated with our non-blinded node_id. */
        fun blindedPrivateKey(nodeKey: PrivateKey): PrivateKey = derivePrivateKey(nodeKey, lastPathKey)
    }

    /**
     * Blind the provided route and encrypt intermediate nodes' payloads.
     *
     * @param sessionKey session key of the blinded path, the corresponding public key will be the first path key.
     * @param publicKeys public keys of each node on the route, starting from the introduction point.
     * @param payloads payloads that should be encrypted for each node on the route.
     * @param allowCompactFormat when true, allows the encryptedData to be empty for the final recipient
     * @return a blinded route.
     */
    fun create(sessionKey: PrivateKey, publicKeys: List<PublicKey>, payloads: List<ByteVector>, allowCompactFormat: Boolean = false): BlindedRouteDetails {
        require(publicKeys.size == payloads.size) { "a payload must be provided for each node in the blinded path" }
        var e = sessionKey
        val (blindedHops, pathKeys) = publicKeys.zip(payloads).map { pair ->
            val (publicKey, payload) = pair
            val pathKey = e.publicKey()
            val sharedSecret = Sphinx.computeSharedSecret(publicKey, e)
            val blindedPublicKey = Sphinx.blind(publicKey, Sphinx.generateKey("blinded_node_id", sharedSecret))
            e *= PrivateKey(Crypto.sha256(pathKey.value.toByteArray() + sharedSecret.toByteArray()))
            if (payload.isEmpty() && allowCompactFormat) {
                Pair(BlindedHop(blindedPublicKey, payload), pathKey)
            } else {
                val rho = Sphinx.generateKey("rho", sharedSecret)
                val (encryptedPayload, mac) = ChaCha20Poly1305.encrypt(
                    rho.toByteArray(),
                    Sphinx.zeroes(12),
                    payload.toByteArray(),
                    byteArrayOf()
                )
                Pair(BlindedHop(blindedPublicKey, ByteVector(encryptedPayload + mac)), pathKey)
            }
        }.unzip()
        return BlindedRouteDetails(BlindedRoute(EncodedNodeId(publicKeys.first()), pathKeys.first(), blindedHops), pathKeys.last())
    }

    /**
     * Compute the blinded private key that must be used to decrypt an incoming blinded onion.
     *
     * @param privateKey this node's private key.
     * @param pathKey unblinding ephemeral key.
     * @return this node's blinded private key.
     */
    fun derivePrivateKey(privateKey: PrivateKey, pathKey: PublicKey): PrivateKey {
        val sharedSecret = Sphinx.computeSharedSecret(pathKey, privateKey)
        return privateKey * PrivateKey(Sphinx.generateKey("blinded_node_id", sharedSecret))
    }

    /**
     * Decrypt the encrypted payload (usually found in the onion) that contains instructions to locate the next node.
     *
     * @param privateKey this node's private key.
     * @param pathKey unblinding ephemeral key.
     * @param encryptedPayload encrypted payload for this node.
     * @return a tuple (decrypted payload, unblinding ephemeral key for the next node)
     */
    fun decryptPayload(
        privateKey: PrivateKey,
        pathKey: PublicKey,
        encryptedPayload: ByteVector
    ): Either<InvalidTlvPayload, Pair<ByteVector, PublicKey>> {
        val sharedSecret = Sphinx.computeSharedSecret(pathKey, privateKey)
        val nextPathKey = Sphinx.blind(pathKey, Sphinx.computeBlindingFactor(pathKey, sharedSecret))
        if (encryptedPayload.isEmpty()) {
            return Either.Right(Pair(encryptedPayload, nextPathKey))
        } else {
            return try {
                val rho = Sphinx.generateKey("rho", sharedSecret)
                val decrypted = ChaCha20Poly1305.decrypt(
                    rho.toByteArray(),
                    Sphinx.zeroes(12),
                    encryptedPayload.dropRight(16).toByteArray(),
                    byteArrayOf(),
                    encryptedPayload.takeRight(16).toByteArray()
                )
                Either.Right(Pair(ByteVector(decrypted), nextPathKey))
            } catch (_: Throwable) {
                Either.Left(CannotDecodeTlv(OnionPaymentPayloadTlv.EncryptedRecipientData.tag))
            }
        }
    }
}