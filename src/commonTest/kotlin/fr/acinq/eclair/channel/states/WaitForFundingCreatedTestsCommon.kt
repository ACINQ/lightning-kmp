package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.blockchain.WatchConfirmed
import fr.acinq.eclair.blockchain.WatchSpent
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.channel.TestsHelper.findOutgoingMessage
import fr.acinq.eclair.channel.TestsHelper.findOutgoingWatch
import fr.acinq.eclair.utils.sat
import fr.acinq.eclair.utils.toMilliSatoshi
import fr.acinq.eclair.wire.AcceptChannel
import fr.acinq.eclair.wire.Error
import fr.acinq.eclair.wire.FundingCreated
import fr.acinq.eclair.wire.FundingSigned
import kotlin.test.Test
import kotlin.test.assertTrue

class WaitForFundingCreatedTestsCommon {
    @Test
    fun `recv FundingCreated`() {
        val (_, bob, fundingCreated) = init(ChannelVersion.STANDARD, TestConstants.fundingSatoshis, TestConstants.pushMsat)
        val (bob1, actions1) = bob.process(MessageReceived(fundingCreated))
        assertTrue { bob1 is WaitForFundingConfirmed }
        actions1.findOutgoingMessage<FundingSigned>()
        actions1.findOutgoingWatch<WatchSpent>()
        actions1.findOutgoingWatch<WatchConfirmed>()
    }

    @Test
    fun `recv FundingCreated (funder can't pay fees)`() {
        val (_, bob, fundingCreated) = init(ChannelVersion.STANDARD, 1000100.sat, 1000000.sat.toMilliSatoshi())
        val (bob1, actions1) = bob.process(MessageReceived(fundingCreated))
        val error = actions1.findOutgoingMessage<Error>()
        println(error)
        assertTrue { bob1 is Aborted }
    }

    @Test
    fun `recv Error`() {
        val (_, bob, _) = init(ChannelVersion.STANDARD, TestConstants.fundingSatoshis, TestConstants.pushMsat)
        val (bob1, _) = bob.process(MessageReceived(Error(ByteVector32.Zeroes, "oops")))
        assertTrue { bob1 is Aborted }
    }

    @Test
    fun `recv CMD_CLOSE`() {
        val (_, bob, _) = init(ChannelVersion.STANDARD, TestConstants.fundingSatoshis, TestConstants.pushMsat)
        val (bob1, _) = bob.process(ExecuteCommand(CMD_CLOSE(null)))
        assertTrue { bob1 is Aborted }
    }

    companion object {
        fun init(channelVersion: ChannelVersion, fundingAmount: Satoshi, pushAmount: MilliSatoshi): Triple<WaitForFundingSigned, WaitForFundingCreated, FundingCreated> {
            val (a, b, open) = TestsHelper.init(channelVersion, 0, fundingAmount)
            val open1 = open.copy(pushMsat = pushAmount)
            val (b1, actions) = b.process(MessageReceived(open1))
            val accept = actions.findOutgoingMessage<AcceptChannel>()
            val (a1, actions2) = a.process(MessageReceived(accept))
            val fundingRequest = actions2.filterIsInstance<MakeFundingTx>().first()
            val fundingTx = Transaction(version = 2, txIn = listOf(), txOut = listOf(TxOut(fundingRequest.amount, fundingRequest.pubkeyScript)), lockTime = 0)
            val (a2, actions3) = a1.process(MakeFundingTxResponse(fundingTx, 0, Satoshi(0)))
            val fundingCreated = actions3.findOutgoingMessage<FundingCreated>()
            return Triple(a2 as WaitForFundingSigned, b1 as WaitForFundingCreated, fundingCreated)
        }
    }
}