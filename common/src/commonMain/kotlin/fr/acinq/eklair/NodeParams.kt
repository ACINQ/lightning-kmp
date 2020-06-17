package fr.acinq.eklair

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.eklair.crypto.KeyManager
import fr.acinq.eklair.db.Databases

data class NodeParams(
    val chainHash: ByteVector32,
    val privateKey: PrivateKey,
    val keyManager: KeyManager,
    val db: Databases,
    val features: Features,
    val minDepthBlocks: Int,
    val expiryDeltaBlocks: CltvExpiryDelta,
    val maxToLocalDelayBlocks: CltvExpiryDelta,
    val feeBase: MilliSatoshi,
    val feeProportionalMillionth: Int,
    val reserveToFundingRatio: Double,
    val maxReserveToFundingRatio: Double,
    val currentBlockHeight: Long) // TODO: use an atomic reference to an external value
