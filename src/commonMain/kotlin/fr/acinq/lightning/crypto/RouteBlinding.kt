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
     * @param nodeId introduction node's id (which cannot be blinded since the sender need to find a route to it).
     * @param blindedPublicKey blinded public key, which hides the real public key.
     * @param blindingEphemeralKey blinding tweak that can be used by the receiving node to derive the private key that matches the blinded public key.
     * @param encryptedPayload encrypted payload that can be decrypted with the introduction node's private key and the blinding ephemeral key.
     */
    data class IntroductionNode(
        val nodeId: EncodedNodeId,
        val blindedPublicKey: PublicKey,
        val blindingEphemeralKey: PublicKey,
        val encryptedPayload: ByteVector
    )

    /**
     * @param blindedPublicKey blinded public key, which hides the real public key.
     * @param encryptedPayload encrypted payload that can be decrypted with the receiving node's private key and the blinding ephemeral key.
     */
    data class BlindedNode(val blindedPublicKey: PublicKey, val encryptedPayload: ByteVector)

    /**
     * @param introductionNodeId the first node, not blinded so that the sender can locate it.
     * @param blindingKey blinding tweak that can be used by the introduction node to derive the private key that matches the blinded public key.
     * @param blindedNodes blinded nodes (including the introduction node).
     */
    data class BlindedRoute(
        val introductionNodeId: EncodedNodeId,
        val blindingKey: PublicKey,
        val blindedNodes: List<BlindedNode>
    ) {
        val introductionNode: IntroductionNode = IntroductionNode(
            introductionNodeId,
            blindedNodes.first().blindedPublicKey,
            blindingKey,
            blindedNodes.first().encryptedPayload
        )
        val subsequentNodes: List<BlindedNode> = blindedNodes.drop(1)
        val blindedNodeIds: List<PublicKey> = blindedNodes.map { it.blindedPublicKey }
        val encryptedPayloads: List<ByteVector> = blindedNodes.map { it.encryptedPayload }
    }

    /**
     * @param route blinded route.
     * @param lastBlinding blinding point for the last node, which can be used to derive the blinded private key.
     */
    data class BlindedRouteDetails(val route: BlindedRoute, val lastBlinding: PublicKey) {
        /** @param nodeKey private key associated with our non-blinded node_id. */
        fun blindedPrivateKey(nodeKey: PrivateKey): PrivateKey = derivePrivateKey(nodeKey, lastBlinding)
    }

    /**
     * Blind the provided route and encrypt intermediate nodes' payloads.
     *
     * @param sessionKey this node's session key.
     * @param publicKeys public keys of each node on the route, starting from the introduction point.
     * @param payloads payloads that should be encrypted for each node on the route.
     * @return a blinded route.
     */
    fun create(sessionKey: PrivateKey, publicKeys: List<PublicKey>, payloads: List<ByteVector>): BlindedRouteDetails {
        require(publicKeys.size == payloads.size) { "a payload must be provided for each node in the blinded path" }
        var e = sessionKey
        val (blindedHops, blindingKeys) = publicKeys.zip(payloads).map { pair ->
            val (publicKey, payload) = pair
            val blindingKey = e.publicKey()
            val sharedSecret = Sphinx.computeSharedSecret(publicKey, e)
            val blindedPublicKey = Sphinx.blind(publicKey, Sphinx.generateKey("blinded_node_id", sharedSecret))
            val rho = Sphinx.generateKey("rho", sharedSecret)
            val (encryptedPayload, mac) = ChaCha20Poly1305.encrypt(
                rho.toByteArray(),
                Sphinx.zeroes(12),
                payload.toByteArray(),
                byteArrayOf()
            )
            e *= PrivateKey(Crypto.sha256(blindingKey.value.toByteArray() + sharedSecret.toByteArray()))
            Pair(BlindedNode(blindedPublicKey, ByteVector(encryptedPayload + mac)), blindingKey)
        }.unzip()
        return BlindedRouteDetails(BlindedRoute(EncodedNodeId(publicKeys.first()), blindingKeys.first(), blindedHops), blindingKeys.last())
    }

    /**
     * Compute the blinded private key that must be used to decrypt an incoming blinded onion.
     *
     * @param privateKey this node's private key.
     * @param blindingEphemeralKey unblinding ephemeral key.
     * @return this node's blinded private key.
     */
    fun derivePrivateKey(privateKey: PrivateKey, blindingEphemeralKey: PublicKey): PrivateKey {
        val sharedSecret = Sphinx.computeSharedSecret(blindingEphemeralKey, privateKey)
        return privateKey * PrivateKey(Sphinx.generateKey("blinded_node_id", sharedSecret))
    }

    /**
     * Decrypt the encrypted payload (usually found in the onion) that contains instructions to locate the next node.
     *
     * @param privateKey this node's private key.
     * @param blindingEphemeralKey unblinding ephemeral key.
     * @param encryptedPayload encrypted payload for this node.
     * @return a tuple (decrypted payload, unblinding ephemeral key for the next node)
     */
    fun decryptPayload(
        privateKey: PrivateKey,
        blindingEphemeralKey: PublicKey,
        encryptedPayload: ByteVector
    ): Either<InvalidTlvPayload, Pair<ByteVector, PublicKey>> {
        val sharedSecret = Sphinx.computeSharedSecret(blindingEphemeralKey, privateKey)
        val rho = Sphinx.generateKey("rho", sharedSecret)
        return try {
            val decrypted = ChaCha20Poly1305.decrypt(
                rho.toByteArray(),
                Sphinx.zeroes(12),
                encryptedPayload.dropRight(16).toByteArray(),
                byteArrayOf(),
                encryptedPayload.takeRight(16).toByteArray()
            )
            val nextBlindingEphemeralKey = Sphinx.blind(blindingEphemeralKey, Sphinx.computeBlindingFactor(blindingEphemeralKey, sharedSecret))
            Either.Right(Pair(ByteVector(decrypted), nextBlindingEphemeralKey))
        } catch (_: Throwable) {
            Either.Left(CannotDecodeTlv(OnionPaymentPayloadTlv.EncryptedRecipientData.tag))
        }
    }
}