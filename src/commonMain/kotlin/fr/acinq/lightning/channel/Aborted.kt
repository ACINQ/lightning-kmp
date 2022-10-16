package fr.acinq.lightning.channel

/**
 * Channel has been aborted before it was funded (because we did not receive a FundingCreated or FundingSigned message for example)
 */
object Aborted : ChannelState() {
    override fun ChannelContext.processInternal(cmd: ChannelCommand): Pair<ChannelState, List<ChannelAction>> {
        return Pair(this@Aborted, listOf())
    }

    override fun ChannelContext.handleLocalError(cmd: ChannelCommand, t: Throwable): Pair<ChannelState, List<ChannelAction>> {
        return Pair(this@Aborted, listOf())
    }
}
