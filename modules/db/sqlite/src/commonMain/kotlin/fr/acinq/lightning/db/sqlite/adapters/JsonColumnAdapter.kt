package fr.acinq.lightning.db.sqlite.adapters

import app.cash.sqldelight.ColumnAdapter
import fr.acinq.lightning.db.sqlite.converters.Converter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.Json.Default.serializersModule
import kotlinx.serialization.serializer

/**
 * Generic adapter that uses json encoding.
 */
internal class JsonColumnAdapter<C : Any, D : Any>(private val converter: Converter<C, D>, private val serializer: KSerializer<D>) : ColumnAdapter<C, String> {

    override fun decode(databaseValue: String): C {
        return converter.toCoreType(Json.decodeFromString(serializer, databaseValue))
    }

    override fun encode(value: C): String {
        return Json.encodeToString(serializer, converter.toDbType(value))
    }
}

internal inline fun <C : Any, reified D : Any> JsonColumnAdapter(converter: Converter<C, D>): JsonColumnAdapter<C, D> {
    return JsonColumnAdapter(converter, serializersModule.serializer())
}
