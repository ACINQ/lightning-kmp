package fr.acinq.lightning.channel

import fr.acinq.bitcoin.Transaction
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_SPENT
import fr.acinq.lightning.blockchain.WatchEventSpent
import fr.acinq.lightning.channel.Helpers.Closing.claimRemoteCommitMainOutput
import fr.acinq.lightning.wire.ChannelReestablish
import fr.acinq.lightning.wire.Error

data class WaitForRemotePublishFutureCommitment(
    override val commitments: Commitments,
    val remoteChannelReestablish: ChannelReestablish
) : ChannelStateWithCommitments() {
    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments = this.copy(commitments = input)

    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return when {
            cmd is ChannelCommand.WatchReceived && cmd.watch is WatchEventSpent && cmd.watch.event is BITCOIN_FUNDING_SPENT -> handleRemoteSpentFuture(cmd.watch.tx)
            cmd is ChannelCommand.Disconnected -> Pair(Offline(this@WaitForRemotePublishFutureCommitment), listOf())
            else -> unhandled(cmd)
        }
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "c:${commitments.channelId} error on event ${cmd::class} in state ${this::class}" }
        val error = Error(channelId, t.message)
        return Pair(Aborted, listOf(ChannelAction.Message.Send(error)))
    }

    internal fun ChannelContext.handleRemoteSpentFuture(tx: Transaction): Pair<ChannelState, List<ChannelAction>> {
        logger.warning { "c:${commitments.channelId} they published their future commit (because we asked them to) in txid=${tx.txid}" }
        val remoteCommitPublished = claimRemoteCommitMainOutput(
            keyManager,
            commitments,
            tx,
            currentOnChainFeerates.claimMainFeerate
        )
        val nextState = Closing(
            commitments = commitments,
            fundingTx = null,
            waitingSinceBlock = currentBlockHeight.toLong(),
            alternativeCommitments = listOf(),
            futureRemoteCommitPublished = remoteCommitPublished
        )
        val actions = mutableListOf<ChannelAction>(ChannelAction.Storage.StoreState(nextState))
        actions.addAll(remoteCommitPublished.run { doPublish(channelId, staticParams.nodeParams.minDepthBlocks.toLong()) })
        return Pair(nextState, actions)
    }
}
