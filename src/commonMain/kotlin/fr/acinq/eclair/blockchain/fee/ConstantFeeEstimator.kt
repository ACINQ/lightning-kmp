package fr.acinq.eclair.blockchain.fee

import fr.acinq.eclair.Eclair.feerateKw2KB

data class ConstantFeeEstimator(var currentFeerates: Long = 750) : FeeEstimator {
    override fun getFeeratePerKb(target: Int): Long = feerateKw2KB(currentFeerates)
    override fun getFeeratePerKw(target: Int): Long = currentFeerates
}
