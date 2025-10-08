package fr.acinq.lightning.serialization.channel

import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.byteVector
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchConfirmedTriggered
import fr.acinq.lightning.channel.ChannelCommand
import fr.acinq.lightning.channel.ChannelType
import fr.acinq.lightning.channel.LNChannel
import fr.acinq.lightning.channel.findOutgoingMessage
import fr.acinq.lightning.channel.states.Normal
import fr.acinq.lightning.channel.states.WaitForChannelReady
import fr.acinq.lightning.channel.states.WaitForFundingConfirmed
import fr.acinq.lightning.channel.states.WaitForFundingConfirmedTestsCommon
import fr.acinq.lightning.serialization.channel.Encryption.from
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.ChannelReady
import fr.acinq.lightning.wire.EncryptedPeerStorage
import fr.acinq.lightning.wire.UpdateAddHtlc
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CompatibilityTestsCommon {
    //@Test
    fun `generate data`() {
        // generate test data
        val (alice, bob, fundingTx) = WaitForFundingConfirmedTestsCommon.init(ChannelType.SupportedChannelType.SimpleTaprootChannels)
        println("funding_tx: ${Transaction.write(fundingTx).byteVector().toHex()}")
        val bin = EncryptedPeerStorage.from(TestConstants.Bob.nodeParams.nodePrivateKey, listOf(bob.state))
        println("wait_for_funding_confirmed: ${bin.data}")

        val (bob1, actionsBob) = bob.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(bob.channelId, WatchConfirmed.ChannelFundingDepthOk, 42, 0, fundingTx)))
        val channelReady = actionsBob.findOutgoingMessage<ChannelReady>()
        assertIs<LNChannel<WaitForChannelReady>>(bob1)

        val bin1 = EncryptedPeerStorage.from(TestConstants.Bob.nodeParams.nodePrivateKey, listOf(bob1.state))
        println("wait_for_channel_ready: ${bin1.data}")

        val (alice1, actionsAlice) = alice.process(ChannelCommand.MessageReceived(channelReady))
        assertIs<LNChannel<WaitForFundingConfirmed>>(alice1)
        assertEquals(channelReady, alice1.state.deferred)

        assertTrue(actionsAlice.isEmpty()) // alice waits until she sees on-chain confirmations herself
        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.WatchReceived(WatchConfirmedTriggered(alice.channelId, WatchConfirmed.ChannelFundingDepthOk, 0, 42, fundingTx)))
        val channelReadyAlice = actionsAlice2.findOutgoingMessage<ChannelReady>()
        val (bob2, _) = bob1.process(ChannelCommand.MessageReceived(channelReadyAlice))
        assertIs<LNChannel<Normal>>(bob2)

        val bin2 = EncryptedPeerStorage.from(TestConstants.Bob.nodeParams.nodePrivateKey, listOf(bob2.state))
        println("normal: ${bin2.data}")

        val add1 = ChannelCommand.Htlc.Add(
            10_000.msat,
            Lightning.randomBytes32(),
            CltvExpiryDelta(144).toCltvExpiry(TestConstants.defaultBlockHeight.toLong()),
            TestConstants.emptyOnionPacket,
            UUID.randomUUID()
        )
        val add2 = ChannelCommand.Htlc.Add(
            10_000.msat,
            Lightning.randomBytes32(),
            CltvExpiryDelta(144).toCltvExpiry(TestConstants.defaultBlockHeight.toLong()),
            TestConstants.emptyOnionPacket,
            UUID.randomUUID()
        )
        val (alice3, actionsAlice3) = alice2.process(add1)
        val (_, actionsAlice4) = alice3.process(add2)
        val htlc1 = actionsAlice3.findOutgoingMessage<UpdateAddHtlc>()
        val htlc2 = actionsAlice4.findOutgoingMessage<UpdateAddHtlc>()

        val (bob3, _) = bob2.process(ChannelCommand.MessageReceived(htlc1))
        val (bob4, _) = bob3.process(ChannelCommand.MessageReceived(htlc2))
        val (bob5, _) = bob4.process(add1)
        val (bob6, _) = bob5.process(add2)

        assertIs<LNChannel<Normal>>(bob6)

        val bin3 = EncryptedPeerStorage.from(TestConstants.Bob.nodeParams.nodePrivateKey, listOf(bob6.state))
        println("normal_with_htlcs: ${bin3.data}")
    }
}