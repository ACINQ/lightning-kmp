package fr.acinq.eklair.blockchain.fee

import kotlinx.serialization.Serializable

interface FeeEstimator {

    fun getFeeratePerKb(target: Int) : Long

    fun getFeeratePerKw(target: Int) : Long

}

@Serializable
data class FeeTargets(val fundingBlockTarget: Int, val commitmentBlockTarget: Int, val mutualCloseBlockTarget: Int, val claimMainBlockTarget: Int)

@Serializable
data class OnChainFeeConf(val feeTargets: FeeTargets, val feeEstimator: FeeEstimator, val maxFeerateMismatch: Double, val closeOnOfflineMismatch: Boolean, val updateFeeMinDiffRatio: Double)
