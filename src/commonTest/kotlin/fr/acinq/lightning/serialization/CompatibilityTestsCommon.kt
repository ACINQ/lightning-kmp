package fr.acinq.lightning.serialization

import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.byteVector
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEPTHOK
import fr.acinq.lightning.blockchain.WatchEventConfirmed
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.channel.fsm.ChannelCommand
import fr.acinq.lightning.channel.fsm.Normal
import fr.acinq.lightning.channel.fsm.WaitForChannelReady
import fr.acinq.lightning.channel.fsm.WaitForFundingConfirmed
import fr.acinq.lightning.channel.states.WaitForFundingConfirmedTestsCommon
import fr.acinq.lightning.serialization.Encryption.from
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.ChannelReady
import fr.acinq.lightning.wire.EncryptedChannelData
import fr.acinq.lightning.wire.UpdateAddHtlc
import kotlin.test.*

class CompatibilityTestsCommon {
    //@Test
    fun `generate data`() {
        // generate test data
        val (alice, bob, fundingTx) = WaitForFundingConfirmedTestsCommon.init(ChannelType.SupportedChannelType.AnchorOutputs)
        println("funding_tx: ${Transaction.write(fundingTx).byteVector().toHex()}")
        val bin = EncryptedChannelData.from(TestConstants.Bob.nodeParams.nodePrivateKey, bob.state)
        println("wait_for_funding_confirmed: ${bin.data}")

        val (bob1, actionsBob) = bob.process(ChannelCommand.WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)))
        val channelReady = actionsBob.findOutgoingMessage<ChannelReady>()
        assertIs<LNChannel<WaitForChannelReady>>(bob1)

        val bin1 = EncryptedChannelData.from(TestConstants.Bob.nodeParams.nodePrivateKey, bob1.state)
        println("wait_for_channel_ready: ${bin1.data}")

        val (alice1, actionsAlice) = alice.process(ChannelCommand.MessageReceived(channelReady))
        assertIs<LNChannel<WaitForFundingConfirmed>>(alice1)
        assertEquals(channelReady, alice1.state.deferred)

        assertTrue(actionsAlice.isEmpty()) // alice waits until she sees on-chain confirmations herself
        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.WatchReceived(WatchEventConfirmed(alice.channelId, BITCOIN_FUNDING_DEPTHOK, 0, 42, fundingTx)))
        val channelReadyAlice = actionsAlice2.findOutgoingMessage<ChannelReady>()
        val (bob2, _) = bob1.process(ChannelCommand.MessageReceived(channelReadyAlice))
        assertIs<LNChannel<Normal>>(bob2)

        val bin2 = EncryptedChannelData.from(TestConstants.Bob.nodeParams.nodePrivateKey, bob2.state)
        println("normal: ${bin2.data}")

        val add1 = CMD_ADD_HTLC(
            10_000.msat,
            Lightning.randomBytes32(),
            CltvExpiryDelta(144).toCltvExpiry(TestConstants.defaultBlockHeight.toLong()),
            TestConstants.emptyOnionPacket,
            UUID.randomUUID()
        )
        val add2 = CMD_ADD_HTLC(
            10_000.msat,
            Lightning.randomBytes32(),
            CltvExpiryDelta(144).toCltvExpiry(TestConstants.defaultBlockHeight.toLong()),
            TestConstants.emptyOnionPacket,
            UUID.randomUUID()
        )
        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.ExecuteCommand(add1))
        val (_, actionsAlice4) = alice3.process(ChannelCommand.ExecuteCommand(add2))
        val htlc1 = actionsAlice3.findOutgoingMessage<UpdateAddHtlc>()
        val htlc2 = actionsAlice4.findOutgoingMessage<UpdateAddHtlc>()

        val (bob3, _) = bob2.process(ChannelCommand.MessageReceived(htlc1))
        val (bob4, _) = bob3.process(ChannelCommand.MessageReceived(htlc2))
        val (bob5, _) = bob4.process(ChannelCommand.ExecuteCommand(add1))
        val (bob6, _) = bob5.process(ChannelCommand.ExecuteCommand(add2))

        assertIs<LNChannel<Normal>>(bob6)

        val bin3 = EncryptedChannelData.from(TestConstants.Bob.nodeParams.nodePrivateKey, bob6.state)
        println("normal_with_htlcs: ${bin3.data}")
    }
}