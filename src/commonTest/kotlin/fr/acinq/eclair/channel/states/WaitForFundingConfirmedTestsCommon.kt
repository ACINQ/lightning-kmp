package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.updated
import fr.acinq.eclair.Eclair
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.blockchain.BITCOIN_FUNDING_DEPTHOK
import fr.acinq.eclair.blockchain.WatchEventConfirmed
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.utils.sat
import fr.acinq.eclair.wire.FundingLocked
import fr.acinq.eclair.wire.FundingSigned
import kotlin.test.Test
import kotlin.test.assertTrue

class WaitForFundingConfirmedTestsCommon {
    @Test
    fun `receive FundingLocked`() {
        val (alice, bob) = init(ChannelVersion.STANDARD, TestConstants.fundingSatoshis, TestConstants.pushMsat)
        val fundingTx = alice.fundingTx!!
        val (bob1, actions) = bob.process(WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
        val fundingLocked = actions.findOutgoingMessage<FundingLocked>()
        assertTrue { bob1 is WaitForFundingLocked }
        val (alice1, _) = alice.process(MessageReceived(fundingLocked))
        assertTrue { alice1 is WaitForFundingConfirmed && alice1.deferred == fundingLocked }
    }

    @Test
    fun `receive BITCOIN_FUNDING_DEPTHOK`() {
        val (alice, bob) = init(ChannelVersion.STANDARD, TestConstants.fundingSatoshis, TestConstants.pushMsat)
        val fundingTx = alice.fundingTx!!
        val (bob1, actions) = bob.process(WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
        actions.findOutgoingMessage<FundingLocked>()
        assertTrue { bob1 is WaitForFundingLocked }
    }

    @Test
    fun `receive BITCOIN_FUNDING_DEPTHOK (bad funding pubkey script)`() {
        val (alice, bob) = init(ChannelVersion.STANDARD, TestConstants.fundingSatoshis, TestConstants.pushMsat)
        val fundingTx = alice.fundingTx!!
        val badOutputScript = Scripts.multiSig2of2(Eclair.randomKey().publicKey(), Eclair.randomKey().publicKey())
        val badFundingTx = fundingTx.copy(txOut = fundingTx.txOut.updated(0, fundingTx.txOut[0].updatePublicKeyScript(badOutputScript)))
        val (bob1, _) = bob.process(WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, badFundingTx)))
        assertTrue { bob1 is Aborted }
    }

    @Test
    fun `receive BITCOIN_FUNDING_DEPTHOK (bad funding amount)`() {
        val (alice, bob) = init(ChannelVersion.STANDARD, TestConstants.fundingSatoshis, TestConstants.pushMsat)
        val fundingTx = alice.fundingTx!!
        val badAmount = 1234567.sat
        val badFundingTx = fundingTx.copy(txOut = fundingTx.txOut.updated(0, fundingTx.txOut[0].updateAmount(badAmount)))
        val (bob1, _) = bob.process(WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, badFundingTx)))
        assertTrue { bob1 is Aborted }
    }

    companion object {
        fun init(channelVersion: ChannelVersion, fundingAmount: Satoshi, pushAmount: MilliSatoshi): Pair<WaitForFundingConfirmed, WaitForFundingConfirmed> {
            val (alice, bob, fundingCreated) = WaitForFundingCreatedTestsCommon.init(channelVersion, fundingAmount, pushAmount)
            val (bob1, actions1) = bob.process(MessageReceived(fundingCreated))
            val fundingSigned = actions1.findOutgoingMessage<FundingSigned>()
            val (alice1, _) = alice.process(MessageReceived(fundingSigned))
            return Pair(alice1 as WaitForFundingConfirmed, bob1 as WaitForFundingConfirmed)
        }
    }
}
