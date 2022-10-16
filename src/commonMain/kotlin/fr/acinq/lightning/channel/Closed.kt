package fr.acinq.lightning.channel

/**
 * Channel is closed i.t its funding tx has been spent and the spending transactions have been confirmed, it can be forgotten
 */
data class Closed(val state: Closing) : ChannelStateWithCommitments() {
    override val commitments: Commitments get() = state.commitments

    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments {
        return this.copy(state = state.updateCommitments(input) as Closing)
    }

    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return Pair(this@Closed, listOf())
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        logger.error(t) { "c:$channelId error on event ${cmd::class} in state ${this::class}" }
        return Pair(this@Closed, listOf())
    }
}
