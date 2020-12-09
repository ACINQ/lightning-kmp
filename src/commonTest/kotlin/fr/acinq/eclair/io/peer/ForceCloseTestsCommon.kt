 package fr.acinq.eclair.io.peer

import fr.acinq.eclair.Eclair
import fr.acinq.eclair.NodeUri
import fr.acinq.eclair.blockchain.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.db.OutgoingPayment
import fr.acinq.eclair.io.*
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.tests.io.peer.expectState
import fr.acinq.eclair.tests.io.peer.newPeers
import fr.acinq.eclair.tests.utils.runSuspendTest
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.Error
import fr.acinq.eclair.wire.LightningMessage
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.*
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class ForceCloseTestsCommon {

    @Test
    fun `remote sends an error`() = runSuspendTest {
        val (alice0, bob0) = TestsHelper.reachNormal()
        val channelId = alice0.channelId
        val (alice, _) = newPeers(this, Pair(alice0.staticParams.nodeParams, bob0.staticParams.nodeParams), listOf(alice0 to bob0))

        val error = Error(channelId, "forced local commit".encodeToByteArray().toByteVector())
        alice.send(BytesReceived(LightningMessage.encode(error)))

        val (_, closing) = alice.expectState<Closing>(channelId) { localCommitPublished != null }
        val localCommitPublished = closing.localCommitPublished
        assertNotNull(localCommitPublished)

        assertEquals(alice0.currentTip, closing.currentTip)
        assertEquals(alice0.staticParams, closing.staticParams)
        assertEquals(alice0.commitments, closing.commitments)
        assertEquals(alice0.commitments.localCommit.publishableTxs.commitTx.tx, localCommitPublished.commitTx)

        // Alice's commit tx has been confirmed, now we spend the claim delayed output
        alice.send(WatchReceived(WatchEventConfirmed(channelId, BITCOIN_TX_CONFIRMED(localCommitPublished.commitTx), alice0.currentBlockHeight + 3,1, localCommitPublished.commitTx)))

        val claimMainDelayedOutputTx = localCommitPublished.claimMainDelayedOutputTx
        assertNotNull(claimMainDelayedOutputTx)

        // Alice's claim tx has been confirmed, we can close the channel
        alice.send(WatchReceived(WatchEventConfirmed(channelId, BITCOIN_TX_CONFIRMED(claimMainDelayedOutputTx), 0,1, claimMainDelayedOutputTx)))

        alice.expectState<Closed>(channelId)
    }

    @Test
    fun `remote publishes his commit tx`() = runSuspendTest {
        val (alice0, bob0) = TestsHelper.reachNormal()
        val channelId = alice0.channelId
        val (alice, _) = newPeers(this, Pair(alice0.staticParams.nodeParams, bob0.staticParams.nodeParams), listOf(alice0 to bob0))

        // Commit tx has been published by Bob
        alice.send(WatchReceived(WatchEventSpent(channelId, BITCOIN_FUNDING_SPENT, bob0.commitments.localCommit.publishableTxs.commitTx.tx)))

        val (_, closing) = alice.expectState<Closing>(channelId) { remoteCommitPublished != null }

        val remoteCommitTx = closing.remoteCommitPublished?.commitTx
        assertNotNull(remoteCommitTx)

        // Bob's commit tx has been confirmed, now we can spend it
        alice.send(WatchReceived(WatchEventConfirmed(channelId, BITCOIN_TX_CONFIRMED(remoteCommitTx), 0,1, remoteCommitTx)))

        val claimMainDelayedOutputTx = closing.remoteCommitPublished?.claimMainOutputTx
        assertNotNull(claimMainDelayedOutputTx)

        // Alice's claim tx has been confirmed, we can close the channel
        alice.send(WatchReceived(WatchEventConfirmed(channelId, BITCOIN_TX_CONFIRMED(claimMainDelayedOutputTx), 0,1, claimMainDelayedOutputTx)))

        alice.expectState<Closed>(channelId)
    }

    @Test
    fun `remote publishes a revoked commit tx`() = runSuspendTest {
        val (alice0, bob0) = TestsHelper.reachNormal()
        val nodeParams = Pair(
            // Alice must declare Bob as her trampoline node to enable direct payments.
            alice0.staticParams.nodeParams.copy(trampolineNode = NodeUri(bob0.staticParams.nodeParams.nodeId, "bob.com", 9735)),
            bob0.staticParams.nodeParams
        )
        val (alice, bob) = newPeers(this, nodeParams, listOf(alice0 to bob0))
        val channelId = alice0.channelId
        assertEquals(bob0.channelId, channelId)

        // #1 - Alice pays to Bob
        val deferredInvoice1 = CompletableDeferred<PaymentRequest>()
        bob.send(ReceivePayment(Eclair.randomBytes32(), 15_000_000.msat, "test invoice 1", deferredInvoice1))
        val invoice1 = deferredInvoice1.await()

        alice.send(SendPayment(UUID.randomUUID(), invoice1.amount!!, bob.nodeParams.nodeId, OutgoingPayment.Details.Normal(invoice1)))

        alice.expectState<Normal> { commitments.availableBalanceForReceive() > alice0.commitments.availableBalanceForReceive() }
        val alice1 = alice.channels[channelId] as? Normal
        assertNotNull(alice1)
        val aliceRevokedCommitTx = alice1.commitments.localCommit.publishableTxs.commitTx.tx

        // #2 - Alice pays to Bob
        val deferredInvoice2 = CompletableDeferred<PaymentRequest>()
        bob.send(ReceivePayment(Eclair.randomBytes32(), 15_000_000.msat, "test invoice 2", deferredInvoice2))
        val invoice2 = deferredInvoice2.await()

        alice.send(SendPayment(UUID.randomUUID(), invoice2.amount!!, bob.nodeParams.nodeId, OutgoingPayment.Details.Normal(invoice2)))

        alice.expectState<Normal> { commitments.availableBalanceForReceive() > alice1.commitments.availableBalanceForReceive() }

        // Funding has been spent by a revoked commit tx has been published
        bob.send(WatchReceived(WatchEventSpent(alice0.channelId, BITCOIN_FUNDING_SPENT, aliceRevokedCommitTx)))

        val (_, closing) = bob.expectState<Closing>(channelId) { revokedCommitPublished.isNotEmpty() }

        // Revoked commit tx has been confirmed, now we can spend it
        bob.send(WatchReceived(WatchEventConfirmed(channelId, BITCOIN_TX_CONFIRMED(aliceRevokedCommitTx), 0,1, aliceRevokedCommitTx)))

        val revokedCommitPublished = closing.revokedCommitPublished.first()
        val claimMainOutputTx = revokedCommitPublished.claimMainOutputTx
        assertNotNull(claimMainOutputTx)
        val mainPenaltyTx = revokedCommitPublished.mainPenaltyTx
        assertNotNull(mainPenaltyTx)

        // Bob's claim tx and penalty tx have been confirmed, we can close the channel
        bob.send(WatchReceived(WatchEventConfirmed(channelId, BITCOIN_TX_CONFIRMED(revokedCommitPublished.claimMainOutputTx!!), 0,1, revokedCommitPublished.claimMainOutputTx!!)))
        bob.send(WatchReceived(WatchEventConfirmed(channelId, BITCOIN_TX_CONFIRMED(revokedCommitPublished.mainPenaltyTx!!), 0,1, revokedCommitPublished.mainPenaltyTx!!)))

        bob.expectState<Closed>(channelId)
    }
}