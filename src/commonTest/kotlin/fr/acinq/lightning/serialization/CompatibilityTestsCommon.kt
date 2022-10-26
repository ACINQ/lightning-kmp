package fr.acinq.lightning.serialization

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.byteVector
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Feature
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEPTHOK
import fr.acinq.lightning.blockchain.WatchEventConfirmed
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.channel.TestsHelper.processEx
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
    @Ignore
    @Test
    fun `generate data`() {
        // generate test data
        val (alice, bob, fundingTx) = run {
            val (alice0, bob0, txSigsBob) = WaitForFundingConfirmedTestsCommon.init(ChannelType.SupportedChannelType.AnchorOutputs)
            val (alice1, actions1) = alice0.processEx(ChannelCommand.MessageReceived(txSigsBob), minVersion = 3)
            assertIs<LNChannel<WaitForFundingConfirmed>>(alice1)
            val fundingTx = actions1.find<ChannelAction.Blockchain.PublishTx>().tx
            Triple(alice1, bob0, fundingTx)
        }
        println("funding_tx: ${Transaction.write(fundingTx).byteVector().toHex()}")
        val bin = EncryptedChannelData.from(TestConstants.Bob.nodeParams.nodePrivateKey, bob.state)
        println("wait_for_funding_confirmed: ${bin.data}")

        val (bob1, actionsBob) = bob.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(bob.channelId, BITCOIN_FUNDING_DEPTHOK, 42, 0, fundingTx)), minVersion = 3)
        val channelReady = actionsBob.findOutgoingMessage<ChannelReady>()
        assertIs<LNChannel<WaitForChannelReady>>(bob1)

        val bin1 = EncryptedChannelData.from(TestConstants.Bob.nodeParams.nodePrivateKey, bob1.state)
        println("wait_for_channel_ready: ${bin1.data}")

        val (alice1, actionsAlice) = alice.processEx(ChannelCommand.MessageReceived(channelReady), minVersion = 3)
        assertIs<LNChannel<WaitForFundingConfirmed>>(alice1)
        assertEquals(channelReady, alice1.state.deferred)

        assertTrue(actionsAlice.isEmpty()) // alice waits until she sees on-chain confirmations herself
        val (alice2, actionsAlice2) = alice1.processEx(ChannelCommand.WatchReceived(WatchEventConfirmed(alice.channelId, BITCOIN_FUNDING_DEPTHOK, 0, 42, fundingTx)), minVersion = 3)
        val channelReadyAlice = actionsAlice2.findOutgoingMessage<ChannelReady>()
        val (bob2, _) = bob1.processEx(ChannelCommand.MessageReceived(channelReadyAlice), minVersion = 3)
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
        val (alice3, actionsAlice3) = alice2.processEx(ChannelCommand.ExecuteCommand(add1), minVersion = 3)
        val (_, actionsAlice4) = alice3.processEx(ChannelCommand.ExecuteCommand(add2), minVersion = 3)
        val htlc1 = actionsAlice3.findOutgoingMessage<UpdateAddHtlc>()
        val htlc2 = actionsAlice4.findOutgoingMessage<UpdateAddHtlc>()

        val (bob3, _) = bob2.processEx(ChannelCommand.MessageReceived(htlc1), minVersion = 3)
        val (bob4, _) = bob3.processEx(ChannelCommand.MessageReceived(htlc2), minVersion = 3)
        val (bob5, _) = bob4.processEx(ChannelCommand.ExecuteCommand(add1), minVersion = 3)
        val (bob6, _) = bob5.processEx(ChannelCommand.ExecuteCommand(add2), minVersion = 3)

        assertIs<LNChannel<Normal>>(bob6)

        val bin3 = EncryptedChannelData.from(TestConstants.Bob.nodeParams.nodePrivateKey, bob6.state)
        println("normal_with_htlcs: ${bin3.data}")
    }
}