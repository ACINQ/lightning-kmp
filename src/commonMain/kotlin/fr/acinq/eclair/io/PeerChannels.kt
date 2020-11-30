package fr.acinq.eclair.io

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel.LocalParams

object PeerChannels {
    fun makeChannelParams(nodeParams: NodeParams, defaultFinalScriptPubkey: ByteVector, localPaymentBasepoint: PublicKey, isFunder: Boolean, fundingAmount: Satoshi): LocalParams {
        // we make sure that funder and fundee key path end differently
        val fundingKeyPath = nodeParams.keyManager.newFundingKeyPath(isFunder)
        return makeChannelParams(nodeParams, defaultFinalScriptPubkey, localPaymentBasepoint, isFunder, fundingAmount, fundingKeyPath)
    }

    fun makeChannelParams(nodeParams: NodeParams, defaultFinalScriptPubkey: ByteVector, localPaymentBasepoint: PublicKey, isFunder: Boolean, fundingAmount: Satoshi, fundingKeyPath: KeyPath): LocalParams {
        return LocalParams(
            nodeParams.nodeId,
            fundingKeyPath,
            dustLimit = nodeParams.dustLimit,
            maxHtlcValueInFlightMsat = nodeParams.maxHtlcValueInFlightMsat,
            channelReserve = (fundingAmount * nodeParams.reserveToFundingRatio).max(nodeParams.dustLimit), // BOLT #2: make sure that our reserve is above our dust limit
            htlcMinimum = nodeParams.htlcMinimum,
            toSelfDelay = nodeParams.toRemoteDelayBlocks, // we choose their delay
            maxAcceptedHtlcs = nodeParams.maxAcceptedHtlcs,
            isFunder = isFunder,
            defaultFinalScriptPubKey = defaultFinalScriptPubkey,
            walletStaticPaymentBasepoint = localPaymentBasepoint,
            features = nodeParams.features
        )
    }
}