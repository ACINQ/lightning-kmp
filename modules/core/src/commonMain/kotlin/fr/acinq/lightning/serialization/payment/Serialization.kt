package fr.acinq.lightning.serialization.payment

import fr.acinq.lightning.db.WalletPayment

object Serialization {

    fun serialize(payment: WalletPayment): ByteArray {
        return fr.acinq.lightning.serialization.payment.v1.Serialization.serialize(payment)
    }

    fun deserialize(bin: ByteArray): DeserializationResult {
        return when (bin.firstOrNull()?.toInt()) {
            1 -> DeserializationResult.Success(fr.acinq.lightning.serialization.payment.v1.Deserialization.deserialize(bin))
            else -> DeserializationResult.UnknownVersion(bin[0].toInt())
        }
    }

    sealed class DeserializationResult {
        data class Success(val data: WalletPayment) : DeserializationResult()
        data class UnknownVersion(val version: Int) : DeserializationResult()

        fun get(): WalletPayment = when(this) {
            is Success -> data
            is UnknownVersion -> error("unknown version ${this.version}")
        }
    }

}