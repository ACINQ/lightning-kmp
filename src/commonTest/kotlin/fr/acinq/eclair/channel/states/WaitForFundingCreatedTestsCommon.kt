package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.blockchain.WatchConfirmed
import fr.acinq.eclair.blockchain.WatchSpent
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.tests.TestConstants
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.sat
import fr.acinq.eclair.utils.toMilliSatoshi
import fr.acinq.eclair.wire.AcceptChannel
import fr.acinq.eclair.wire.Error
import fr.acinq.eclair.wire.FundingCreated
import fr.acinq.eclair.wire.FundingSigned
import kotlin.test.Test
import kotlin.test.assertTrue

class WaitForFundingCreatedTestsCommon : EclairTestSuite() {
    @Test
    fun `recv FundingCreated`() {
        val (_, bob, fundingCreated) = init(ChannelVersion.STANDARD, TestConstants.fundingAmount, TestConstants.pushMsat)
        val (bob1, actions1) = bob.process(ChannelEvent.MessageReceived(fundingCreated))
        assertTrue { bob1 is WaitForFundingConfirmed }
        actions1.findOutgoingMessage<FundingSigned>()
        actions1.hasWatch<WatchSpent>()
        actions1.hasWatch<WatchConfirmed>()
        actions1.has<ChannelAction.ChannelId.IdSwitch>()
        actions1.has<ChannelAction.Storage.StoreState>()
    }

    @Test
    fun `recv FundingCreated (funder can't pay fees)`() {
        val (_, bob, fundingCreated) = init(ChannelVersion.STANDARD, 1_000_100.sat, 1_000_000.sat.toMilliSatoshi())
        val (bob1, actions1) = bob.process(ChannelEvent.MessageReceived(fundingCreated))
        actions1.hasOutgoingMessage<Error>()
        assertTrue { bob1 is Aborted }
    }

    @Test
    fun `recv Error`() {
        val (_, bob, _) = init(ChannelVersion.STANDARD, TestConstants.fundingAmount, TestConstants.pushMsat)
        val (bob1, actions1) = bob.process(ChannelEvent.MessageReceived(Error(ByteVector32.Zeroes, "oops")))
        assertTrue { bob1 is Aborted }
        assertTrue { actions1.isEmpty() }
    }

    @Test
    fun `recv CMD_CLOSE`() {
        val (_, bob, _) = init(ChannelVersion.STANDARD, TestConstants.fundingAmount, TestConstants.pushMsat)
        val (bob1, actions1) = bob.process(ChannelEvent.ExecuteCommand(CMD_CLOSE(null)))
        assertTrue { bob1 is Aborted }
        assertTrue { actions1.isEmpty() }
    }

    @Test
    fun `recv Disconnected`() {
        val (_, bob, fundingCreated) = init(ChannelVersion.STANDARD, TestConstants.fundingAmount, TestConstants.pushMsat)
        val (bob1, _) = bob.process(ChannelEvent.MessageReceived(fundingCreated))
        assertTrue { bob1 is WaitForFundingConfirmed }
        val (bob2, actions2) = bob1.process(ChannelEvent.Disconnected)
        assertTrue { bob2 is Offline }
        assertTrue { actions2.isEmpty() }
    }

    companion object {
        fun init(channelVersion: ChannelVersion, fundingAmount: Satoshi, pushAmount: MilliSatoshi): Triple<WaitForFundingSigned, WaitForFundingCreated, FundingCreated> {
            val (a, b, open) = TestsHelper.init(channelVersion, 0, fundingAmount, pushAmount)
            val (b1, actions) = b.process(ChannelEvent.MessageReceived(open))
            val accept = actions.findOutgoingMessage<AcceptChannel>()
            val (a1, actions2) = a.process(ChannelEvent.MessageReceived(accept))
            val fundingRequest = actions2.filterIsInstance<ChannelAction.Blockchain.MakeFundingTx>().first()
            val fundingTx = Transaction(version = 2, txIn = listOf(), txOut = listOf(TxOut(fundingRequest.amount, fundingRequest.pubkeyScript)), lockTime = 0)
            val (a2, actions3) = a1.process(ChannelEvent.MakeFundingTxResponse(fundingTx, 0, Satoshi(0)))
            val fundingCreated = actions3.findOutgoingMessage<FundingCreated>()
            return Triple(a2 as WaitForFundingSigned, b1 as WaitForFundingCreated, fundingCreated)
        }
    }
}
