package fr.acinq.eclair.db

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.CltvExpiry
import fr.acinq.eclair.channel.ChannelStateWithCommitments

class InMemoryChannelsDb : ChannelsDb {
    private val channels = mutableMapOf<ByteVector32, ChannelStateWithCommitments>()

    override suspend fun addOrUpdateChannel(state: ChannelStateWithCommitments) { channels[state.channelId] = state }

    override suspend fun removeChannel(channelId: ByteVector32) { channels.remove(channelId) }

    override suspend fun listLocalChannels(): List<ChannelStateWithCommitments> = channels.values.toList()

    override suspend fun addHtlcInfo(channelId: ByteVector32, commitmentNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry) {
        TODO("Not yet implemented")
    }

    override suspend fun listHtlcInfos(channelId: ByteVector32, commitmentNumber: Long): List<Pair<ByteVector32, CltvExpiry>> {
        TODO("Not yet implemented")
    }

    override fun close() {
        TODO("Not yet implemented")
    }
}
