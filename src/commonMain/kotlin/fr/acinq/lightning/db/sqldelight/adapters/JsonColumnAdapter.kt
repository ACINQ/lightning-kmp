package fr.acinq.lightning.db.sqldelight.adapters

import app.cash.sqldelight.ColumnAdapter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.Json.Default.serializersModule
import kotlinx.serialization.serializer

/**
 * Generic adapter that uses json encoding.
 */
class JsonColumnAdapter<T : Any>(private val serializer: KSerializer<T>) : ColumnAdapter<T, String> {

    override fun decode(databaseValue: String): T {
        return Json.decodeFromString(serializer, databaseValue)
    }

    override fun encode(value: T): String {
        return Json.encodeToString(serializer, value)
    }
}

inline fun <reified T : Any> JsonColumnAdapter(): JsonColumnAdapter<T> {
    return JsonColumnAdapter(serializersModule.serializer())
}
