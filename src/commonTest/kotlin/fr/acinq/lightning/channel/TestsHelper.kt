package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.blockchain.*
import fr.acinq.lightning.blockchain.electrum.UnspentItem
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.states.WaitForChannelReadyTestsCommon
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.payment.OutgoingPaymentPacket
import fr.acinq.lightning.router.ChannelHop
import fr.acinq.lightning.serialization.Serialization
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.*
import fr.acinq.secp256k1.Hex
import org.kodein.memory.file.FileSystem
import org.kodein.memory.file.Path
import org.kodein.memory.file.openWriteableFile
import org.kodein.memory.file.resolve
import kotlin.test.*

// LN Message
internal inline fun <reified T : LightningMessage> List<ChannelAction>.findOutgoingMessages(): List<T> = filterIsInstance<ChannelAction.Message.Send>().map { it.message }.filterIsInstance<T>()
internal inline fun <reified T : LightningMessage> List<ChannelAction>.findOutgoingMessageOpt(): T? = findOutgoingMessages<T>().firstOrNull()
internal inline fun <reified T : LightningMessage> List<ChannelAction>.findOutgoingMessage(): T = findOutgoingMessageOpt<T>() ?: fail("cannot find outgoing message ${T::class}")
internal inline fun <reified T : LightningMessage> List<ChannelAction>.hasOutgoingMessage() = assertNotNull(findOutgoingMessageOpt<T>(), "cannot find outgoing message ${T::class}")

// Blockchain Watches
internal inline fun <reified T : Watch> List<ChannelAction>.findWatches(): List<T> = filterIsInstance<ChannelAction.Blockchain.SendWatch>().map { it.watch }.filterIsInstance<T>()
internal inline fun <reified T : Watch> List<ChannelAction>.findWatch(): T = findWatches<T>().firstOrNull() ?: fail("cannot find watch ${T::class}")
internal inline fun <reified T : Watch> List<ChannelAction>.hasWatch() = assertNotNull(findWatches<T>().firstOrNull(), "cannot find watch ${T::class}")

// Commands
internal inline fun <reified T : Command> List<ChannelAction>.findCommands(): List<T> = filterIsInstance<ChannelAction.Message.SendToSelf>().map { it.command }.filterIsInstance<T>()
internal inline fun <reified T : Command> List<ChannelAction>.findCommandOpt(): T? = findCommands<T>().firstOrNull()
internal inline fun <reified T : Command> List<ChannelAction>.findCommand(): T = findCommandOpt<T>() ?: fail("cannot find command ${T::class}")
internal inline fun <reified T : Command> List<ChannelAction>.hasCommand() = assertNotNull(findCommandOpt<T>(), "cannot find command ${T::class}")

// Transactions
internal fun List<ChannelAction>.findTxs(): List<Transaction> = filterIsInstance<ChannelAction.Blockchain.PublishTx>().map { it.tx }
internal fun List<ChannelAction>.hasTx(tx: Transaction) = assertTrue(findTxs().contains(tx))

// Errors
internal inline fun <reified T : Throwable> List<ChannelAction>.findErrorOpt(): T? = filterIsInstance<ChannelAction.ProcessLocalError>().map { it.error }.filterIsInstance<T>().firstOrNull()
internal inline fun <reified T : Throwable> List<ChannelAction>.findError(): T = findErrorOpt<T>() ?: fail("cannot find error ${T::class}")
internal inline fun <reified T : Throwable> List<ChannelAction>.hasError() = assertNotNull(findErrorOpt<T>(), "cannot find error ${T::class}")

internal inline fun <reified T : ChannelException> List<ChannelAction>.findCommandErrorOpt(): T? {
    val cmdAddError = filterIsInstance<ChannelAction.ProcessCmdRes.AddFailed>().map { it.error }.filterIsInstance<T>().firstOrNull()
    val otherCmdError = filterIsInstance<ChannelAction.ProcessCmdRes.NotExecuted>().map { it.t }.filterIsInstance<T>().firstOrNull()
    return cmdAddError ?: otherCmdError
}

internal inline fun <reified T : ChannelException> List<ChannelAction>.findCommandError(): T = findCommandErrorOpt<T>() ?: fail("cannot find command error ${T::class}")
internal inline fun <reified T : ChannelException> List<ChannelAction>.hasCommandError() = assertNotNull(findCommandErrorOpt<T>(), "cannot find command error ${T::class}")

internal inline fun <reified T : ChannelAction> List<ChannelAction>.findOpt(): T? = filterIsInstance<T>().firstOrNull()
internal inline fun <reified T : ChannelAction> List<ChannelAction>.find() = findOpt<T>() ?: fail("cannot find action ${T::class}")
internal inline fun <reified T : ChannelAction> List<ChannelAction>.has() = assertTrue { any { it is T } }
internal inline fun <reified T : ChannelAction> List<ChannelAction>.doesNotHave() = assertTrue { none { it is T } }

fun Normal.updateFeerate(feerate: FeeratePerKw): Normal = this.copy(currentOnChainFeerates = OnChainFeerates(feerate, feerate, feerate))
fun Negotiating.updateFeerate(feerate: FeeratePerKw): Negotiating = this.copy(currentOnChainFeerates = OnChainFeerates(feerate, feerate, feerate))

fun Features.add(vararg pairs: Pair<Feature, FeatureSupport>): Features = this.copy(activated = this.activated + mapOf(*pairs))
fun Features.remove(vararg features: Feature): Features = this.copy(activated = activated.filterKeys { f -> !features.contains(f) })

object TestsHelper {

    fun init(
        channelType: ChannelType.SupportedChannelType = ChannelType.SupportedChannelType.AnchorOutputs,
        aliceFeatures: Features = TestConstants.Alice.nodeParams.features,
        bobFeatures: Features = TestConstants.Bob.nodeParams.features,
        currentHeight: Int = TestConstants.defaultBlockHeight,
        aliceFundingAmount: Satoshi = TestConstants.aliceFundingAmount,
        bobFundingAmount: Satoshi = TestConstants.bobFundingAmount,
        alicePushAmount: MilliSatoshi = TestConstants.alicePushAmount,
        bobPushAmount: MilliSatoshi = TestConstants.bobPushAmount,
        zeroConf: Boolean = false,
        channelOrigin: ChannelOrigin? = null
    ): Triple<WaitForAcceptChannel, WaitForOpenChannel, OpenDualFundedChannel> {
        val (aliceNodeParams, bobNodeParams) = when (zeroConf) {
            true -> Pair(
                TestConstants.Alice.nodeParams.copy(features = aliceFeatures, zeroConfPeers = setOf(TestConstants.Bob.nodeParams.nodeId)),
                TestConstants.Bob.nodeParams.copy(features = bobFeatures, zeroConfPeers = setOf(TestConstants.Alice.nodeParams.nodeId))
            )
            false -> Pair(
                TestConstants.Alice.nodeParams.copy(features = aliceFeatures),
                TestConstants.Bob.nodeParams.copy(features = bobFeatures)
            )
        }
        val alice = WaitForInit(
            StaticParams(aliceNodeParams, TestConstants.Bob.keyManager.nodeId),
            currentTip = Pair(currentHeight, Block.RegtestGenesisBlock.header),
            currentOnChainFeerates = OnChainFeerates(TestConstants.feeratePerKw, TestConstants.feeratePerKw, TestConstants.feeratePerKw)
        )
        val bob = WaitForInit(
            StaticParams(bobNodeParams, TestConstants.Alice.keyManager.nodeId),
            currentTip = Pair(currentHeight, Block.RegtestGenesisBlock.header),
            currentOnChainFeerates = OnChainFeerates(TestConstants.feeratePerKw, TestConstants.feeratePerKw, TestConstants.feeratePerKw)
        )
        val channelFlags = 0.toByte()
        val aliceChannelParams = TestConstants.Alice.channelParams().copy(features = aliceFeatures)
        val bobChannelParams = TestConstants.Bob.channelParams().copy(features = bobFeatures)
        val aliceInit = Init(aliceFeatures.toByteArray().toByteVector())
        val bobInit = Init(bobFeatures.toByteArray().toByteVector())
        val (alice1, actionsAlice1) = alice.process(
            ChannelCommand.InitInitiator(
                aliceFundingAmount,
                alicePushAmount,
                createWallet(aliceNodeParams.keyManager, aliceFundingAmount + 3500.sat).second,
                FeeratePerKw.CommitmentFeerate,
                TestConstants.feeratePerKw,
                aliceChannelParams,
                bobInit,
                channelFlags,
                ChannelConfig.standard,
                channelType,
                channelOrigin
            )
        )
        assertIs<WaitForAcceptChannel>(alice1)
        val bobWallet = if (bobFundingAmount > 0.sat) createWallet(bobNodeParams.keyManager, bobFundingAmount + 1500.sat).second else WalletState.empty
        val (bob1, _) = bob.process(ChannelCommand.InitNonInitiator(aliceChannelParams.channelKeys.temporaryChannelId, bobFundingAmount, bobPushAmount, bobWallet, bobChannelParams, ChannelConfig.standard, aliceInit))
        assertIs<WaitForOpenChannel>(bob1)
        val open = actionsAlice1.findOutgoingMessage<OpenDualFundedChannel>()
        return Triple(alice1, bob1, open)
    }

    fun reachNormal(
        channelType: ChannelType.SupportedChannelType = ChannelType.SupportedChannelType.AnchorOutputs,
        aliceFeatures: Features = TestConstants.Alice.nodeParams.features,
        bobFeatures: Features = TestConstants.Bob.nodeParams.features,
        currentHeight: Int = TestConstants.defaultBlockHeight,
        aliceFundingAmount: Satoshi = TestConstants.aliceFundingAmount,
        bobFundingAmount: Satoshi = TestConstants.bobFundingAmount,
        alicePushAmount: MilliSatoshi = TestConstants.alicePushAmount,
        bobPushAmount: MilliSatoshi = TestConstants.bobPushAmount,
        zeroConf: Boolean = false,
    ): Triple<Normal, Normal, Transaction> {
        val (alice, channelReadyAlice, bob, channelReadyBob) = WaitForChannelReadyTestsCommon.init(channelType, aliceFeatures, bobFeatures, currentHeight, aliceFundingAmount, bobFundingAmount, alicePushAmount, bobPushAmount, zeroConf)
        val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(channelReadyBob))
        assertIs<Normal>(alice1)
        assertEquals(actionsAlice1.findWatch<WatchConfirmed>().event, BITCOIN_FUNDING_DEEPLYBURIED)
        actionsAlice1.has<ChannelAction.Storage.StoreState>()
        val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(channelReadyAlice))
        assertIs<Normal>(bob1)
        assertEquals(actionsBob1.findWatch<WatchConfirmed>().event, BITCOIN_FUNDING_DEEPLYBURIED)
        actionsBob1.has<ChannelAction.Storage.StoreState>()
        return Triple(alice1, bob1, alice.fundingTx.tx.buildUnsignedTx())
    }

    fun mutualCloseAlice(alice: Normal, bob: Normal, scriptPubKey: ByteVector? = null, feerates: ClosingFeerates? = null): Triple<Negotiating, Negotiating, ClosingSigned> {
        val (alice1, actionsAlice1) = alice.process(ChannelCommand.ExecuteCommand(CMD_CLOSE(scriptPubKey, feerates)))
        assertTrue(alice1 is Normal)
        val shutdownAlice = actionsAlice1.findOutgoingMessage<Shutdown>()
        assertNull(actionsAlice1.findOutgoingMessageOpt<ClosingSigned>())

        val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(shutdownAlice))
        assertTrue(bob1 is Negotiating)
        val shutdownBob = actionsBob1.findOutgoingMessage<Shutdown>()
        assertNull(actionsBob1.findOutgoingMessageOpt<ClosingSigned>())

        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(shutdownBob))
        assertTrue(alice2 is Negotiating)
        val closingSignedAlice = actionsAlice2.findOutgoingMessage<ClosingSigned>()
        return Triple(alice2, bob1, closingSignedAlice)
    }

    fun mutualCloseBob(alice: Normal, bob: Normal, scriptPubKey: ByteVector? = null, feerates: ClosingFeerates? = null): Triple<Negotiating, Negotiating, ClosingSigned> {
        val (bob1, actionsBob1) = bob.process(ChannelCommand.ExecuteCommand(CMD_CLOSE(scriptPubKey, feerates)))
        assertTrue(bob1 is Normal)
        val shutdownBob = actionsBob1.findOutgoingMessage<Shutdown>()
        assertNull(actionsBob1.findOutgoingMessageOpt<ClosingSigned>())

        val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(shutdownBob))
        assertTrue(alice1 is Negotiating)
        val shutdownAlice = actionsAlice1.findOutgoingMessage<Shutdown>()
        val closingSignedAlice = actionsAlice1.findOutgoingMessage<ClosingSigned>()

        val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(shutdownAlice))
        assertTrue(bob2 is Negotiating)
        assertNull(actionsBob2.findOutgoingMessageOpt<ClosingSigned>())
        return Triple(alice1, bob2, closingSignedAlice)
    }

    fun localClose(s: ChannelState): Pair<Closing, LocalCommitPublished> {
        assertTrue(s is ChannelStateWithCommitments)
        assertEquals(ChannelType.SupportedChannelType.AnchorOutputs, s.commitments.channelFeatures.channelType)
        // an error occurs and s publishes their commit tx
        val commitTx = s.commitments.localCommit.publishableTxs.commitTx.tx
        val (s1, actions1) = s.process(ChannelCommand.MessageReceived(Error(ByteVector32.Zeroes, "oops")))
        assertTrue(s1 is Closing)
        actions1.has<ChannelAction.Storage.StoreState>()
        actions1.has<ChannelAction.Storage.StoreChannelClosing>()

        val localCommitPublished = s1.localCommitPublished
        assertNotNull(localCommitPublished)
        assertEquals(commitTx, localCommitPublished.commitTx)
        actions1.hasTx(commitTx)
        assertNotNull(localCommitPublished.claimMainDelayedOutputTx)
        actions1.hasTx(localCommitPublished.claimMainDelayedOutputTx!!.tx)
        Transaction.correctlySpends(localCommitPublished.claimMainDelayedOutputTx!!.tx, localCommitPublished.commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        // all htlcs success/timeout should be published
        localCommitPublished.htlcTxs.values.filterNotNull().forEach { htlcTx ->
            Transaction.correctlySpends(htlcTx.tx, localCommitPublished.commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            actions1.hasTx(htlcTx.tx)
        }
        // and their outputs should be claimed
        localCommitPublished.claimHtlcDelayedTxs.forEach { claimHtlcDelayed -> actions1.hasTx(claimHtlcDelayed.tx) }

        // we watch the confirmation of the "final" transactions that send funds to our wallets (main delayed output and 2nd stage htlc transactions)
        val expectedWatchConfirmed = buildSet {
            add(BITCOIN_TX_CONFIRMED(localCommitPublished.commitTx))
            add(BITCOIN_TX_CONFIRMED(localCommitPublished.claimMainDelayedOutputTx!!.tx))
            addAll(localCommitPublished.claimHtlcDelayedTxs.map { BITCOIN_TX_CONFIRMED(it.tx) })
        }
        assertEquals(expectedWatchConfirmed, actions1.findWatches<WatchConfirmed>().map { it.event }.toSet())

        // we watch outputs of the commitment tx that both parties may spend
        val watchSpent = actions1.findWatches<WatchSpent>()
        watchSpent.forEach { watch ->
            assertEquals(BITCOIN_OUTPUT_SPENT, watch.event)
            assertEquals(watch.txId, commitTx.txid)
        }
        assertEquals(localCommitPublished.htlcTxs.keys, watchSpent.map { OutPoint(commitTx, it.outputIndex.toLong()) }.toSet())

        return s1 to localCommitPublished
    }

    fun remoteClose(rCommitTx: Transaction, s: ChannelState): Pair<Closing, RemoteCommitPublished> {
        assertTrue(s is ChannelStateWithCommitments)
        assertEquals(ChannelType.SupportedChannelType.AnchorOutputs, s.commitments.channelFeatures.channelType)
        // we make s believe r unilaterally closed the channel
        val (s1, actions1) = s.process(ChannelCommand.WatchReceived(WatchEventSpent(s.channelId, BITCOIN_FUNDING_SPENT, rCommitTx)))
        assertTrue(s1 is Closing)

        if (s !is Closing) {
            val channelBalance = s.commitments.localCommit.spec.toLocal
            if (channelBalance > 0.msat) {
                actions1.has<ChannelAction.Storage.StoreChannelClosing>()
            }
        }

        val remoteCommitPublished = s1.remoteCommitPublished ?: s1.nextRemoteCommitPublished ?: s1.futureRemoteCommitPublished
        assertNotNull(remoteCommitPublished)
        assertNull(s1.localCommitPublished)

        // if s has a main output in the commit tx (when it has a non-dust balance), it should be claimed
        remoteCommitPublished.claimMainOutputTx?.let { claimMain ->
            Transaction.correctlySpends(claimMain.tx, rCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            actions1.hasTx(claimMain.tx)
        }
        // all htlcs success/timeout should be claimed
        remoteCommitPublished.claimHtlcTxs.values.filterNotNull().forEach { claimHtlc ->
            Transaction.correctlySpends(claimHtlc.tx, rCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            actions1.hasTx(claimHtlc.tx)
        }

        // we watch the confirmation of the "final" transactions that send funds to our wallets (main delayed output and 2nd stage htlc transactions)
        val watchConfirmedList = actions1.findWatches<WatchConfirmed>()
        assertEquals(BITCOIN_TX_CONFIRMED(rCommitTx), watchConfirmedList.first().event)
        remoteCommitPublished.claimMainOutputTx?.let { claimMain ->
            assertEquals(BITCOIN_TX_CONFIRMED(claimMain.tx), watchConfirmedList.drop(1).first().event)
        }

        // we watch outputs of the commitment tx that both parties may spend
        val watchSpent = actions1.findWatches<WatchSpent>()
        watchSpent.forEach { watch ->
            assertEquals(BITCOIN_OUTPUT_SPENT, watch.event)
            assertEquals(watch.txId, rCommitTx.txid)
        }
        assertEquals(remoteCommitPublished.claimHtlcTxs.keys, watchSpent.map { OutPoint(rCommitTx, it.outputIndex.toLong()) }.toSet())

        // s is now in CLOSING state with txs pending for confirmation before going in CLOSED state
        return s1 to remoteCommitPublished
    }

    fun signAndRevack(alice: ChannelState, bob: ChannelState): Pair<ChannelState, ChannelState> {
        val (alice1, actions1) = alice.process(ChannelCommand.ExecuteCommand(CMD_SIGN))
        val commitSig = actions1.findOutgoingMessage<CommitSig>()
        val (bob1, actions2) = bob.process(ChannelCommand.MessageReceived(commitSig))
        val revack = actions2.findOutgoingMessage<RevokeAndAck>()
        val (alice2, _) = alice1.process(ChannelCommand.MessageReceived(revack))
        return Pair(alice2, bob1)
    }

    fun makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: Long, paymentPreimage: ByteVector32 = randomBytes32(), paymentId: UUID = UUID.randomUUID()): Pair<ByteVector32, CMD_ADD_HTLC> {
        val paymentHash: ByteVector32 = Crypto.sha256(paymentPreimage).toByteVector32()
        val expiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight)
        val dummyKey = PrivateKey(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")).publicKey()
        val dummyUpdate = ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId(144, 0, 0), 0, 0, 0, CltvExpiryDelta(1), 0.msat, 0.msat, 0, null)
        val cmd = OutgoingPaymentPacket.buildCommand(
            paymentId,
            paymentHash,
            listOf(ChannelHop(dummyKey, destination, dummyUpdate)),
            PaymentOnion.FinalPayload.createSinglePartPayload(amount, expiry, randomBytes32(), null)
        ).first.copy(commit = false)
        return Pair(paymentPreimage, cmd)
    }

    fun createWallet(keyManager: KeyManager, amount: Satoshi): Pair<PrivateKey, WalletState> {
        val privKey = keyManager.bip84PrivateKey(account = 1, addressIndex = 0)
        val address = Bitcoin.computeP2WpkhAddress(privKey.publicKey(), Block.RegtestGenesisBlock.hash)
        val parentTx = Transaction(2, listOf(TxIn(OutPoint(randomBytes32(), 3), 0)), listOf(TxOut(amount, Script.pay2wpkh(privKey.publicKey()))), 0)
        return privKey to WalletState(mapOf(address to listOf(UnspentItem(parentTx.txid, 0, amount.toLong(), 654321))), mapOf(parentTx.txid to parentTx))
    }

    fun addHtlc(amount: MilliSatoshi, payer: ChannelState, payee: ChannelState): Triple<Pair<ChannelState, ChannelState>, ByteVector32, UpdateAddHtlc> {
        val currentBlockHeight = payer.currentBlockHeight.toLong()
        val (paymentPreimage, cmd) = makeCmdAdd(amount, payee.staticParams.nodeParams.nodeId, currentBlockHeight)
        val (sender0, receiver0, htlc) = addHtlc(cmd, payer, payee)
        return Triple(sender0 to receiver0, paymentPreimage, htlc)
    }

    fun addHtlc(cmdAdd: CMD_ADD_HTLC, payer: ChannelState, payee: ChannelState): Triple<ChannelState, ChannelState, UpdateAddHtlc> {
        val (sender0, senderActions0) = payer.process(ChannelCommand.ExecuteCommand(cmdAdd))
        val htlc = senderActions0.findOutgoingMessage<UpdateAddHtlc>()

        val (receiver0, _) = payee.process(ChannelCommand.MessageReceived(htlc))
        assertTrue(receiver0 is ChannelStateWithCommitments)
        assertTrue(receiver0.commitments.remoteChanges.proposed.contains(htlc))

        return Triple(sender0, receiver0, htlc)
    }

    fun fulfillHtlc(id: Long, paymentPreimage: ByteVector32, payer: ChannelState, payee: ChannelState): Pair<ChannelState, ChannelState> {
        val (payee0, payeeActions0) = payee.process(ChannelCommand.ExecuteCommand(CMD_FULFILL_HTLC(id, paymentPreimage)))
        val fulfillHtlc = payeeActions0.findOutgoingMessage<UpdateFulfillHtlc>()

        val (payer0, _) = payer.process(ChannelCommand.MessageReceived(fulfillHtlc))
        assertTrue(payer0 is ChannelStateWithCommitments)
        assertTrue(payer0.commitments.remoteChanges.proposed.contains(fulfillHtlc))

        return payer0 to payee0
    }

    fun failHtlc(id: Long, payer: ChannelState, payee: ChannelState): Pair<ChannelState, ChannelState> {
        val (payee0, payeeActions0) = payee.process(ChannelCommand.ExecuteCommand(CMD_FAIL_HTLC(id, CMD_FAIL_HTLC.Reason.Failure(TemporaryNodeFailure))))
        val failHtlc = payeeActions0.findOutgoingMessage<UpdateFailHtlc>()

        val (payer0, _) = payer.process(ChannelCommand.MessageReceived(failHtlc))
        assertTrue(payer0 is ChannelStateWithCommitments)
        assertTrue(payer0.commitments.remoteChanges.proposed.contains(failHtlc))

        return payer0 to payee0
    }

    /**
     * Cross sign nodes where nodeA initiate the signature exchange
     */
    fun crossSign(nodeA: ChannelState, nodeB: ChannelState): Pair<ChannelState, ChannelState> {
        assertTrue(nodeA is ChannelStateWithCommitments)
        assertTrue(nodeB is ChannelStateWithCommitments)

        val sCommitIndex = nodeA.commitments.localCommit.index
        val rCommitIndex = nodeB.commitments.localCommit.index
        val rHasChanges = nodeB.commitments.localHasChanges()

        val (sender0, sActions0) = nodeA.process(ChannelCommand.ExecuteCommand(CMD_SIGN))
        val commitSig0 = sActions0.findOutgoingMessage<CommitSig>()

        val (receiver0, rActions0) = nodeB.process(ChannelCommand.MessageReceived(commitSig0))
        val revokeAndAck0 = rActions0.findOutgoingMessage<RevokeAndAck>()
        val commandSign0 = rActions0.findCommand<CMD_SIGN>()

        val (sender1, _) = sender0.process(ChannelCommand.MessageReceived(revokeAndAck0))
        val (receiver1, rActions1) = receiver0.process(ChannelCommand.ExecuteCommand(commandSign0))
        val commitSig1 = rActions1.findOutgoingMessage<CommitSig>()

        val (sender2, sActions2) = sender1.process(ChannelCommand.MessageReceived(commitSig1))
        val revokeAndAck1 = sActions2.findOutgoingMessage<RevokeAndAck>()
        val (receiver2, _) = receiver1.process(ChannelCommand.MessageReceived(revokeAndAck1))

        if (rHasChanges) {
            val commandSign1 = sActions2.findCommand<CMD_SIGN>()
            val (sender3, sActions3) = sender2.process(ChannelCommand.ExecuteCommand(commandSign1))
            val commitSig2 = sActions3.findOutgoingMessage<CommitSig>()

            val (receiver3, rActions3) = receiver2.process(ChannelCommand.MessageReceived(commitSig2))
            val revokeAndAck2 = rActions3.findOutgoingMessage<RevokeAndAck>()
            val (sender4, _) = sender3.process(ChannelCommand.MessageReceived(revokeAndAck2))

            sender4 as ChannelStateWithCommitments; receiver3 as ChannelStateWithCommitments
            assertEquals(sCommitIndex + 1, sender4.commitments.localCommit.index)
            assertEquals(sCommitIndex + 2, sender4.commitments.remoteCommit.index)
            assertEquals(rCommitIndex + 2, receiver3.commitments.localCommit.index)
            assertEquals(rCommitIndex + 1, receiver3.commitments.remoteCommit.index)

            return sender4 to receiver3
        } else {
            sender2 as ChannelStateWithCommitments; receiver2 as ChannelStateWithCommitments
            assertEquals(sCommitIndex + 1, sender2.commitments.localCommit.index)
            assertEquals(sCommitIndex + 1, sender2.commitments.remoteCommit.index)
            assertEquals(rCommitIndex + 1, receiver2.commitments.localCommit.index)
            assertEquals(rCommitIndex + 1, receiver2.commitments.remoteCommit.index)

            return sender2 to receiver2
        }
    }

    fun LocalCommitPublished.htlcSuccessTxs(): List<Transactions.TransactionWithInputInfo.HtlcTx.HtlcSuccessTx> {
        return htlcTxs.values.filterIsInstance<Transactions.TransactionWithInputInfo.HtlcTx.HtlcSuccessTx>()
    }

    fun LocalCommitPublished.htlcTimeoutTxs(): List<Transactions.TransactionWithInputInfo.HtlcTx.HtlcTimeoutTx> {
        return htlcTxs.values.filterIsInstance<Transactions.TransactionWithInputInfo.HtlcTx.HtlcTimeoutTx>()
    }

    fun RemoteCommitPublished.claimHtlcSuccessTxs(): List<Transactions.TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcSuccessTx> {
        return claimHtlcTxs.values.filterIsInstance<Transactions.TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcSuccessTx>()
    }

    fun RemoteCommitPublished.claimHtlcTimeoutTxs(): List<Transactions.TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcTimeoutTx> {
        return claimHtlcTxs.values.filterIsInstance<Transactions.TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcTimeoutTx>()
    }

    // we check that serialization works by checking that deserialize(serialize(state)) == state
    private fun checkSerialization(state: ChannelStateWithCommitments, minVersion: Int = 1, saveFiles: Boolean = false) {
        fun save(blob: ByteArray, suffix: String) {
            val name = (state::class.simpleName ?: "serialized") + "_${Hex.encode(Crypto.sha256(blob).take(8).toByteArray())}.$suffix"
            val file: Path = FileSystem.workingDir().resolve(name)
            file.openWriteableFile(false).putBytes(blob)
        }

        // Before v3, we had a single set of hard-coded channel features, so they will not match if the test added new channel features that weren't supported then.
        fun maskChannelFeatures(state: ChannelStateWithCommitments): ChannelStateWithCommitments = when (state) {
            is WaitForRemotePublishFutureCommitment -> state.copy(commitments = state.commitments.copy(channelFeatures = ChannelFeatures(ChannelType.SupportedChannelType.AnchorOutputs.features)))
            is LegacyWaitForFundingConfirmed -> state.copy(commitments = state.commitments.copy(channelFeatures = ChannelFeatures(ChannelType.SupportedChannelType.AnchorOutputs.features)))
            is WaitForFundingConfirmed -> state.copy(commitments = state.commitments.copy(channelFeatures = ChannelFeatures(ChannelType.SupportedChannelType.AnchorOutputs.features)))
            is LegacyWaitForFundingLocked -> state.copy(commitments = state.commitments.copy(channelFeatures = ChannelFeatures(ChannelType.SupportedChannelType.AnchorOutputs.features)))
            is WaitForChannelReady -> state.copy(commitments = state.commitments.copy(channelFeatures = ChannelFeatures(ChannelType.SupportedChannelType.AnchorOutputs.features)))
            is Normal -> state.copy(commitments = state.commitments.copy(channelFeatures = ChannelFeatures(ChannelType.SupportedChannelType.AnchorOutputs.features)))
            is ShuttingDown -> state.copy(commitments = state.commitments.copy(channelFeatures = ChannelFeatures(ChannelType.SupportedChannelType.AnchorOutputs.features)))
            is Negotiating -> state.copy(commitments = state.commitments.copy(channelFeatures = ChannelFeatures(ChannelType.SupportedChannelType.AnchorOutputs.features)))
            is Closing -> state.copy(commitments = state.commitments.copy(channelFeatures = ChannelFeatures(ChannelType.SupportedChannelType.AnchorOutputs.features)))
            is Closed -> state.copy(state = state.state.copy(commitments = state.commitments.copy(channelFeatures = ChannelFeatures(ChannelType.SupportedChannelType.AnchorOutputs.features))))
            is ErrorInformationLeak -> state.copy(commitments = state.commitments.copy(channelFeatures = ChannelFeatures(ChannelType.SupportedChannelType.AnchorOutputs.features)))
        }

        // We never persist a funding RBF attempt.
        fun removeRbfAttempt(state: ChannelStateWithCommitments): ChannelStateWithCommitments = when (state) {
            is WaitForFundingConfirmed -> state.copy(rbfStatus = WaitForFundingConfirmed.Companion.RbfStatus.None)
            else -> state
        }

        if (saveFiles) {
            if (minVersion <= 1) save(fr.acinq.lightning.serialization.v1.Serialization.serialize(state), "v1")
            if (minVersion <= 2) save(fr.acinq.lightning.serialization.v2.Serialization.serialize(state), "v2")
            if (minVersion <= 3) save(fr.acinq.lightning.serialization.v3.Serialization.serialize(state), "v3")
        }

        if (minVersion <= 1) {
            val serializedv1 = fr.acinq.lightning.serialization.v1.Serialization.serialize(state)
            val deserializedv1 = Serialization.deserialize(serializedv1, state.staticParams.nodeParams)
            assertEquals(maskChannelFeatures(deserializedv1), maskChannelFeatures(state), "serialization error (v1)")
        }
        if (minVersion <= 2) {
            val serializedv2 = fr.acinq.lightning.serialization.v2.Serialization.serialize(state)
            val deserializedv2 = Serialization.deserialize(serializedv2, state.staticParams.nodeParams)
            assertEquals(maskChannelFeatures(deserializedv2), maskChannelFeatures(state), "serialization error (v2)")
        }
        if (minVersion <= 3) {
            val serializedv3 = fr.acinq.lightning.serialization.v3.Serialization.serialize(state)
            val deserializedv3 = Serialization.deserialize(serializedv3, state.staticParams.nodeParams)
            assertEquals(deserializedv3, removeRbfAttempt(state), "serialization error (v3)")
        }
    }

    private fun checkSerialization(actions: List<ChannelAction>, minVersion: Int = 1) {
        // we check that serialization works everytime we're suppose to persist channel data
        actions.filterIsInstance<ChannelAction.Storage.StoreState>().forEach { checkSerialization(it.data, minVersion) }
    }

    // test-specific extension that allows for extra checks during tests
    fun ChannelState.processEx(event: ChannelCommand, minVersion: Int = 1): Pair<ChannelState, List<ChannelAction>> {
        val result = this.process(event)
        checkSerialization(result.second, minVersion)
        return result
    }

}
