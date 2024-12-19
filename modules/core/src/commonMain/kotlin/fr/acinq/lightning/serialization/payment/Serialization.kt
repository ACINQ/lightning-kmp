package fr.acinq.lightning.serialization.payment

import fr.acinq.lightning.db.WalletPayment

object Serialization {

    fun serialize(payment: WalletPayment): ByteArray {
        return fr.acinq.lightning.serialization.payment.v1.Serialization.serialize(payment)
    }

    fun deserialize(bin: ByteArray): Result<WalletPayment> {
        return runCatching {
            when (val version = bin.first().toInt()) {
                1 -> fr.acinq.lightning.serialization.payment.v1.Deserialization.deserialize(bin)
                else -> error("unknown version $version")
            }
        }
    }

}