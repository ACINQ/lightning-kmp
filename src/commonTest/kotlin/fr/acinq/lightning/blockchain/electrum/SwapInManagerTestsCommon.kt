package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEPTHOK
import fr.acinq.lightning.blockchain.WatchEventConfirmed
import fr.acinq.lightning.channel.ChannelCommand
import fr.acinq.lightning.channel.LNChannel
import fr.acinq.lightning.channel.LocalFundingStatus
import fr.acinq.lightning.channel.TestsHelper
import fr.acinq.lightning.channel.states.Normal
import fr.acinq.lightning.channel.states.SpliceTestsCommon
import fr.acinq.lightning.channel.states.WaitForFundingSignedTestsCommon
import fr.acinq.lightning.logging.*
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.wire.SpliceLocked
import kotlin.test.*

class SwapInManagerTestsCommon : LightningTestSuite() {

    private val logger = MDCLogger(loggerFactory.newLogger(this::class))

    private val dummyPubkey = PublicKey.fromHex("02eae982c8563a1c256ee9b4655af7d4c0dc545d1e5c350a68c5f8902cd4cf3021")
    private val dummyScript = Script.pay2wpkh(dummyPubkey)
    private val dummyAddress = Bitcoin.computeP2WpkhAddress(dummyPubkey, Bitcoin.Chain.Regtest.chainHash)

    @Test
    fun `swap funds`() {
        val mgr = SwapInManager(listOf(), logger)
        val wallet = run {
            val parentTxs = listOf(
                Transaction(2, listOf(TxIn(OutPoint(TxId(randomBytes32()), 2), 0)), listOf(TxOut(50_000.sat, dummyScript), TxOut(75_000.sat, dummyScript)), 0),
                Transaction(2, listOf(TxIn(OutPoint(TxId(randomBytes32()), 0), 0)), listOf(TxOut(25_000.sat, dummyScript)), 0)
            )
            val utxos = listOf(
                WalletState.Utxo(parentTxs[0].txid, 0, 100, parentTxs[0], WalletState.Companion.AddressMeta.Single), // deeply confirmed
                WalletState.Utxo(parentTxs[0].txid, 1, 100, parentTxs[0], WalletState.Companion.AddressMeta.Single), // deeply confirmed
                WalletState.Utxo(parentTxs[1].txid, 0, 149, parentTxs[1], WalletState.Companion.AddressMeta.Single), // recently confirmed
            )
            val addressState = WalletState.Companion.AddressState(WalletState.Companion.AddressMeta.Single, alreadyUsed = true, utxos)
            WalletState(mapOf(dummyAddress to addressState))
        }
        val cmd = SwapInCommand.TrySwapIn(currentBlockHeight = 150, wallet = wallet, swapInParams = SwapInParams(minConfirmations = 3, maxConfirmations = 720, refundDelay = 900), trustedTxs = emptySet())
        mgr.process(cmd).also { result ->
            assertNotNull(result)
            assertEquals(result.walletInputs.map { it.amount }.toSet(), setOf(50_000.sat, 75_000.sat))
        }
    }

    @Test
    fun `swap funds -- not deeply confirmed`() {
        val mgr = SwapInManager(listOf(), logger)
        val wallet = run {
            val parentTxs = listOf(
                Transaction(2, listOf(TxIn(OutPoint(TxId(randomBytes32()), 2), 0)), listOf(TxOut(50_000.sat, dummyScript)), 0),
                Transaction(2, listOf(TxIn(OutPoint(TxId(randomBytes32()), 0), 0)), listOf(TxOut(25_000.sat, dummyScript)), 0)
            )
            val utxos = listOf(
                WalletState.Utxo(parentTxs[0].txid, 0, 100, parentTxs[0], WalletState.Companion.AddressMeta.Single), // recently confirmed
                WalletState.Utxo(parentTxs[1].txid, 0, 0, parentTxs[1], WalletState.Companion.AddressMeta.Single), // unconfirmed
            )
            val addressState = WalletState.Companion.AddressState(WalletState.Companion.AddressMeta.Single, alreadyUsed = true, utxos)
            WalletState(mapOf(dummyAddress to addressState))
        }
        val cmd = SwapInCommand.TrySwapIn(currentBlockHeight = 101, wallet = wallet, swapInParams = SwapInParams(minConfirmations = 3, maxConfirmations = 720, refundDelay = 900), trustedTxs = emptySet())
        mgr.process(cmd).also { assertNull(it) }
    }

    @Test
    fun `swap funds -- max confirmations exceeded`() {
        val mgr = SwapInManager(listOf(), logger)
        val wallet = run {
            val parentTxs = listOf(
                Transaction(2, listOf(TxIn(OutPoint(TxId(randomBytes32()), 2), 0)), listOf(TxOut(50_000.sat, dummyScript)), 0),
                Transaction(2, listOf(TxIn(OutPoint(TxId(randomBytes32()), 0), 0)), listOf(TxOut(25_000.sat, dummyScript)), 0)
            )
            val utxos = listOf(
                WalletState.Utxo(parentTxs[0].txid, 0, 100, parentTxs[0], WalletState.Companion.AddressMeta.Single), // exceeds refund delay
                WalletState.Utxo(parentTxs[1].txid, 0, 120, parentTxs[1], WalletState.Companion.AddressMeta.Single), // exceeds max confirmation before refund
            )
            val addressState = WalletState.Companion.AddressState(WalletState.Companion.AddressMeta.Single, alreadyUsed = true, utxos)
            WalletState(mapOf(dummyAddress to addressState))
        }
        val cmd = SwapInCommand.TrySwapIn(currentBlockHeight = 130, wallet = wallet, swapInParams = SwapInParams(minConfirmations = 3, maxConfirmations = 10, refundDelay = 15), trustedTxs = emptySet())
        mgr.process(cmd).also { assertNull(it) }
    }

    @Test
    fun `swap funds -- allow unconfirmed in migration`() {
        val mgr = SwapInManager(listOf(), logger)
        val parentTxs = listOf(
            Transaction(2, listOf(TxIn(OutPoint(TxId(randomBytes32()), 1), 0)), listOf(TxOut(75_000.sat, dummyScript)), 0),
            Transaction(2, listOf(TxIn(OutPoint(TxId(randomBytes32()), 2), 0)), listOf(TxOut(50_000.sat, dummyScript)), 0),
            Transaction(2, listOf(TxIn(OutPoint(TxId(randomBytes32()), 0), 0)), listOf(TxOut(25_000.sat, dummyScript)), 0)
        )
        val wallet = run {
            val utxos = listOf(
                WalletState.Utxo(parentTxs[0].txid, 0, 100, parentTxs[0], WalletState.Companion.AddressMeta.Single), // deeply confirmed
                WalletState.Utxo(parentTxs[1].txid, 0, 150, parentTxs[1], WalletState.Companion.AddressMeta.Single), // recently confirmed
                WalletState.Utxo(parentTxs[2].txid, 0, 0, parentTxs[2], WalletState.Companion.AddressMeta.Single), // unconfirmed
            )
            val addressState = WalletState.Companion.AddressState(WalletState.Companion.AddressMeta.Single, alreadyUsed = true, utxos)
            WalletState(mapOf(dummyAddress to addressState))
        }
        val cmd = SwapInCommand.TrySwapIn(currentBlockHeight = 150, wallet = wallet, swapInParams = SwapInParams(minConfirmations = 5, maxConfirmations = 720, refundDelay = 900), trustedTxs = parentTxs.map { it.txid }.toSet())
        mgr.process(cmd).also { result ->
            assertNotNull(result)
            assertEquals(result.walletInputs.map { it.amount }.toSet(), setOf(25_000.sat, 50_000.sat, 75_000.sat))
        }
    }

    @Test
    fun `swap funds -- previously used inputs`() {
        val mgr = SwapInManager(listOf(), logger)
        val wallet = run {
            val parentTx = Transaction(2, listOf(TxIn(OutPoint(TxId(randomBytes32()), 1), 0)), listOf(TxOut(75_000.sat, dummyScript)), 0)
            val utxos = listOf(WalletState.Utxo(parentTx.txid, 0, 100, parentTx, WalletState.Companion.AddressMeta.Single))
            val addressState = WalletState.Companion.AddressState(WalletState.Companion.AddressMeta.Single, alreadyUsed = true, utxos)
            WalletState(mapOf(dummyAddress to addressState))
        }
        val cmd = SwapInCommand.TrySwapIn(currentBlockHeight = 150, wallet = wallet, swapInParams = SwapInParams(minConfirmations = 5, maxConfirmations = 720, refundDelay = 900), trustedTxs = emptySet())
        mgr.process(cmd).also { assertNotNull(it) }

        // We cannot reuse the same inputs.
        mgr.process(cmd.copy(currentBlockHeight = 151)).also { assertNull(it) }

        // The channel funding attempts fails: we can now reuse those inputs.
        mgr.process(SwapInCommand.UnlockWalletInputs(wallet.utxos.map { it.outPoint }.toSet()))
        mgr.process(cmd.copy(currentBlockHeight = 152)).also { result ->
            assertNotNull(result)
            assertEquals(result.walletInputs.map { it.amount }.toSet(), setOf(75_000.sat))
        }
    }

    @Test
    fun `swap funds -- ignore inputs from pending channel`() {
        val (waitForFundingSigned, _) = WaitForFundingSignedTestsCommon.init()
        val wallet = run {
            val utxos = waitForFundingSigned.state.signingSession.fundingTx.tx.localInputs.map { i -> WalletState.Utxo(i.outPoint.txid, i.outPoint.index.toInt(), 100, i.previousTx, WalletState.Companion.AddressMeta.Single) }
            val addressState = WalletState.Companion.AddressState(WalletState.Companion.AddressMeta.Single, alreadyUsed = true, utxos)
            WalletState(mapOf(dummyAddress to addressState))
        }
        val mgr = SwapInManager(listOf(waitForFundingSigned.state), logger)
        val cmd = SwapInCommand.TrySwapIn(currentBlockHeight = 150, wallet = wallet, swapInParams = SwapInParams(minConfirmations = 5, maxConfirmations = 720, refundDelay = 900), trustedTxs = emptySet())
        mgr.process(cmd).also { assertNull(it) }

        // The pending channel is aborted: we can reuse those inputs.
        mgr.process(SwapInCommand.UnlockWalletInputs(wallet.utxos.map { it.outPoint }.toSet()))
        mgr.process(cmd).also { assertNotNull(it) }
    }

    @Test
    fun `swap funds -- ignore inputs from pending splices`() {
        val (alice, bob) = TestsHelper.reachNormal(zeroConf = true)
        val (alice1, _) = SpliceTestsCommon.spliceIn(alice, bob, listOf(50_000.sat, 75_000.sat))
        assertEquals(2, alice1.commitments.active.size)
        val inputs = alice1.commitments.active.map { it.localFundingStatus }.filterIsInstance<LocalFundingStatus.UnconfirmedFundingTx>().flatMap { it.sharedTx.tx.localInputs }
        assertEquals(3, inputs.size) // 1 initial funding input and 2 splice inputs
        val wallet = run {
            val utxos = inputs.map { i -> WalletState.Utxo(i.outPoint.txid, i.outPoint.index.toInt(), 100, i.previousTx, WalletState.Companion.AddressMeta.Single) }
            val addressState = WalletState.Companion.AddressState(WalletState.Companion.AddressMeta.Single, alreadyUsed = true, utxos)
            WalletState(mapOf(dummyAddress to addressState))
        }
        val mgr = SwapInManager(listOf(alice1.state), logger)
        val cmd = SwapInCommand.TrySwapIn(currentBlockHeight = 150, wallet = wallet, swapInParams = SwapInParams(minConfirmations = 5, maxConfirmations = 720, refundDelay = 900), trustedTxs = emptySet())
        mgr.process(cmd).also { assertNull(it) }

        // The channel is aborted: we can reuse those inputs.
        mgr.process(SwapInCommand.UnlockWalletInputs(wallet.utxos.map { it.outPoint }.toSet()))
        mgr.process(cmd).also { result ->
            assertNotNull(result)
            assertEquals(3, result.walletInputs.size)
        }
    }

    @Test
    fun `swap funds -- ignore inputs from confirmed splice`() {
        val (alice, bob) = TestsHelper.reachNormal(zeroConf = true)
        val (alice1, _) = SpliceTestsCommon.spliceIn(alice, bob, listOf(50_000.sat, 75_000.sat))
        assertEquals(2, alice1.commitments.active.size)
        assertIs<LocalFundingStatus.UnconfirmedFundingTx>(alice1.commitments.latest.localFundingStatus)
        val inputs = (alice1.commitments.latest.localFundingStatus as LocalFundingStatus.UnconfirmedFundingTx).sharedTx.tx.localInputs
        assertEquals(2, inputs.size) // 2 splice inputs
        val spliceTx = alice1.commitments.latest.localFundingStatus.signedTx!!
        val (alice2, _) = alice1.process(ChannelCommand.WatchReceived(WatchEventConfirmed(alice.channelId, BITCOIN_FUNDING_DEPTHOK, 100, 2, spliceTx)))
        val (alice3, _) = alice2.process(ChannelCommand.MessageReceived(SpliceLocked(alice.channelId, spliceTx.txid)))
        assertIs<LNChannel<Normal>>(alice3)
        assertEquals(1, alice3.commitments.all.size)
        assertIs<LocalFundingStatus.ConfirmedFundingTx>(alice3.commitments.latest.localFundingStatus)
        val wallet = run {
            val utxos = inputs.map { i -> WalletState.Utxo(i.outPoint.txid, i.outPoint.index.toInt(), 100, i.previousTx, WalletState.Companion.AddressMeta.Single) }
            val addressState = WalletState.Companion.AddressState(WalletState.Companion.AddressMeta.Single, alreadyUsed = true, utxos)
            WalletState(mapOf(dummyAddress to addressState))
        }
        val mgr = SwapInManager(listOf(alice3.state), logger)
        val cmd = SwapInCommand.TrySwapIn(currentBlockHeight = 150, wallet = wallet, swapInParams = SwapInParams(minConfirmations = 5, maxConfirmations = 720, refundDelay = 900), trustedTxs = emptySet())
        mgr.process(cmd).also { assertNull(it) }
    }

}