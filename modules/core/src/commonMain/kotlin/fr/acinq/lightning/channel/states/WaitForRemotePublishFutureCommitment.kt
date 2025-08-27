package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.lightning.blockchain.WatchConfirmedTriggered
import fr.acinq.lightning.blockchain.WatchSpentTriggered
import fr.acinq.lightning.channel.ChannelAction
import fr.acinq.lightning.channel.ChannelCommand
import fr.acinq.lightning.channel.Commitments
import fr.acinq.lightning.wire.ChannelReestablish
import kotlinx.serialization.Transient

data class WaitForRemotePublishFutureCommitment(
    override val commitments: Commitments,
    val remoteChannelReestablish: ChannelReestablish,
    @Transient override val remoteCommitNonces: Map<TxId, IndividualNonce>
) : ChannelStateWithCommitments() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override suspend fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when {
            cmd is ChannelCommand.WatchReceived -> when (cmd.watch) {
                is WatchSpentTriggered -> handlePotentialForceClose(cmd.watch)
                is WatchConfirmedTriggered -> Pair(this@WaitForRemotePublishFutureCommitment, listOf())
            }
            cmd is ChannelCommand.Disconnected -> Pair(Offline(this@WaitForRemotePublishFutureCommitment), listOf())
            else -> unhandled(cmd)
        }
    }
}
