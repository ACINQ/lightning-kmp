package fr.acinq.lightning.io

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.KeyPath
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.channel.LocalParams

object PeerChannels {
    fun makeChannelParams(nodeParams: NodeParams, defaultFinalScriptPubkey: ByteVector, isInitiator: Boolean, maxHtlcValueInFlight: MilliSatoshi): LocalParams {
        // we make sure that initiator and non-initiator key path end differently
        val fundingKeyPath = nodeParams.keyManager.newFundingKeyPath(isInitiator)
        return makeChannelParams(nodeParams, defaultFinalScriptPubkey, isInitiator, maxHtlcValueInFlight, fundingKeyPath)
    }

    private fun makeChannelParams(nodeParams: NodeParams, defaultFinalScriptPubkey: ByteVector, isInitiator: Boolean, maxHtlcValueInFlight: MilliSatoshi, fundingKeyPath: KeyPath): LocalParams {
        return LocalParams(
            nodeParams.nodeId,
            fundingKeyPath,
            dustLimit = nodeParams.dustLimit,
            maxHtlcValueInFlightMsat = maxHtlcValueInFlight.toLong(),
            htlcMinimum = nodeParams.htlcMinimum,
            toSelfDelay = nodeParams.toRemoteDelayBlocks, // we choose their delay
            maxAcceptedHtlcs = nodeParams.maxAcceptedHtlcs,
            isInitiator = isInitiator,
            defaultFinalScriptPubKey = defaultFinalScriptPubkey,
            features = nodeParams.features
        )
    }
}