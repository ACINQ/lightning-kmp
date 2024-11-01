package fr.acinq.lightning.db.sqlite.converters

internal interface Converter<C, D> {
    fun toCoreType(o: D): C
    fun toDbType(o: C): D
}