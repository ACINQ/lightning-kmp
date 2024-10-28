package fr.acinq.lightning.channel.states

import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_SPENT
import fr.acinq.lightning.blockchain.WatchEventSpent
import fr.acinq.lightning.channel.ChannelAction
import fr.acinq.lightning.channel.ChannelCommand
import fr.acinq.lightning.channel.Commitments
import fr.acinq.lightning.wire.ChannelReestablish
import fr.acinq.lightning.wire.Error

data class WaitForRemotePublishFutureCommitment(
    override val commitments: Commitments,
    val remoteChannelReestablish: ChannelReestablish
) : ChannelStateWithCommitments() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override suspend fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when {
            cmd is ChannelCommand.WatchReceived && cmd.watch is WatchEventSpent && cmd.watch.event is BITCOIN_FUNDING_SPENT -> handlePotentialForceClose(cmd.watch)
            cmd is ChannelCommand.Disconnected -> Pair(Offline(this@WaitForRemotePublishFutureCommitment), listOf())
            else -> unhandled(cmd)
        }
    }
}
