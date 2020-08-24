package fr.acinq.eklair.db

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eklair.CltvExpiry
import fr.acinq.eklair.channel.HasCommitments

interface ChannelsDb {
    suspend fun addOrUpdateChannel(state: HasCommitments)

    suspend fun removeChannel(channelId: ByteVector32)

    suspend fun listLocalChannels(): List<HasCommitments>

    suspend fun addHtlcInfo(channelId: ByteVector32, commitmentNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry)

    suspend fun listHtlcInfos(channelId: ByteVector32, commitmentNumber: Long): List<Pair<ByteVector32, CltvExpiry>>

    fun close()
}