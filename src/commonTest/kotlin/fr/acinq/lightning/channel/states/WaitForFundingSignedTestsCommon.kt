package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.*
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.randomBytes
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEPTHOK
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toMilliSatoshi
import fr.acinq.lightning.wire.*
import kotlin.test.*

class WaitForFundingSignedTestsCommon : LightningTestSuite() {

    @Test
    fun `recv CommitSig`() {
        val (alice, commitSigAlice, bob, commitSigBob) = init()
        val commitInput = alice.state.signingSession.commitInput
        run {
            alice.process(ChannelCommand.MessageReceived(commitSigBob)).also { (state, actions) ->
                assertIs<WaitForFundingSigned>(state.state)
                assertTrue(actions.isEmpty())
            }
        }
        run {
            bob.process(ChannelCommand.MessageReceived(commitSigAlice)).also { (state, actions) ->
                assertIs<WaitForFundingConfirmed>(state.state)
                assertEquals(actions.size, 5)
                actions.hasOutgoingMessage<TxSignatures>().also { assertFalse(it.channelData.isEmpty()) }
                actions.findWatch<WatchConfirmed>().also { assertEquals(WatchConfirmed(state.channelId, commitInput.outPoint.txid, commitInput.txOut.publicKeyScript, 3, BITCOIN_FUNDING_DEPTHOK), it) }
                actions.find<ChannelAction.Storage.StoreIncomingPayment.ViaNewChannel>().also { assertEquals(TestConstants.bobFundingAmount.toMilliSatoshi() + TestConstants.alicePushAmount - TestConstants.bobPushAmount, it.amount) }
                actions.has<ChannelAction.Storage.StoreState>()
                actions.find<ChannelAction.EmitEvent>().also { assertEquals(ChannelEvents.Created(state.state), it.event) }
            }
        }
    }

    @Test
    fun `recv CommitSig -- zero conf`() {
        val (alice, commitSigAlice, bob, commitSigBob) = init(ChannelType.SupportedChannelType.AnchorOutputsZeroReserve, zeroConf = true)
        run {
            alice.process(ChannelCommand.MessageReceived(commitSigBob)).also { (state, actions) ->
                assertIs<WaitForFundingSigned>(state.state)
                assertTrue(actions.isEmpty())
            }
        }
        run {
            bob.process(ChannelCommand.MessageReceived(commitSigAlice)).also { (state, actions) ->
                assertIs<WaitForChannelReady>(state.state)
                assertEquals(actions.size, 6)
                actions.hasOutgoingMessage<TxSignatures>().also { assertFalse(it.channelData.isEmpty()) }
                actions.hasOutgoingMessage<ChannelReady>().also { assertEquals(ShortChannelId.peerId(bob.staticParams.nodeParams.nodeId), it.alias) }
                actions.findWatch<WatchConfirmed>().also { assertEquals(state.commitments.latest.fundingTxId, it.txId) }
                actions.find<ChannelAction.Storage.StoreIncomingPayment.ViaNewChannel>().also { assertEquals(TestConstants.bobFundingAmount.toMilliSatoshi() + TestConstants.alicePushAmount - TestConstants.bobPushAmount, it.amount) }
                actions.has<ChannelAction.Storage.StoreState>()
                actions.find<ChannelAction.EmitEvent>().also { assertEquals(ChannelEvents.Created(state.state), it.event) }
            }
        }
    }

    @Test
    fun `recv CommitSig -- liquidity ads`() {
        val (alice, commitSigAlice, bob, commitSigBob) = init(requestRemoteFunding = TestConstants.bobFundingAmount, alicePushAmount = 0.msat, bobPushAmount = 0.msat)
        val lease = alice.process(ChannelCommand.MessageReceived(commitSigBob)).let { (state, actions) ->
            assertIs<WaitForFundingSigned>(state.state)
            assertTrue(actions.isEmpty())
            val lease = state.state.signingSession.liquidityLease
            assertNotNull(lease)
            assertEquals(TestConstants.bobFundingAmount / 100, lease.fees.serviceFee)
            val localCommit = state.state.signingSession.localCommit.right!!
            assertEquals(TestConstants.aliceFundingAmount - lease.fees.total, localCommit.spec.toLocal.truncateToSatoshi())
            assertEquals(TestConstants.bobFundingAmount + lease.fees.total, localCommit.spec.toRemote.truncateToSatoshi())
            lease
        }
        bob.process(ChannelCommand.MessageReceived(commitSigAlice)).also { (state, actions) ->
            assertIs<WaitForFundingConfirmed>(state.state)
            assertEquals(actions.size, 5)
            assertEquals(TestConstants.bobFundingAmount + lease.fees.total, state.commitments.latest.localCommit.spec.toLocal.truncateToSatoshi())
            assertEquals(TestConstants.aliceFundingAmount - lease.fees.total, state.commitments.latest.localCommit.spec.toRemote.truncateToSatoshi())
            actions.hasOutgoingMessage<TxSignatures>().also { assertFalse(it.channelData.isEmpty()) }
            actions.findWatch<WatchConfirmed>().also { assertEquals(BITCOIN_FUNDING_DEPTHOK, it.event) }
            actions.find<ChannelAction.Storage.StoreIncomingPayment.ViaNewChannel>().also { assertEquals((TestConstants.bobFundingAmount + lease.fees.total).toMilliSatoshi(), it.amount) }
            actions.has<ChannelAction.Storage.StoreState>()
            actions.find<ChannelAction.EmitEvent>().also { assertEquals(ChannelEvents.Created(state.state), it.event) }
        }
    }

    @Test
    fun `recv CommitSig -- liquidity ads -- with push_amount`() {
        val aliceFundingAmount = 520_000.sat
        val alicePushAmount = 20_000_500.msat
        val bobFundingAmount = 210_000.sat
        val bobPushAmount = 9_999_500.msat
        val requestFunding = 200_000.sat
        val (alice, commitSigAlice, bob, commitSigBob) = init(
            aliceFundingAmount = aliceFundingAmount,
            alicePushAmount = alicePushAmount,
            bobFundingAmount = bobFundingAmount,
            bobPushAmount = bobPushAmount,
            requestRemoteFunding = requestFunding
        )
        val (aliceBalance, bobBalance) = alice.process(ChannelCommand.MessageReceived(commitSigBob)).let { (state, actions) ->
            assertIs<WaitForFundingSigned>(state.state)
            assertTrue(actions.isEmpty())
            val lease = state.state.signingSession.liquidityLease
            assertNotNull(lease)
            assertEquals(2_000.sat, lease.fees.serviceFee) // 1%
            val aliceBalance = aliceFundingAmount.toMilliSatoshi() - alicePushAmount - lease.fees.total.toMilliSatoshi() + bobPushAmount
            val bobBalance = bobFundingAmount.toMilliSatoshi() - bobPushAmount + alicePushAmount + lease.fees.total.toMilliSatoshi()
            val localCommit = state.state.signingSession.localCommit.right!!
            assertEquals(aliceBalance, localCommit.spec.toLocal)
            assertEquals(bobBalance, localCommit.spec.toRemote)
            Pair(aliceBalance, bobBalance)
        }
        bob.process(ChannelCommand.MessageReceived(commitSigAlice)).also { (state, actions) ->
            assertIs<WaitForFundingConfirmed>(state.state)
            assertEquals(actions.size, 5)
            assertEquals(bobBalance, state.commitments.latest.localCommit.spec.toLocal)
            assertEquals(aliceBalance, state.commitments.latest.localCommit.spec.toRemote)
            actions.hasOutgoingMessage<TxSignatures>().also { assertFalse(it.channelData.isEmpty()) }
            actions.findWatch<WatchConfirmed>().also { assertEquals(BITCOIN_FUNDING_DEPTHOK, it.event) }
            actions.find<ChannelAction.Storage.StoreIncomingPayment.ViaNewChannel>().also { assertEquals(bobBalance, it.amount) }
            actions.has<ChannelAction.Storage.StoreState>()
            actions.find<ChannelAction.EmitEvent>().also { assertEquals(ChannelEvents.Created(state.state), it.event) }
        }
    }

    @Test
    fun `recv CommitSig -- with channel origin -- off-chain payment`() {
        val channelOrigin = Origin.OffChainPayment(randomBytes32(), 50_000_000.msat, TransactionFees(500.sat, 1_000.sat))
        val (alice, _, _, commitSigBob) = init(aliceFundingAmount = 0.sat, alicePushAmount = 0.msat, bobPushAmount = 50_000_000.msat, channelOrigin = channelOrigin)
        val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(commitSigBob))
        assertIs<WaitForFundingConfirmed>(alice1.state)
        assertEquals(actionsAlice1.size, 6)
        actionsAlice1.hasOutgoingMessage<TxSignatures>()
        actionsAlice1.has<ChannelAction.Storage.StoreState>()
        actionsAlice1.find<ChannelAction.Storage.StoreIncomingPayment.ViaNewChannel>().also {
            assertEquals(50_000_000.msat, it.amount)
            assertEquals(channelOrigin, it.origin)
            assertEquals(alice1.commitments.latest.fundingTxId, it.txId)
        }
        actionsAlice1.hasWatch<WatchConfirmed>()
        val events = actionsAlice1.filterIsInstance<ChannelAction.EmitEvent>().map { it.event }
        assertTrue(events.any { it is ChannelEvents.Created })
        assertTrue(events.any { it is LiquidityEvents.Accepted })
    }

    @Test
    fun `recv CommitSig -- with channel origin -- dual-swap-in`() {
        val channelOrigin = Origin.OnChainWallet(setOf(), 200_000_000.msat, TransactionFees(750.sat, 0.sat))
        val (alice, _, _, commitSigBob) = init(aliceFundingAmount = 200_000.sat, alicePushAmount = 0.msat, bobFundingAmount = 500_000.sat, channelOrigin = channelOrigin)
        val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(commitSigBob))
        assertIs<WaitForFundingConfirmed>(alice1.state)
        assertEquals(actionsAlice1.size, 6)
        actionsAlice1.hasOutgoingMessage<TxSignatures>()
        actionsAlice1.has<ChannelAction.Storage.StoreState>()
        actionsAlice1.find<ChannelAction.Storage.StoreIncomingPayment.ViaNewChannel>().also {
            assertEquals(it.amount, 200_000_000.msat)
            assertEquals(it.origin, channelOrigin)
            assertTrue(it.localInputs.isNotEmpty())
        }
        actionsAlice1.hasWatch<WatchConfirmed>()
        val events = actionsAlice1.filterIsInstance<ChannelAction.EmitEvent>().map { it.event }
        assertTrue(events.any { it is ChannelEvents.Created })
        assertTrue(events.any { it is SwapInEvents.Accepted })
    }

    @Test
    fun `recv CommitSig -- with invalid signature`() {
        val (alice, commitSigAlice, bob, commitSigBob) = init()
        run {
            val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(commitSigBob.copy(signature = ByteVector64.Zeroes)))
            assertEquals(actionsAlice1.size, 2)
            actionsAlice1.hasOutgoingMessage<Error>()
            actionsAlice1.find<ChannelAction.Storage.RemoveChannel>().also { assertEquals(alice.channelId, it.data.channelId) }
            assertIs<Aborted>(alice1.state)
        }
        run {
            val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(commitSigAlice.copy(signature = ByteVector64.Zeroes)))
            assertEquals(actionsBob1.size, 2)
            actionsBob1.hasOutgoingMessage<Error>()
            actionsBob1.find<ChannelAction.Storage.RemoveChannel>().also { assertEquals(bob.channelId, it.data.channelId) }
            assertIs<Aborted>(bob1.state)
        }
    }

    @Test
    fun `recv TxSignatures`() {
        val (alice, commitSigAlice, bob, commitSigBob) = init()
        val commitInput = alice.state.signingSession.commitInput
        val txSigsBob = run {
            val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(commitSigAlice))
            assertIs<WaitForFundingConfirmed>(bob1.state)
            actionsBob1.hasOutgoingMessage<TxSignatures>()
        }
        run {
            val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(commitSigBob))
            assertIs<WaitForFundingSigned>(alice1.state)
            assertTrue(actionsAlice1.isEmpty())
            val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(txSigsBob))
            assertIs<WaitForFundingConfirmed>(alice2.state)
            assertEquals(6, actionsAlice2.size)
            assertTrue(actionsAlice2.hasOutgoingMessage<TxSignatures>().channelData.isEmpty())
            actionsAlice2.has<ChannelAction.Storage.StoreState>()
            val watchConfirmedAlice = actionsAlice2.findWatch<WatchConfirmed>()
            assertEquals(WatchConfirmed(alice2.channelId, commitInput.outPoint.txid, commitInput.txOut.publicKeyScript, 3, BITCOIN_FUNDING_DEPTHOK), watchConfirmedAlice)
            assertEquals(ChannelEvents.Created(alice2.state), actionsAlice2.find<ChannelAction.EmitEvent>().event)
            val fundingTx = actionsAlice2.find<ChannelAction.Blockchain.PublishTx>().tx
            assertEquals(fundingTx.txid, txSigsBob.txId)
            assertEquals(commitInput.outPoint.txid, fundingTx.txid)
        }
    }

    @Test
    fun `recv TxSignatures -- zero-conf`() {
        val (alice, commitSigAlice, bob, commitSigBob) = init(ChannelType.SupportedChannelType.AnchorOutputsZeroReserve, zeroConf = true)
        val txSigsBob = run {
            val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(commitSigAlice))
            assertIs<WaitForChannelReady>(bob1.state)
            actionsBob1.hasOutgoingMessage<TxSignatures>()
        }
        run {
            val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(commitSigBob))
            assertIs<WaitForFundingSigned>(alice1.state)
            assertTrue(actionsAlice1.isEmpty())
            val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(txSigsBob))
            assertIs<WaitForChannelReady>(alice2.state)
            assertEquals(7, actionsAlice2.size)
            assertTrue(actionsAlice2.hasOutgoingMessage<TxSignatures>().channelData.isEmpty())
            assertEquals(actionsAlice2.hasOutgoingMessage<ChannelReady>().alias, ShortChannelId.peerId(alice.staticParams.nodeParams.nodeId))
            assertEquals(actionsAlice2.findWatch<WatchConfirmed>().txId, alice2.commitments.latest.fundingTxId)
            actionsAlice2.has<ChannelAction.Storage.StoreState>()
            assertEquals(ChannelEvents.Created(alice2.state), actionsAlice2.find<ChannelAction.EmitEvent>().event)
            val fundingTx = actionsAlice2.find<ChannelAction.Blockchain.PublishTx>().tx
            assertEquals(fundingTx.txid, txSigsBob.txId)
        }
    }

    @Test
    fun `recv TxSignatures -- before CommitSig`() {
        val (alice, _, bob, _) = init()
        run {
            val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(TxSignatures(alice.channelId, TxId(randomBytes32()), listOf())))
            assertEquals(actionsAlice1.findOutgoingMessage<Error>().toAscii(), UnexpectedFundingSignatures(alice.channelId).message)
            assertIs<Aborted>(alice1.state)
        }
        run {
            val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(TxSignatures(bob.channelId, TxId(randomBytes32()), listOf())))
            assertEquals(actionsBob1.findOutgoingMessage<Error>().toAscii(), UnexpectedFundingSignatures(bob.channelId).message)
            assertIs<Aborted>(bob1.state)
        }
    }

    @Test
    fun `recv TxSignatures -- invalid`() {
        val (alice, _, _, commitSigBob) = init()
        val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(commitSigBob))
        assertIs<WaitForFundingSigned>(alice1.state)
        assertTrue(actionsAlice1.isEmpty())
        val invalidWitness = Script.witnessPay2wpkh(randomKey().publicKey(), randomBytes(72).byteVector())
        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(TxSignatures(alice.channelId, alice.state.signingSession.fundingTx.txId, listOf(invalidWitness))))
        assertIs<Aborted>(alice2.state)
        assertEquals(actionsAlice2.size, 2)
        actionsAlice2.hasOutgoingMessage<Error>()
        actionsAlice2.find<ChannelAction.Storage.RemoveChannel>().also { assertEquals(alice.channelId, it.data.channelId) }
    }

    @Test
    fun `recv TxAbort`() {
        val (alice, _, bob, _) = init()
        run {
            val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(TxAbort(alice.channelId, "changed my mind")))
            assertEquals(actionsAlice1.size, 1)
            actionsAlice1.hasOutgoingMessage<TxAbort>()
            assertIs<Aborted>(alice1.state)
        }
        run {
            val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(TxAbort(bob.channelId, "changed my mind")))
            assertEquals(actionsBob1.size, 1)
            actionsBob1.hasOutgoingMessage<TxAbort>()
            assertIs<Aborted>(bob1.state)
        }
    }

    @Test
    fun `recv TxInitRbf`() {
        val (alice, _, bob, _) = init()
        run {
            val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(TxInitRbf(alice.channelId, 0, FeeratePerKw(5000.sat))))
            assertEquals(actionsAlice1.size, 1)
            assertEquals(actionsAlice1.findOutgoingMessage<Warning>().toAscii(), InvalidRbfAttempt(alice.channelId).message)
            assertEquals(alice, alice1)
        }
        run {
            val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(TxInitRbf(bob.channelId, 0, FeeratePerKw(5000.sat))))
            assertEquals(actionsBob1.size, 1)
            assertEquals(actionsBob1.findOutgoingMessage<Warning>().toAscii(), InvalidRbfAttempt(bob.channelId).message)
            assertEquals(bob, bob1)
        }
    }

    @Test
    fun `recv TxAckRbf`() {
        val (alice, _, bob, _) = init()
        run {
            val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(TxAckRbf(alice.channelId)))
            assertEquals(actionsAlice1.size, 1)
            assertEquals(actionsAlice1.findOutgoingMessage<Warning>().toAscii(), InvalidRbfAttempt(alice.channelId).message)
            assertEquals(alice, alice1)
        }
        run {
            val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(TxAckRbf(bob.channelId)))
            assertEquals(actionsBob1.size, 1)
            assertEquals(actionsBob1.findOutgoingMessage<Warning>().toAscii(), InvalidRbfAttempt(bob.channelId).message)
            assertEquals(bob, bob1)
        }
    }

    @Test
    fun `recv ChannelCommand_Close_ForceClose`() {
        val (alice, _, _, _) = init()
        val (alice1, actions1) = alice.process(ChannelCommand.Close.ForceClose)
        assertEquals(actions1.findOutgoingMessage<Error>().toAscii(), ChannelFundingError(alice.channelId).message)
        assertIs<Aborted>(alice1.state)
    }

    @Test
    fun `recv Disconnected`() {
        val (alice, _, bob, _) = init()
        run {
            val (alice1, actions1) = alice.process(ChannelCommand.Disconnected)
            assertTrue(actions1.isEmpty())
            assertIs<Offline>(alice1.state)
            assertEquals(alice1.state.state, alice.state)
        }
        run {
            val (bob1, actions1) = bob.process(ChannelCommand.Disconnected)
            assertTrue(actions1.isEmpty())
            assertIs<Offline>(bob1.state)
            assertEquals(bob1.state.state, bob.state)
        }
    }

    companion object {
        data class Fixture(val alice: LNChannel<WaitForFundingSigned>, val commitSigAlice: CommitSig, val bob: LNChannel<WaitForFundingSigned>, val commitSigBob: CommitSig, val walletAlice: List<WalletState.Utxo>)

        fun init(
            channelType: ChannelType.SupportedChannelType = ChannelType.SupportedChannelType.AnchorOutputs,
            aliceFeatures: Features = TestConstants.Alice.nodeParams.features,
            bobFeatures: Features = TestConstants.Bob.nodeParams.features,
            currentHeight: Int = TestConstants.defaultBlockHeight,
            aliceFundingAmount: Satoshi = TestConstants.aliceFundingAmount,
            bobFundingAmount: Satoshi = TestConstants.bobFundingAmount,
            alicePushAmount: MilliSatoshi = TestConstants.alicePushAmount,
            bobPushAmount: MilliSatoshi = TestConstants.bobPushAmount,
            requestRemoteFunding: Satoshi? = null,
            zeroConf: Boolean = false,
            channelOrigin: Origin? = null
        ): Fixture {
            val (alice, bob, inputAlice, walletAlice) = WaitForFundingCreatedTestsCommon.init(
                channelType,
                aliceFeatures,
                bobFeatures,
                currentHeight,
                aliceFundingAmount,
                bobFundingAmount,
                alicePushAmount,
                bobPushAmount,
                requestRemoteFunding,
                zeroConf,
                channelOrigin
            )
            val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(inputAlice))
            // Bob's message will either be tx_add_input or tx_complete depending on whether Bob contributes or not.
            val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(actionsBob1.findOutgoingMessage<InteractiveTxMessage>()))
            val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(actionsAlice1.findOutgoingMessage<TxAddOutput>()))
            val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(actionsBob2.findOutgoingMessage<TxComplete>()))
            assertIs<LNChannel<WaitForFundingSigned>>(alice2)
            assertIs<WaitForFundingSigned>(alice2.state)
            val commitSigAlice = actionsAlice2.findOutgoingMessage<CommitSig>()
            assertTrue(commitSigAlice.channelData.isEmpty())
            actionsAlice2.has<ChannelAction.Storage.StoreState>()
            val (bob3, actionsBob3) = bob2.process(ChannelCommand.MessageReceived(actionsAlice2.findOutgoingMessage<TxComplete>()))
            assertIs<LNChannel<WaitForFundingSigned>>(bob3)
            assertIs<WaitForFundingSigned>(bob3.state)
            val commitSigBob = actionsBob3.findOutgoingMessage<CommitSig>()
            if (bob.staticParams.nodeParams.features.hasFeature(Feature.ChannelBackupClient)) {
                assertFalse(commitSigBob.channelData.isEmpty())
            }
            actionsBob3.has<ChannelAction.Storage.StoreState>()
            return Fixture(alice2, commitSigAlice, bob3, commitSigBob, walletAlice)
        }
    }

}