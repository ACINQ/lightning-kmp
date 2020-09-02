package fr.acinq.eklair.blockchain.fee

import fr.acinq.eklair.Eclair.feerateKw2KB
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass


@Serializable
data class ConstantFeeEstimator(var currentFeerates: Long = 750) : FeeEstimator {


    override fun getFeeratePerKb(target: Int): Long = feerateKw2KB(currentFeerates)

    override fun getFeeratePerKw(target: Int): Long = currentFeerates

    companion object {
        val testSerializersModule = SerializersModule {
            polymorphic(FeeEstimator::class) {
                subclass(serializer())
            }
        }
    }
}
