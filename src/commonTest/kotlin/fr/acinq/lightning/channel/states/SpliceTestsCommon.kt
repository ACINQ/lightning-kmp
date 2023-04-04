package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.*
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.channel.TestsHelper.addHtlc
import fr.acinq.lightning.channel.TestsHelper.claimHtlcSuccessTxs
import fr.acinq.lightning.channel.TestsHelper.claimHtlcTimeoutTxs
import fr.acinq.lightning.channel.TestsHelper.crossSign
import fr.acinq.lightning.channel.TestsHelper.fulfillHtlc
import fr.acinq.lightning.channel.TestsHelper.htlcSuccessTxs
import fr.acinq.lightning.channel.TestsHelper.htlcTimeoutTxs
import fr.acinq.lightning.channel.TestsHelper.makeCmdAdd
import fr.acinq.lightning.channel.TestsHelper.reachNormal
import fr.acinq.lightning.channel.TestsHelper.signAndRevack
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.router.Announcements
import fr.acinq.lightning.serialization.Encryption.from
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.transactions.Transactions.weight2fee
import fr.acinq.lightning.transactions.incomings
import fr.acinq.lightning.transactions.outgoings
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.*

@ExperimentalUnsignedTypes
class SpliceTestsCommon : LightningTestSuite() {

    private fun doSplice(alice: LNChannel<Normal>, bob: LNChannel<Normal>, cmd: Command.Splice.Request): Pair<LNChannel<Normal>, LNChannel<Normal>> {
        val (alice1, actionsAlice1) = alice.process(ChannelCommand.ExecuteCommand(cmd))
        val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(actionsAlice1.findOutgoingMessage<SpliceInit>()))
        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(actionsBob1.findOutgoingMessage<SpliceAck>()))
        val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(actionsAlice2.findOutgoingMessage<TxAddInput>()))
        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.MessageReceived(actionsBob2.findOutgoingMessage<TxComplete>()))
        val (bob3, actionsBob3) = bob2.process(ChannelCommand.MessageReceived(actionsAlice3.findOutgoingMessage<TxAddOutput>()))
        val (alice4, actionsAlice4) = alice3.process(ChannelCommand.MessageReceived(actionsBob3.findOutgoingMessage<TxComplete>()))
        val (bob4, actionsBob4) = bob3.process(ChannelCommand.MessageReceived(actionsAlice4.findOutgoingMessage<TxAddOutput>()))
        val (alice5, actionsAlice5) = alice4.process(ChannelCommand.MessageReceived(actionsBob4.findOutgoingMessage<TxComplete>()))
        val (bob5, actionsBob5) = bob4.process(ChannelCommand.MessageReceived(actionsAlice5.findOutgoingMessage<TxComplete>()))
        runBlocking { assertIs<Command.Splice.Response>(cmd.replyTo.await()) }
        val (bob6, actionsBob6) = bob5.process(ChannelCommand.MessageReceived(actionsAlice5.findOutgoingMessage<CommitSig>()))
        val (alice6, _) = alice5.process(ChannelCommand.MessageReceived(actionsBob5.findOutgoingMessage<CommitSig>()))
        val (alice7, actionsAlice7) = alice6.process(ChannelCommand.MessageReceived(actionsBob6.findOutgoingMessage<TxSignatures>()))
        val (bob7, _) = bob6.process(ChannelCommand.MessageReceived(actionsAlice7.findOutgoingMessage<TxSignatures>()))

        assertEquals(alice.commitments.active.size + 1, alice7.commitments.active.size)
        assertEquals(bob.commitments.active.size + 1, bob7.commitments.active.size)
        assertIs<LNChannel<Normal>>(alice7)
        assertIs<LNChannel<Normal>>(bob7)
        return Pair(alice7, bob7)
    }

    /** Full remote commit resolution from tx detection to channel close */
    private fun remoteClose(channel1: LNChannel<Closing>, actions1: List<ChannelAction>, commitment: Commitment, remoteCommitTx: Transaction) {
        assertEquals(commitment.remoteCommit.txid, remoteCommitTx.txid)
        assertEquals(0, commitment.remoteCommit.spec.htlcs.size, "this helper only supports remote-closing without htlcs")

        assertIs<LNChannel<Closing>>(channel1)
        assertEquals(1, actions1.findPublishTxs().size)
        val claimRemoteDelayedOutputTx = actions1.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimRemoteDelayedOutputTx)
        assertEquals(2, actions1.findWatches<WatchConfirmed>().size)
        val watchConfirmedCommit = actions1.findWatches<WatchConfirmed>().first { it.txId == commitment.remoteCommit.txid }
        val watchConfirmedClaimRemote = actions1.findWatches<WatchConfirmed>().first { it.txId == claimRemoteDelayedOutputTx.txid }
        actions1.has<ChannelAction.Storage.StoreState>()

        // remote commit confirmed
        val (channel2, actions2) = channel1.process(ChannelCommand.WatchReceived(WatchEventConfirmed(channel1.channelId, watchConfirmedCommit.event, channel1.currentBlockHeight, 42, remoteCommitTx)))
        assertIs<LNChannel<Closing>>(channel2)
        actions2.has<ChannelAction.Storage.StoreState>()

        // claim main delayed confirmed
        val (channel3, actionsAlice4) = channel2.process(ChannelCommand.WatchReceived(WatchEventConfirmed(channel1.channelId, watchConfirmedClaimRemote.event, channel2.currentBlockHeight, 43, claimRemoteDelayedOutputTx)))
        assertIs<LNChannel<Closed>>(channel3)
        actionsAlice4.has<ChannelAction.Storage.StoreState>()
    }

    @Test
    fun `latest remote commit`() {
        val cmd = Command.Splice.Request(
            replyTo = CompletableDeferred(),
            spliceIn = null,
            spliceOut = Command.Splice.Request.SpliceOut(
                amount = 15_000.sat,
                scriptPubKey = ByteVector.fromHex("0020aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            ),
            feerate = FeeratePerKw(253.sat)
        )

        val (alice0, bob0) = reachNormal()
        val (alice1, bob1) = doSplice(alice0, bob0, cmd)

        val bobCommitTx1 = bob1.commitments.latest.localCommit.publishableTxs.commitTx.tx
        // remote commit detected
        val (alice2, actions2) = alice1.process(ChannelCommand.WatchReceived(WatchEventSpent(alice1.channelId, BITCOIN_FUNDING_SPENT, bobCommitTx1)))
        assertIs<LNChannel<Closing>>(alice2)
        remoteClose(alice2, actions2, alice1.commitments.active.first(), bobCommitTx1)
    }

    @Test
    fun `previous remote commit`() {
        val cmd = Command.Splice.Request(
            replyTo = CompletableDeferred(),
            spliceIn = null,
            spliceOut = Command.Splice.Request.SpliceOut(
                amount = 15_000.sat,
                scriptPubKey = ByteVector.fromHex("0020aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            ),
            feerate = FeeratePerKw(253.sat)
        )

        val (alice0, bob0) = reachNormal()
        val (alice1, bob1) = doSplice(alice0, bob0, cmd)
        val (alice2, _) = doSplice(alice1, bob1, cmd)

        val bobCommitTx1 = bob1.commitments.latest.localCommit.publishableTxs.commitTx.tx

        // alice detects the commit for the older commitment
        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.WatchReceived(WatchEventSpent(alice2.channelId, BITCOIN_FUNDING_SPENT, bobCommitTx1)))
        // alice attempts a local force close and in parallel puts a watch on the remote commit
        assertIs<LNChannel<Closing>>(alice3)
        assertEquals(2, actionsAlice3.findPublishTxs().size)
        val localCommit = actionsAlice3.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.CommitTx).also { assertEquals(alice2.commitments.latest.localCommit.publishableTxs.commitTx.tx.txid, it.txid) }
        val claimMainDelayed = actionsAlice3.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimLocalDelayedOutputTx)
        assertEquals(3, actionsAlice3.findWatches<WatchConfirmed>().size)
        val watchConfirmedRemoteCommit = actionsAlice3.hasWatchConfirmed(bobCommitTx1.txid).also { assertEquals(BITCOIN_ALTERNATIVE_COMMIT_TX_CONFIRMED, it.event) }
        actionsAlice3.hasWatchConfirmed(localCommit.txid)
        actionsAlice3.hasWatchConfirmed(claimMainDelayed.txid)

        // the commit confirms
        val (alice4, actionsAlice4) = alice3.process(ChannelCommand.WatchReceived(WatchEventConfirmed(alice3.channelId, watchConfirmedRemoteCommit.event, alice3.currentBlockHeight, 43, bobCommitTx1)))
        assertIs<LNChannel<Closing>>(alice4)
        // we clean up the commitments
        assertEquals(3, alice3.commitments.active.size)
        assertEquals(1, alice4.commitments.active.size)
        // and switch to normal processing for remote commit
        remoteClose(alice4, actionsAlice4, alice4.commitments.active.first(), bobCommitTx1)
    }

}
