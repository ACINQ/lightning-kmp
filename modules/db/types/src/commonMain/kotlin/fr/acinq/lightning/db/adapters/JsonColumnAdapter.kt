package fr.acinq.lightning.db.adapters

import app.cash.sqldelight.ColumnAdapter
import fr.acinq.lightning.db.converters.Converter
import fr.acinq.lightning.db.converters.IncomingLightningPaymentConverter
import fr.acinq.lightning.db.types.IncomingLightningPayment
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.Json.Default.serializersModule
import kotlinx.serialization.serializer

/**
 * Generic adapter that uses json encoding.
 */
internal inline fun <C : Any, reified D : Any> jsonColumnAdapter(converter: Converter<C, D>): ColumnAdapter<C, String> {
    val serializer: KSerializer<D> = serializersModule.serializer()
    val json = Json {
        prettyPrint = true
    }
    return object : ColumnAdapter<C, String> {

        override fun decode(databaseValue: String): C {
            return converter.toCoreType(json.decodeFromString(serializer, databaseValue))
        }

        override fun encode(value: C): String {
            return json.encodeToString(serializer, converter.toDbType(value))
        }
    }
}

val IncomingLightningPaymentAdapter = jsonColumnAdapter<fr.acinq.lightning.db.LightningIncomingPayment, IncomingLightningPayment>(IncomingLightningPaymentConverter)