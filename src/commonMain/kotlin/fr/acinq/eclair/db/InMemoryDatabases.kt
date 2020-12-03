package fr.acinq.eclair.db

data class InMemoryDatabases(
    override val channels: InMemoryChannelsDb = InMemoryChannelsDb(),
    override val payments: InMemoryPaymentsDb = InMemoryPaymentsDb()
) : Databases