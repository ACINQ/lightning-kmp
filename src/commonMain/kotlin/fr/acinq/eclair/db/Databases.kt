package fr.acinq.eclair.db

interface Databases {
    val channels: ChannelsDb
    val payments: PaymentsDb
}

/**
 * In-memory implementations of the database components.
 * This should only be used in tests: applications should inject their own implementations depending on the database backend available on the platform.
 */
data class InMemoryDatabases(
    override val channels: InMemoryChannelsDb = InMemoryChannelsDb(),
    override val payments: InMemoryPaymentsDb = InMemoryPaymentsDb()
) : Databases