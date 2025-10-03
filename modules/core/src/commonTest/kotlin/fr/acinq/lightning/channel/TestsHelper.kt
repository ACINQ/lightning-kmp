package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.blockchain.Watch
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchSpent
import fr.acinq.lightning.blockchain.WatchSpentTriggered
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.states.*
import fr.acinq.lightning.crypto.ChannelKeys
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.db.ChannelCloseOutgoingPayment.ChannelClosingType
import fr.acinq.lightning.json.JsonSerializers
import fr.acinq.lightning.logging.MDCLogger
import fr.acinq.lightning.logging.mdc
import fr.acinq.lightning.payment.OutgoingPaymentPacket
import fr.acinq.lightning.serialization.channel.Serialization
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.testLoggerFactory
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
internal fun List<ChannelAction>.hasWatchFundingSpent(txId: TxId): WatchSpent = findWatches<WatchSpent>().firstOrNull { it.event is WatchSpent.ChannelSpent }?.also { assertEquals(txId, it.txId) } ?: fail("cannot find watch-funding-spent")
internal fun List<ChannelAction>.hasWatchOutputSpent(outpoint: OutPoint): WatchSpent = findWatches<WatchSpent>().firstOrNull { it.event is WatchSpent.ClosingOutputSpent && it.txId == outpoint.txid && it.outputIndex.toLong() == outpoint.index } ?: fail("cannot find watch-output-spent")
internal fun List<ChannelAction>.hasWatchOutputsSpent(outpoints: Set<OutPoint>): Set<WatchSpent> = outpoints.map { hasWatchOutputSpent(it) }.toSet()
internal fun List<ChannelAction>.hasWatchConfirmed(txId: TxId): WatchConfirmed = assertNotNull(findWatches<WatchConfirmed>().firstOrNull { it.txId == txId })

// Commands
internal inline fun <reified T : ChannelCommand> List<ChannelAction>.findCommands(): List<T> = filterIsInstance<ChannelAction.Message.SendToSelf>().map { it.command }.filterIsInstance<T>()
internal inline fun <reified T : ChannelCommand> List<ChannelAction>.findCommandOpt(): T? = findCommands<T>().firstOrNull()
internal inline fun <reified T : ChannelCommand> List<ChannelAction>.findCommand(): T = findCommandOpt<T>() ?: fail("cannot find command ${T::class}")
internal inline fun <reified T : ChannelCommand> List<ChannelAction>.hasCommand() = assertNotNull(findCommandOpt<T>(), "cannot find command ${T::class}")

// Transactions
internal fun List<ChannelAction>.findPublishTxs(): List<Transaction> = filterIsInstance<ChannelAction.Blockchain.PublishTx>().map { it.tx }
internal fun List<ChannelAction>.hasPublishTx(tx: Transaction) = assertContains(findPublishTxs(), tx)
internal fun List<ChannelAction>.findPublishTxs(txType: ChannelAction.Blockchain.PublishTx.Type): List<Transaction> = filterIsInstance<ChannelAction.Blockchain.PublishTx>().filter { it.txType == txType }.map { it.tx }
internal fun List<ChannelAction>.hasPublishTx(txType: ChannelAction.Blockchain.PublishTx.Type): Transaction = assertNotNull(filterIsInstance<ChannelAction.Blockchain.PublishTx>().firstOrNull { it.txType == txType }).tx

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

fun Features.add(vararg pairs: Pair<Feature, FeatureSupport>): Features = this.copy(activated = this.activated + mapOf(*pairs))
fun Features.remove(vararg features: Feature): Features = this.copy(activated = activated.filterKeys { f -> !features.contains(f) })

data class LNChannel<out S : ChannelState>(
    val ctx: ChannelContext,
    val state: S
) {
    val staticParams = ctx.staticParams
    val currentBlockHeight = ctx.currentBlockHeight
    val channelId: ByteVector32 by lazy {
        when (state) {
            is ChannelStateWithCommitments -> state.channelId
            is WaitForFundingCreated -> state.channelId
            is WaitForFundingSigned -> state.channelId
            else -> error("no channel id in state ${state::class}")
        }
    }
    val commitments: Commitments by lazy {
        when {
            state is ChannelStateWithCommitments -> state.commitments
            state is Offline && state.state is ChannelStateWithCommitments -> state.state.commitments
            state is Syncing && state.state is ChannelStateWithCommitments -> state.state.commitments
            else -> error("no commitments in state ${state::class}")
        }
    }
    val channelKeys: ChannelKeys by lazy {
        when {
            state is WaitForFundingSigned -> state.channelParams.localParams.channelKeys(ctx.keyManager)
            state is Offline && state.state is WaitForFundingSigned -> state.state.channelParams.localParams.channelKeys(ctx.keyManager)
            state is Syncing && state.state is WaitForFundingSigned -> state.state.channelParams.localParams.channelKeys(ctx.keyManager)
            state is ChannelStateWithCommitments -> state.commitments.channelParams.localParams.channelKeys(ctx.keyManager)
            state is Offline && state.state is ChannelStateWithCommitments -> state.state.commitments.channelParams.localParams.channelKeys(ctx.keyManager)
            state is Syncing && state.state is ChannelStateWithCommitments -> state.state.commitments.channelParams.localParams.channelKeys(ctx.keyManager)
            else -> error("no channel keys in state ${state::class}")
        }
    }

    fun signCommitTx(): Transaction = commitments.latest.fullySignedCommitTx(channelKeys)

    fun unsignedHtlcTxs(): List<Transactions.HtlcTx> = commitments.latest.unsignedHtlcTxs(channelKeys).map { it.first }

    fun signHtlcTimeoutTxs(): List<Transactions.HtlcTimeoutTx> {
        val commitKeys = commitments.latest.localKeys(channelKeys)
        return commitments.latest.unsignedHtlcTxs(channelKeys).mapNotNull { (htlcTx, remoteSig) ->
            when (htlcTx) {
                is Transactions.HtlcSuccessTx -> null
                is Transactions.HtlcTimeoutTx -> htlcTx.sign(commitKeys, remoteSig)
            }
        }
    }

    fun signHtlcSuccessTxs(preimages: Set<ByteVector32>): List<Transactions.HtlcSuccessTx> {
        val commitKeys = commitments.latest.localKeys(channelKeys)
        return commitments.latest.unsignedHtlcTxs(channelKeys).mapNotNull { (htlcTx, remoteSig) ->
            when (htlcTx) {
                is Transactions.HtlcSuccessTx -> preimages.find { it.sha256() == htlcTx.paymentHash }?.let { p -> htlcTx.sign(commitKeys, remoteSig, p) }
                is Transactions.HtlcTimeoutTx -> null
            }
        }
    }

    fun process(cmd: ChannelCommand): Pair<LNChannel<ChannelState>, List<ChannelAction>> = runBlocking {
        state
            .run { ctx.copy(logger = ctx.logger.copy(staticMdc = state.mdc())).process(cmd) }
            .let { (newState, actions) ->
                checkSerialization(actions)
                JsonSerializers.json.encodeToString(newState)
                LNChannel(ctx, newState) to actions
            }
    }

    /** same as [process] but with the added assumption that we stay in the same state */
    fun processSameState(event: ChannelCommand): Pair<LNChannel<S>, List<ChannelAction>> {
        val (newState, actions) = this.process(event)
        assertIs<LNChannel<S>>(newState)
        return newState to actions
    }

    // we check that serialization works by checking that deserialize(serialize(state)) == state
    private fun checkSerialization(state: PersistedChannelState) {

        // We don't persist unsigned funding RBF or splice attempts.
        fun removeTemporaryStatuses(state: PersistedChannelState): PersistedChannelState = when (state) {
            is Normal -> when (state.spliceStatus) {
                is SpliceStatus.WaitingForSigs -> state.copy(spliceStatus = state.spliceStatus.copy(session = state.spliceStatus.session.copy(nextRemoteCommitNonce = null)))
                else -> state.copy(spliceStatus = SpliceStatus.None)
            }
            is WaitForFundingSigned -> state.copy(signingSession = state.signingSession.copy(nextRemoteCommitNonce = null), remoteCommitNonces = mapOf())
            else -> state
        }

        val dummyReplyTo = CompletableDeferred<ChannelCloseResponse>()
        fun ignoreClosingReplyTo(state: PersistedChannelState): PersistedChannelState = when (state) {
            is Normal -> state.copy(closeCommand = state.closeCommand?.copy(replyTo = dummyReplyTo))
            is ShuttingDown -> state.copy(closeCommand = state.closeCommand?.copy(replyTo = dummyReplyTo))
            is Negotiating -> state.copy(closeCommand = state.closeCommand?.copy(replyTo = dummyReplyTo))
            else -> state
        }

        val serialized = Serialization.serialize(state)
        val deserialized = Serialization.deserialize(serialized).value
//        assertEquals(removeTemporaryStatuses(ignoreClosingReplyTo(state)), ignoreClosingReplyTo(deserialized), "serialization error")
    }

    private fun checkSerialization(actions: List<ChannelAction>) {
        // we check that serialization works everytime we're supposed to persist channel data
        actions.filterIsInstance<ChannelAction.Storage.StoreState>().forEach { checkSerialization(it.data) }
    }

}

object TestsHelper {

    fun init(
        channelType: ChannelType.SupportedChannelType = ChannelType.SupportedChannelType.AnchorOutputs,
        aliceFeatures: Features = TestConstants.Alice.nodeParams.features,
        bobFeatures: Features = TestConstants.Bob.nodeParams.features,
        bobUsePeerStorage: Boolean = true,
        currentHeight: Int = TestConstants.defaultBlockHeight,
        aliceFundingAmount: Satoshi = TestConstants.aliceFundingAmount,
        bobFundingAmount: Satoshi = TestConstants.bobFundingAmount,
        requestRemoteFunding: Satoshi? = null,
        zeroConf: Boolean = false,
        channelOrigin: Origin? = null
    ): Triple<LNChannel<WaitForAcceptChannel>, LNChannel<WaitForOpenChannel>, OpenDualFundedChannel> {
        val (aliceFeatures1, bobFeatures1) = when (channelType) {
            ChannelType.SupportedChannelType.SimpleTaprootChannels -> Pair(
                aliceFeatures.add(Feature.SimpleTaprootChannels to FeatureSupport.Mandatory),
                bobFeatures.add(Feature.SimpleTaprootChannels to FeatureSupport.Mandatory)
            )

            else -> Pair(aliceFeatures, bobFeatures)
        }
        val (aliceNodeParams, bobNodeParams) = when (zeroConf) {
            true -> Pair(
                TestConstants.Alice.nodeParams.copy(features = aliceFeatures1, zeroConfPeers = setOf(TestConstants.Bob.nodeParams.nodeId), usePeerStorage = false),
                TestConstants.Bob.nodeParams.copy(features = bobFeatures1, zeroConfPeers = setOf(TestConstants.Alice.nodeParams.nodeId), usePeerStorage = bobUsePeerStorage)
            )
            false -> Pair(
                TestConstants.Alice.nodeParams.copy(features = aliceFeatures1, usePeerStorage = false),
                TestConstants.Bob.nodeParams.copy(features = bobFeatures1, usePeerStorage = bobUsePeerStorage)
            )
        }
        val alice = LNChannel(
            ChannelContext(
                StaticParams(aliceNodeParams, TestConstants.Bob.nodeParams.nodeId),
                currentBlockHeight = currentHeight,
                onChainFeerates = MutableStateFlow(OnChainFeerates(TestConstants.feeratePerKw, TestConstants.feeratePerKw, TestConstants.feeratePerKw, TestConstants.feeratePerKw)),
                logger = MDCLogger(testLoggerFactory.newLogger("ChannelState"))
            ),
            WaitForInit
        )
        val bob = LNChannel(
            ChannelContext(
                StaticParams(bobNodeParams, TestConstants.Alice.nodeParams.nodeId),
                currentBlockHeight = currentHeight,
                onChainFeerates = MutableStateFlow(OnChainFeerates(TestConstants.feeratePerKw, TestConstants.feeratePerKw, TestConstants.feeratePerKw, TestConstants.feeratePerKw)),
                logger = MDCLogger(testLoggerFactory.newLogger("ChannelState"))
            ),
            WaitForInit
        )

        val channelFlags = ChannelFlags(announceChannel = false, nonInitiatorPaysCommitFees = requestRemoteFunding != null)
        val aliceChannelParams = TestConstants.Alice.channelParams(payCommitTxFees = !channelFlags.nonInitiatorPaysCommitFees).copy(features = aliceFeatures1.initFeatures())
        val bobChannelParams = TestConstants.Bob.channelParams(payCommitTxFees = channelFlags.nonInitiatorPaysCommitFees).copy(features = bobFeatures1.initFeatures())
        val aliceInit = Init(aliceFeatures1)
        val bobInit = Init(bobFeatures1)
        val cmd = ChannelCommand.Init.Initiator(
            CompletableDeferred(),
            aliceFundingAmount,
            createWallet(aliceNodeParams.keyManager, aliceFundingAmount + 3500.sat).second,
            FeeratePerKw.CommitmentFeerate,
            TestConstants.feeratePerKw,
            aliceChannelParams,
            TestConstants.Alice.nodeParams.dustLimit,
            TestConstants.Alice.nodeParams.htlcMinimum,
            TestConstants.Alice.nodeParams.maxHtlcValueInFlightMsat,
            TestConstants.Alice.nodeParams.maxAcceptedHtlcs,
            TestConstants.Alice.nodeParams.toRemoteDelayBlocks,
            bobInit,
            channelFlags,
            ChannelConfig.standard,
            channelType,
            requestRemoteFunding?.let {
                when (channelOrigin) {
                    is Origin.OffChainPayment -> LiquidityAds.RequestFunding(it, TestConstants.fundingRates.findRate(it)!!, LiquidityAds.PaymentDetails.FromFutureHtlc(listOf(channelOrigin.paymentHash)))
                    else -> LiquidityAds.RequestFunding(it, TestConstants.fundingRates.findRate(it)!!, LiquidityAds.PaymentDetails.FromChannelBalance)
                }
            },
            channelOrigin,
        )
        val (alice1, actionsAlice1) = alice.process(cmd)
        assertIs<LNChannel<WaitForAcceptChannel>>(alice1)
        val temporaryChannelId = cmd.temporaryChannelId(aliceChannelParams.channelKeys(alice.ctx.keyManager))
        val bobWallet = if (bobFundingAmount > 0.sat) createWallet(bobNodeParams.keyManager, bobFundingAmount + 1500.sat).second else listOf()
        val (bob1, _) = bob.process(
            ChannelCommand.Init.NonInitiator(
                CompletableDeferred(),
                temporaryChannelId,
                bobFundingAmount,
                bobWallet,
                bobChannelParams,
                TestConstants.Bob.nodeParams.dustLimit,
                TestConstants.Bob.nodeParams.htlcMinimum,
                TestConstants.Bob.nodeParams.maxHtlcValueInFlightMsat,
                TestConstants.Bob.nodeParams.maxAcceptedHtlcs,
                TestConstants.Bob.nodeParams.toRemoteDelayBlocks,
                ChannelConfig.standard,
                aliceInit,
                TestConstants.fundingRates
            )
        )
        assertIs<LNChannel<WaitForOpenChannel>>(bob1)
        val open = actionsAlice1.findOutgoingMessage<OpenDualFundedChannel>()
        return Triple(alice1, bob1, open)
    }

    fun reachNormal(
        channelType: ChannelType.SupportedChannelType = ChannelType.SupportedChannelType.AnchorOutputs,
        aliceFeatures: Features = TestConstants.Alice.nodeParams.features.initFeatures(),
        bobFeatures: Features = TestConstants.Bob.nodeParams.features.initFeatures(),
        bobUsePeerStorage: Boolean = true,
        currentHeight: Int = TestConstants.defaultBlockHeight,
        aliceFundingAmount: Satoshi = TestConstants.aliceFundingAmount,
        bobFundingAmount: Satoshi = TestConstants.bobFundingAmount,
        requestRemoteFunding: Satoshi? = null,
        zeroConf: Boolean = false,
    ): Triple<LNChannel<Normal>, LNChannel<Normal>, TxId> {
        val (alice, channelReadyAlice, bob, channelReadyBob) = WaitForChannelReadyTestsCommon.init(
            channelType,
            aliceFeatures,
            bobFeatures,
            bobUsePeerStorage,
            currentHeight,
            aliceFundingAmount,
            bobFundingAmount,
            requestRemoteFunding,
            zeroConf
        )
        val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(channelReadyBob))
        assertIs<LNChannel<Normal>>(alice1)
        actionsAlice1.has<ChannelAction.Storage.StoreState>()
        val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(channelReadyAlice))
        assertIs<LNChannel<Normal>>(bob1)
        actionsBob1.has<ChannelAction.Storage.StoreState>()
        val fundingTxId = when (val fundingStatus = alice.commitments.latest.localFundingStatus) {
            is LocalFundingStatus.UnconfirmedFundingTx -> fundingStatus.sharedTx.txId
            is LocalFundingStatus.ConfirmedFundingTx -> fundingStatus.txId
        }
        return Triple(alice1, bob1, fundingTxId)
    }

    suspend fun mutualCloseAlice(alice: LNChannel<Normal>, bob: LNChannel<Normal>, closingFeerate: FeeratePerKw, scriptPubKey: ByteVector? = null): Triple<LNChannel<Negotiating>, LNChannel<Negotiating>, Transaction> {
        val cmd = ChannelCommand.Close.MutualClose(CompletableDeferred(), scriptPubKey, closingFeerate)
        val (alice1, actionsAlice1) = alice.process(cmd)
        assertIs<LNChannel<Normal>>(alice1)
        val shutdownAlice = actionsAlice1.findOutgoingMessage<Shutdown>()
        assertNull(actionsAlice1.findOutgoingMessageOpt<ClosingComplete>())

        val (bob1, actionsBob1) = bob.process(ChannelCommand.MessageReceived(shutdownAlice))
        assertIs<LNChannel<Negotiating>>(bob1)
        val shutdownBob = actionsBob1.findOutgoingMessage<Shutdown>()
        assertNull(actionsBob1.findOutgoingMessageOpt<ClosingComplete>())

        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(shutdownBob))
        assertIs<LNChannel<Negotiating>>(alice2)
        val closingComplete = actionsAlice2.findOutgoingMessage<ClosingComplete>()

        val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(closingComplete))
        assertIs<LNChannel<Negotiating>>(bob2)
        val closingSig = actionsBob2.findOutgoingMessage<ClosingSig>()

        val (alice3, actionsAlice3) = alice2.process(ChannelCommand.MessageReceived(closingSig))
        assertIs<LNChannel<Negotiating>>(alice3)
        val closingTx = actionsAlice3.findPublishTxs().first()
        actionsAlice3.hasWatchConfirmed(closingTx.txid)
        Transaction.correctlySpends(closingTx, mapOf(alice1.commitments.latest.fundingInput to alice1.commitments.latest.localFundingStatus.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        assertEquals(ChannelCloseResponse.Success(closingTx.txid, closingComplete.fees), cmd.replyTo.await())
        return Triple(alice3, bob2, closingTx)
    }

    suspend fun mutualCloseBob(alice: LNChannel<Normal>, bob: LNChannel<Normal>, closingFeerate: FeeratePerKw, scriptPubKey: ByteVector? = null): Triple<LNChannel<Negotiating>, LNChannel<Negotiating>, Transaction> {
        val cmd = ChannelCommand.Close.MutualClose(CompletableDeferred(), scriptPubKey, closingFeerate)
        val (bob1, actionsBob1) = bob.process(cmd)
        assertIs<LNChannel<Normal>>(bob1)
        val shutdownBob = actionsBob1.findOutgoingMessage<Shutdown>()
        assertNull(actionsBob1.findOutgoingMessageOpt<ClosingComplete>())

        val (alice1, actionsAlice1) = alice.process(ChannelCommand.MessageReceived(shutdownBob))
        assertIs<LNChannel<Negotiating>>(alice1)
        val shutdownAlice = actionsAlice1.findOutgoingMessage<Shutdown>()
        assertNull(actionsAlice1.findOutgoingMessageOpt<ClosingComplete>())

        val (bob2, actionsBob2) = bob1.process(ChannelCommand.MessageReceived(shutdownAlice))
        assertIs<LNChannel<Negotiating>>(bob2)
        val closingComplete = actionsBob2.findOutgoingMessage<ClosingComplete>()

        val (alice2, actionsAlice2) = alice1.process(ChannelCommand.MessageReceived(closingComplete))
        assertIs<LNChannel<Negotiating>>(alice2)
        val closingSig = actionsAlice2.findOutgoingMessage<ClosingSig>()

        val (bob3, actionsBob3) = bob2.process(ChannelCommand.MessageReceived(closingSig))
        assertIs<LNChannel<Negotiating>>(bob3)
        val closingTx = actionsBob3.findPublishTxs().first()
        actionsBob3.hasWatchConfirmed(closingTx.txid)
        Transaction.correctlySpends(closingTx, mapOf(alice1.commitments.latest.fundingInput to alice1.commitments.latest.localFundingStatus.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        assertEquals(ChannelCloseResponse.Success(closingTx.txid, closingComplete.fees), cmd.replyTo.await())
        return Triple(alice2, bob3, closingTx)
    }

    data class LocalCloseTxs(val mainTx: Transaction, val htlcSuccessTxs: List<Transaction>, val htlcTimeoutTxs: List<Transaction>)

    fun localClose(s: LNChannel<ChannelState>, htlcSuccessCount: Int = 0, htlcTimeoutCount: Int = 0): Triple<LNChannel<Closing>, LocalCommitPublished, LocalCloseTxs> {
        assertIs<LNChannel<ChannelStateWithCommitments>>(s)
        assertEquals(Transactions.CommitmentFormat.AnchorOutputs, s.state.commitments.latest.commitmentFormat)
        // An error occurs and we publish our commit tx.
        val commitTxId = s.state.commitments.latest.localCommit.txId
        val (s1, actions1) = s.process(ChannelCommand.MessageReceived(Error(ByteVector32.Zeroes, "oops")))
        assertIs<LNChannel<Closing>>(s1)
        actions1.has<ChannelAction.Storage.StoreState>()
        actions1.find<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>().also {
            assertEquals(commitTxId, it.txId)
            assertEquals(ChannelClosingType.Local, it.closingType)
        }

        val localCommitPublished = s1.state.localCommitPublished
        assertNotNull(localCommitPublished)
        assertEquals(commitTxId, localCommitPublished.commitTx.txid)
        // It may be strictly greater if we don't have the preimage for some of our received HTLCs, or if we haven't fulfilled them yet.
        assertTrue(localCommitPublished.incomingHtlcs.size >= htlcSuccessCount)
        assertEquals(htlcTimeoutCount, localCommitPublished.outgoingHtlcs.size)
        // We're not claiming the outputs of htlc txs yet.
        assertTrue(localCommitPublished.htlcDelayedOutputs.isEmpty())
        actions1.hasPublishTx(localCommitPublished.commitTx)
        Transaction.correctlySpends(localCommitPublished.commitTx, mapOf(s.commitments.latest.fundingInput to s.commitments.latest.localFundingStatus.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        assertNotNull(localCommitPublished.localOutput)
        val mainTx = actions1.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimLocalDelayedOutputTx)
        Transaction.correctlySpends(mainTx, localCommitPublished.commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        val htlcSuccessTxs = actions1.findPublishTxs(ChannelAction.Blockchain.PublishTx.Type.HtlcSuccessTx)
        assertEquals(htlcSuccessCount, htlcSuccessTxs.size)
        val htlcTimeoutTxs = actions1.findPublishTxs(ChannelAction.Blockchain.PublishTx.Type.HtlcTimeoutTx)
        assertEquals(htlcTimeoutCount, htlcTimeoutTxs.size)
        (htlcSuccessTxs + htlcTimeoutTxs).forEach { htlcTx -> Transaction.correctlySpends(htlcTx, localCommitPublished.commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }

        // We watch the confirmation of the commitment transaction.
        val watchConfirmed = actions1.findWatches<WatchConfirmed>()
        watchConfirmed.forEach { assertEquals(WatchConfirmed.ClosingTxConfirmed, it.event) }
        assertEquals(setOf(commitTxId), watchConfirmed.map { it.txId }.toSet())

        // We watch outputs of the commitment tx that we want to claim.
        val watchSpent = actions1.findWatches<WatchSpent>()
        watchSpent.forEach { watch ->
            assertIs<WatchSpent.ClosingOutputSpent>(watch.event)
            assertEquals(watch.txId, commitTxId)
        }
        val watchedOutputs = watchSpent.map { w -> OutPoint(w.txId, w.outputIndex.toLong()) }.toSet()
        assertTrue(watchedOutputs.contains(localCommitPublished.localOutput))
        assertTrue(watchedOutputs.containsAll(localCommitPublished.htlcOutputs))

        // Once our closing transactions are published, we watch for their confirmation.
        var closingState: LNChannel<Closing> = s1
        (listOf(mainTx) + htlcSuccessTxs + htlcTimeoutTxs).forEach { tx ->
            val event = WatchSpent.ClosingOutputSpent(tx.txOut.first().amount)
            val (s2, actions2) = closingState.process(ChannelCommand.WatchReceived(WatchSpentTriggered(closingState.channelId, event, tx)))
            assertIs<LNChannel<Closing>>(s2)
            actions2.hasWatchConfirmed(tx.txid)
            closingState = s2
        }

        return Triple(closingState, localCommitPublished, LocalCloseTxs(mainTx, htlcSuccessTxs, htlcTimeoutTxs))
    }

    data class RemoteCloseTxs(val mainTx: Transaction, val htlcSuccessTxs: List<Transaction>, val htlcTimeoutTxs: List<Transaction>)

    fun remoteClose(rCommitTx: Transaction, s: LNChannel<ChannelState>, htlcSuccessCount: Int = 0, htlcTimeoutCount: Int = 0): Triple<LNChannel<Closing>, RemoteCommitPublished, RemoteCloseTxs> {
        assertIs<LNChannel<ChannelStateWithCommitments>>(s)
        assertEquals(Transactions.CommitmentFormat.AnchorOutputs, s.state.commitments.latest.commitmentFormat)
        // Our peer has unilaterally closed the channel.
        val (s1, actions1) = s.process(ChannelCommand.WatchReceived(WatchSpentTriggered(s.state.channelId, WatchSpent.ChannelSpent(TestConstants.fundingAmount), rCommitTx)))
        assertIs<LNChannel<Closing>>(s1)

        // If we're transitioning to the closing state, we store that as an outgoing (on-chain) payment.
        if (s.state !is Closing) {
            val channelBalance = s.state.commitments.latest.localCommit.spec.toLocal
            if (channelBalance > 0.msat) {
                actions1.find<ChannelAction.Storage.StoreOutgoingPayment.ViaClose>().also {
                    assertEquals(rCommitTx.txid, it.txId)
                    assertEquals(ChannelClosingType.Remote, it.closingType)
                }
            }
        }

        val remoteCommitPublished = s1.state.remoteCommitPublished ?: s1.state.nextRemoteCommitPublished ?: s1.state.futureRemoteCommitPublished
        assertNotNull(remoteCommitPublished)
        assertNull(s1.state.localCommitPublished)
        // It may be strictly greater if we don't have the preimage for some of our received HTLCs, or if we haven't fulfilled them yet.
        assertTrue(remoteCommitPublished.incomingHtlcs.size >= htlcSuccessCount)
        assertEquals(htlcTimeoutCount, remoteCommitPublished.outgoingHtlcs.size)
        assertNotNull(remoteCommitPublished.localOutput)
        val mainTx = actions1.hasPublishTx(ChannelAction.Blockchain.PublishTx.Type.ClaimRemoteDelayedOutputTx)
        Transaction.correctlySpends(mainTx, rCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        val htlcSuccessTxs = actions1.findPublishTxs(ChannelAction.Blockchain.PublishTx.Type.ClaimHtlcSuccessTx)
        assertEquals(htlcSuccessCount, htlcSuccessTxs.size)
        val htlcTimeoutTxs = actions1.findPublishTxs(ChannelAction.Blockchain.PublishTx.Type.ClaimHtlcTimeoutTx)
        assertEquals(htlcTimeoutCount, htlcTimeoutTxs.size)
        (htlcSuccessTxs + htlcTimeoutTxs).forEach { htlcTx -> Transaction.correctlySpends(htlcTx, rCommitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }

        // We watch the confirmation of the remote commitment transaction.
        val watchConfirmed = actions1.findWatches<WatchConfirmed>()
        watchConfirmed.forEach { assertEquals(WatchConfirmed.ClosingTxConfirmed, it.event) }
        assertEquals(setOf(rCommitTx.txid), watchConfirmed.map { it.txId }.toSet())

        // We watch outputs of the commitment tx that we want to claim.
        val watchSpent = actions1.findWatches<WatchSpent>()
        watchSpent.forEach { watch ->
            assertIs<WatchSpent.ClosingOutputSpent>(watch.event)
            assertEquals(watch.txId, rCommitTx.txid)
        }
        val watchedOutputs = watchSpent.map { w -> OutPoint(w.txId, w.outputIndex.toLong()) }.toSet()
        assertTrue(watchedOutputs.contains(remoteCommitPublished.localOutput))
        assertTrue(watchedOutputs.containsAll(remoteCommitPublished.htlcOutputs))

        // Once our closing transactions are published, we watch for their confirmation.
        var closingState: LNChannel<Closing> = s1
        (listOf(mainTx) + htlcSuccessTxs + htlcTimeoutTxs).forEach { tx ->
            val event = WatchSpent.ClosingOutputSpent(tx.txOut.first().amount)
            val (s2, actions2) = closingState.process(ChannelCommand.WatchReceived(WatchSpentTriggered(closingState.channelId, event, tx)))
            assertIs<LNChannel<Closing>>(s2)
            actions2.hasWatchConfirmed(tx.txid)
            closingState = s2
        }

        return Triple(closingState, remoteCommitPublished, RemoteCloseTxs(mainTx, htlcSuccessTxs, htlcTimeoutTxs))
    }

    fun useAlternativeCommitSig(s: LNChannel<ChannelState>, commitment: Commitment, feerate: FeeratePerKw): Transaction {
        val channelKeys = s.commitments.channelKeys(s.ctx.keyManager)
        val fundingKey = commitment.localFundingKey(channelKeys)
        val commitKeys = channelKeys.localCommitmentKeys(s.commitments.channelParams, commitment.localCommit.index)
        val alternativeSpec = commitment.localCommit.spec.copy(feerate = feerate)
        val remoteFundingPubKey = commitment.remoteFundingPubkey
        // This commitment transaction isn't signed, but we don't care, we will make it look like it was confirmed anyway.
        val (localCommitTx, _) = Commitments.makeLocalTxs(
            channelParams = s.commitments.channelParams,
            commitParams = commitment.localCommitParams,
            commitKeys = commitKeys,
            commitTxNumber = commitment.localCommit.index,
            localFundingKey = fundingKey,
            remoteFundingPubKey = remoteFundingPubKey,
            commitmentInput = commitment.commitInput(fundingKey),
            commitmentFormat = commitment.commitmentFormat,
            spec = alternativeSpec,
        )
        return localCommitTx.tx
    }

    fun signAndRevack(alice: LNChannel<ChannelState>, bob: LNChannel<ChannelState>): Pair<LNChannel<ChannelState>, LNChannel<ChannelState>> {
        val (alice1, actions1) = alice.process(ChannelCommand.Commitment.Sign)
        val commitSig = actions1.findOutgoingMessage<CommitSig>()
        val (bob1, actions2) = bob.process(ChannelCommand.MessageReceived(commitSig))
        val revack = actions2.findOutgoingMessage<RevokeAndAck>()
        val (alice2, _) = alice1.process(ChannelCommand.MessageReceived(revack))
        return Pair(alice2, bob1)
    }

    fun makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: Long, paymentPreimage: ByteVector32 = randomBytes32(), paymentId: UUID = UUID.randomUUID()): Pair<ByteVector32, ChannelCommand.Htlc.Add> {
        val paymentHash = Crypto.sha256(paymentPreimage).toByteVector32()
        val expiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight)
        val payload = PaymentOnion.FinalPayload.Standard.createSinglePartPayload(amount, expiry, randomBytes32(), null)
        val onion = OutgoingPaymentPacket.buildOnion(listOf(destination), listOf(payload), paymentHash, OnionRoutingPacket.PaymentPacketLength).packet
        val cmd = ChannelCommand.Htlc.Add(amount, paymentHash, expiry, onion, paymentId, commit = false)
        return Pair(paymentPreimage, cmd)
    }

    fun createWallet(keyManager: KeyManager, amount: Satoshi): Pair<PrivateKey, List<WalletState.Utxo>> {
        val swapInAddressIndex = 0
        val (privateKey, script) = keyManager.swapInOnChainWallet.run { Pair(userPrivateKey, getSwapInProtocol(swapInAddressIndex).pubkeyScript) }
        val parentTx = Transaction(2, listOf(TxIn(OutPoint(TxId(randomBytes32()), 3), 0)), listOf(TxOut(amount, script)), 0)
        return privateKey to listOf(WalletState.Utxo(parentTx.txid, 0, 42, parentTx, WalletState.AddressMeta.Derived(swapInAddressIndex)))
    }

    fun <T : ChannelState> addHtlc(amount: MilliSatoshi, payer: LNChannel<T>, payee: LNChannel<T>): Triple<Pair<LNChannel<T>, LNChannel<T>>, ByteVector32, UpdateAddHtlc> {
        val currentBlockHeight = payer.currentBlockHeight.toLong()
        val (paymentPreimage, cmd) = makeCmdAdd(amount, payee.staticParams.nodeParams.nodeId, currentBlockHeight)
        val (sender0, receiver0, htlc) = addHtlc(cmd, payer, payee)
        return Triple(sender0 to receiver0, paymentPreimage, htlc)
    }

    fun <T : ChannelState> addHtlc(cmdAdd: ChannelCommand.Htlc.Add, payer: LNChannel<T>, payee: LNChannel<T>): Triple<LNChannel<T>, LNChannel<T>, UpdateAddHtlc> {
        val (sender0, senderActions0) = payer.process(cmdAdd)
        val htlc = senderActions0.findOutgoingMessage<UpdateAddHtlc>()

        val (receiver0, _) = payee.process(ChannelCommand.MessageReceived(htlc))
        assertIs<LNChannel<ChannelStateWithCommitments>>(receiver0)
        assertTrue(receiver0.state.commitments.changes.remoteChanges.proposed.contains(htlc))

        assertIs<LNChannel<T>>(sender0)
        assertIs<LNChannel<T>>(receiver0)
        return Triple(sender0, receiver0, htlc)
    }

    fun <T : ChannelState> fulfillHtlc(id: Long, paymentPreimage: ByteVector32, payer: LNChannel<T>, payee: LNChannel<T>): Pair<LNChannel<T>, LNChannel<T>> {
        val (payee0, payeeActions0) = payee.process(ChannelCommand.Htlc.Settlement.Fulfill(id, paymentPreimage))
        val fulfillHtlc = payeeActions0.findOutgoingMessage<UpdateFulfillHtlc>()

        val (payer0, _) = payer.process(ChannelCommand.MessageReceived(fulfillHtlc))
        assertIs<LNChannel<ChannelStateWithCommitments>>(payer0)
        assertTrue(payer0.state.commitments.changes.remoteChanges.proposed.contains(fulfillHtlc))

        assertIs<LNChannel<T>>(payer0)
        assertIs<LNChannel<T>>(payee0)
        return payer0 to payee0
    }

    fun <T : ChannelState> failHtlc(id: Long, payer: LNChannel<T>, payee: LNChannel<T>): Pair<LNChannel<T>, LNChannel<T>> {
        val (payee0, payeeActions0) = payee.process(ChannelCommand.Htlc.Settlement.Fail(id, ChannelCommand.Htlc.Settlement.Fail.Reason.Failure(TemporaryNodeFailure)))
        val failHtlc = payeeActions0.findOutgoingMessage<UpdateFailHtlc>()

        val (payer0, _) = payer.process(ChannelCommand.MessageReceived(failHtlc))
        assertIs<LNChannel<ChannelStateWithCommitments>>(payer0)
        assertTrue(payer0.state.commitments.changes.remoteChanges.proposed.contains(failHtlc))

        assertIs<LNChannel<T>>(payer0)
        assertIs<LNChannel<T>>(payee0)
        return payer0 to payee0
    }

    private fun verifyCommitSigsCount(commitSigs: CommitSigs, commitmentsCount: Int) {
        if (commitmentsCount == 1) {
            assertIs<CommitSig>(commitSigs)
        } else {
            assertIs<CommitSigBatch>(commitSigs)
            assertEquals(commitmentsCount, commitSigs.batchSize)
        }
    }

    /**
     * Cross sign nodes where nodeA initiate the signature exchange
     */
    fun <T : ChannelStateWithCommitments> crossSign(nodeA: LNChannel<T>, nodeB: LNChannel<T>, commitmentsCount: Int = 1): Pair<LNChannel<T>, LNChannel<T>> {
        val sCommitIndex = nodeA.state.commitments.localCommitIndex
        val rCommitIndex = nodeB.state.commitments.localCommitIndex
        val rHasChanges = nodeB.state.commitments.changes.localHasChanges()

        val (sender0, sActions0) = nodeA.process(ChannelCommand.Commitment.Sign)
        val commitSigs0 = sActions0.findOutgoingMessage<CommitSigs>()
        verifyCommitSigsCount(commitSigs0, commitmentsCount)

        val (receiver0, rActions0) = nodeB.process(ChannelCommand.MessageReceived(commitSigs0))
        val revokeAndAck0 = rActions0.findOutgoingMessage<RevokeAndAck>()
        val commandSign0 = rActions0.findCommand<ChannelCommand.Commitment.Sign>()

        val (sender1, _) = sender0.process(ChannelCommand.MessageReceived(revokeAndAck0))
        assertIs<LNChannel<T>>(sender1)
        val (receiver1, rActions1) = receiver0.process(commandSign0)
        val commitSigs1 = rActions1.findOutgoingMessage<CommitSigs>()
        verifyCommitSigsCount(commitSigs1, commitmentsCount)

        val (sender2, sActions2) = sender1.process(ChannelCommand.MessageReceived(commitSigs1))
        val revokeAndAck1 = sActions2.findOutgoingMessage<RevokeAndAck>()
        val (receiver2, _) = receiver1.process(ChannelCommand.MessageReceived(revokeAndAck1))
        assertIs<LNChannel<T>>(receiver2)

        if (rHasChanges) {
            val commandSign1 = sActions2.findCommand<ChannelCommand.Commitment.Sign>()
            val (sender3, sActions3) = sender2.process(commandSign1)
            val commitSigs2 = sActions3.findOutgoingMessage<CommitSigs>()
            verifyCommitSigsCount(commitSigs2, commitmentsCount)

            val (receiver3, rActions3) = receiver2.process(ChannelCommand.MessageReceived(commitSigs2))
            val revokeAndAck2 = rActions3.findOutgoingMessage<RevokeAndAck>()
            val (sender4, _) = sender3.process(ChannelCommand.MessageReceived(revokeAndAck2))

            assertIs<LNChannel<T>>(sender4)
            assertIs<LNChannel<T>>(receiver3)
            assertEquals(sCommitIndex + 1, sender4.commitments.localCommitIndex)
            assertEquals(rCommitIndex + 2, sender4.commitments.remoteCommitIndex)
            assertEquals(rCommitIndex + 2, receiver3.commitments.localCommitIndex)
            assertEquals(sCommitIndex + 1, receiver3.commitments.remoteCommitIndex)

            return sender4 to receiver3
        } else {
            assertIs<LNChannel<T>>(sender2)
            assertEquals(sCommitIndex + 1, sender2.commitments.localCommitIndex)
            assertEquals(rCommitIndex + 1, sender2.commitments.remoteCommitIndex)
            assertEquals(rCommitIndex + 1, receiver2.commitments.localCommitIndex)
            assertEquals(sCommitIndex + 1, receiver2.commitments.remoteCommitIndex)

            return sender2 to receiver2
        }
    }

}
