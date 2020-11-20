package fr.acinq.eclair.blockchain.fee

import fr.acinq.eclair.utils.sat

data class ConstantFeeProvider(val currentFeerates: FeeratePerKB = FeeratePerKB(3000.sat)) : FeeProvider {
    override fun getFeerates(): FeeratesPerKB = FeeratesPerKB(currentFeerates, currentFeerates, currentFeerates, currentFeerates, currentFeerates, currentFeerates, currentFeerates, currentFeerates)
}
