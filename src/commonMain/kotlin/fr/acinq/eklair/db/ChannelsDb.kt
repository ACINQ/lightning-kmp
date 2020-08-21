package fr.acinq.eklair.db

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eklair.CltvExpiry
import fr.acinq.eklair.channel.HasCommitments

interface ChannelsDb {
    fun addOrUpdateChannel(state: HasCommitments): Unit

    fun removeChannel(channelId: ByteVector32): Unit

    fun listLocalChannels(): List<HasCommitments>

    fun addHtlcInfo(channelId: ByteVector32, commitmentNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry): Unit

    fun listHtlcInfos(channelId: ByteVector32, commitmentNumber: Long): List<Pair<ByteVector32, CltvExpiry>>

    fun close()
}