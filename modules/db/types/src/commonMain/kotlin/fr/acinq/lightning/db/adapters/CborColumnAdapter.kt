package fr.acinq.lightning.db.adapters

import app.cash.sqldelight.ColumnAdapter
import fr.acinq.lightning.db.converters.Converter
import fr.acinq.lightning.db.converters.IncomingPaymentConverter
import fr.acinq.lightning.db.types.IncomingPayment
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json.Default.serializersModule
import kotlinx.serialization.serializer

/**
 * Generic adapter that uses cbor encoding.
 */
@OptIn(ExperimentalSerializationApi::class)
private inline fun <C : Any, reified D : Any> cborColumnAdapter(converter: Converter<C, D>): ColumnAdapter<C, ByteArray> {
    val serializer: KSerializer<D> = serializersModule.serializer()
    val format = Cbor {

    }
    return object : ColumnAdapter<C, ByteArray> {

        override fun decode(databaseValue: ByteArray): C {
            return converter.toCoreType(format.decodeFromByteArray(serializer, databaseValue))
        }

        override fun encode(value: C): ByteArray {
            return format.encodeToByteArray(serializer, converter.toDbType(value))
        }
    }
}

val IncomingPaymentCborAdapter = cborColumnAdapter<fr.acinq.lightning.db.IncomingPayment, IncomingPayment>(IncomingPaymentConverter)