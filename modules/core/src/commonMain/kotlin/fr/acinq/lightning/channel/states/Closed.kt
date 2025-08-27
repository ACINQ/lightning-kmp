package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.lightning.channel.ChannelAction
import fr.acinq.lightning.channel.ChannelCommand
import fr.acinq.lightning.channel.Commitments

/**
 * Channel is closed i.t its funding tx has been spent and the spending transactions have been confirmed, it can be forgotten
 */
data class Closed(val state: Closing) : ChannelStateWithCommitments() {
    override val commitments: Commitments get() = state.commitments

    override val remoteCommitNonces: Map<TxId, IndividualNonce> get() = state.remoteCommitNonces

    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments {
        return this.copy(state = state.updateCommitments(input) as Closing)
    }

    override suspend fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return Pair(this@Closed, listOf())
    }
}
