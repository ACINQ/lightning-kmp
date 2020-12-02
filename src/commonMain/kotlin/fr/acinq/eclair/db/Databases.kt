package fr.acinq.eclair.db

interface Databases {
    val channels: ChannelsDb
    val payments: PaymentsDb
}