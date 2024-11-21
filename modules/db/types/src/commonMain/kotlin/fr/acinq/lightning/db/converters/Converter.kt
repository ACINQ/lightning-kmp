package fr.acinq.lightning.db.converters

internal interface Converter<C, D> {
    fun toCoreType(o: D): C
    fun toDbType(o: C): D
}