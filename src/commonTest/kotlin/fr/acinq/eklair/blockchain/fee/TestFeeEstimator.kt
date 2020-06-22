package fr.acinq.eklair.blockchain.fee

import fr.acinq.eklair.Eclair.feerateKw2KB
import fr.acinq.eklair.TestConstants.feeratePerKw


class TestFeeEstimator() : FeeEstimator {
    private var currentFeerates: Long = 750

    override fun getFeeratePerKb(target: Int): Long = feerateKw2KB(currentFeerates)

    override fun getFeeratePerKw(target: Int): Long = currentFeerates

    fun setFeerate(feeratesPerKw: Long): FeeEstimator {
        currentFeerates = feeratesPerKw
        return this
    }
}