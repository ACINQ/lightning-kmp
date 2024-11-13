package fr.acinq.lightning.blockchain.fee

import fr.acinq.lightning.utils.sat

data class ConstantFeeEstimator(val currentFeerate: FeeratePerKw = FeeratePerKw(750.sat)) : FeeEstimator {
    override fun getFeerate(target: Int): FeeratePerKw = currentFeerate
}
