package fr.acinq.eclair.blockchain.fee

import fr.acinq.eclair.utils.sat

data class ConstantFeeEstimator(val currentFeerate: FeeratePerKw = FeeratePerKw(750.sat)) : FeeEstimator {
    override fun getFeerate(target: Int): FeeratePerKw = currentFeerate
}
