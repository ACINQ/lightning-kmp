package fr.acinq.eclair.db

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.CltvExpiry
import fr.acinq.eclair.channel.ChannelStateWithCommitments

interface ChannelsDb {
    suspend fun addOrUpdateChannel(state: ChannelStateWithCommitments)

    suspend fun removeChannel(channelId: ByteVector32)

    suspend fun listLocalChannels(): List<ChannelStateWithCommitments>
}