package fr.acinq.eclair.blockchain.fee

import kotlinx.serialization.Serializable

interface FeeEstimator {

    fun getFeeratePerKb(target: Int): Long

    fun getFeeratePerKw(target: Int): Long

}

@Serializable
data class OnchainFeerates(val fundingFeeratePerKw: Long, val commitmentFeeratePerKw: Long, val mutualCloseFeeratePerKw: Long, val claimMainFeeratePerKw: Long, val fastFeeratePerKw: Long)

@Serializable
data class OnChainFeeConf(val maxFeerateMismatch: Double, val closeOnOfflineMismatch: Boolean, val updateFeeMinDiffRatio: Double)
