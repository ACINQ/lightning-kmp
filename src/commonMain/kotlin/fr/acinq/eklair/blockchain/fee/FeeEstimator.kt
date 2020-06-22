package fr.acinq.eklair.blockchain.fee

interface FeeEstimator {

    fun getFeeratePerKb(target: Int) : Long

    fun getFeeratePerKw(target: Int) : Long

}

data class FeeTargets(val fundingBlockTarget: Int, val commitmentBlockTarget: Int, val mutualCloseBlockTarget: Int, val claimMainBlockTarget: Int)

data class OnChainFeeConf(val feeTargets: FeeTargets, val feeEstimator: FeeEstimator, val maxFeerateMismatch: Double, val closeOnOfflineMismatch: Boolean, val updateFeeMinDiffRatio: Double)
