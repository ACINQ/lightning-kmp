package fr.acinq.lightning.channel

data class ErrorInformationLeak(
    override val commitments: Commitments
) : ChannelStateWithCommitments() {
    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return Pair(this@ErrorInformationLeak, listOf())
    }

    override fun updateCommitments(input: Commitments): ChannelStateWithCommitments {
        return this.copy(commitments = input)
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        return Pair(this@ErrorInformationLeak, listOf())
    }
}
