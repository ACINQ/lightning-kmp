package fr.acinq.lightning.db.adapters

import app.cash.sqldelight.ColumnAdapter
import fr.acinq.lightning.db.converters.Converter
import fr.acinq.lightning.db.converters.IncomingPaymentConverter
import fr.acinq.lightning.db.types.IncomingPayment
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.Json.Default.serializersModule
import kotlinx.serialization.serializer

/**
 * Generic adapter that uses json encoding.
 */
private inline fun <C : Any, reified D : Any> jsonColumnAdapter(converter: Converter<C, D>): ColumnAdapter<C, String> {
    val serializer: KSerializer<D> = serializersModule.serializer()
    val format = Json {
        prettyPrint = true
    }
    return object : ColumnAdapter<C, String> {

        override fun decode(databaseValue: String): C {
            return converter.toCoreType(format.decodeFromString(serializer, databaseValue))
        }

        override fun encode(value: C): String {
            return format.encodeToString(serializer, converter.toDbType(value))
        }
    }
}

val IncomingPaymentJsonAdapter = jsonColumnAdapter<fr.acinq.lightning.db.IncomingPayment, IncomingPayment>(IncomingPaymentConverter)