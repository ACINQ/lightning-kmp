package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.utils.sat
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ElectrumMiniWalletTest : LightningTestSuite() {

    @Test
    fun `single address with no utxos`() = runSuspendTest(timeout = 15.seconds) {
        val client = connectToMainnetServer()
        val wallet = ElectrumMiniWallet(Block.LivenetGenesisBlock.hash, client, this, loggerFactory)
        wallet.addAddress("bc1qyjmhaptq78vh5j7tnzu7ujayd8sftjahphxppz")

        val walletState = wallet.walletStateFlow.first { it.addresses.isNotEmpty() }

        assertEquals(0, walletState.utxos.size)
        assertEquals(0.sat, walletState.totalBalance)

        wallet.stop()
        client.stop()
    }

    @Test
    fun `single address with existing utxos`() = runSuspendTest(timeout = 15.seconds) {
        val client = connectToMainnetServer()
        val wallet = ElectrumMiniWallet(Block.LivenetGenesisBlock.hash, client, this, loggerFactory)
        wallet.addAddress("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2")

        val walletState = wallet.walletStateFlow.first { it.addresses.isNotEmpty() }

        // This address has 3 transactions confirmed at block 100 002 and 3 transactions confirmed at block 100 003.
        assertEquals(6, walletState.utxos.size)
        assertEquals(30_000_000.sat, walletState.totalBalance)
        val swapInParams = SwapInParams(minConfirmations = 3, maxConfirmations = 10, refundDelay = 11)

        run {
            val withConf = walletState.withConfirmations(currentBlockHeight = 100_004, swapInParams)
            assertEquals(0, withConf.unconfirmed.size)
            assertEquals(3, withConf.weaklyConfirmed.size)
            assertEquals(3, withConf.deeplyConfirmed.size)
            assertEquals(0, withConf.lockedUntilRefund.size)
            assertEquals(0, withConf.readyForRefund.size)
            assertEquals(15_000_000.sat, withConf.weaklyConfirmed.balance)
            assertEquals(15_000_000.sat, withConf.deeplyConfirmed.balance)
        }
        run {
            val withConf = walletState.withConfirmations(currentBlockHeight = 100_005, swapInParams)
            assertEquals(0, withConf.unconfirmed.size)
            assertEquals(0, withConf.weaklyConfirmed.size)
            assertEquals(6, withConf.deeplyConfirmed.size)
            assertEquals(0, withConf.lockedUntilRefund.size)
            assertEquals(0, withConf.readyForRefund.size)
            assertEquals(30_000_000.sat, withConf.deeplyConfirmed.balance)
        }
        run {
            val withConf = walletState.withConfirmations(currentBlockHeight = 100_011, swapInParams)
            assertEquals(0, withConf.unconfirmed.size)
            assertEquals(0, withConf.weaklyConfirmed.size)
            assertEquals(3, withConf.deeplyConfirmed.size)
            assertEquals(3, withConf.lockedUntilRefund.size)
            assertEquals(0, withConf.readyForRefund.size)
            assertEquals(15_000_000.sat, withConf.deeplyConfirmed.balance)
            assertEquals(15_000_000.sat, withConf.lockedUntilRefund.balance)
        }
        run {
            val withConf = walletState.withConfirmations(currentBlockHeight = 100_012, swapInParams)
            assertEquals(0, withConf.unconfirmed.size)
            assertEquals(0, withConf.weaklyConfirmed.size)
            assertEquals(0, withConf.deeplyConfirmed.size)
            assertEquals(3, withConf.lockedUntilRefund.size)
            assertEquals(3, withConf.readyForRefund.size)
            assertEquals(15_000_000.sat, withConf.lockedUntilRefund.balance)
            assertEquals(15_000_000.sat, withConf.readyForRefund.balance)
        }
        run {
            val withConf = walletState.withConfirmations(currentBlockHeight = 100_013, swapInParams)
            assertEquals(0, withConf.unconfirmed.size)
            assertEquals(0, withConf.weaklyConfirmed.size)
            assertEquals(0, withConf.deeplyConfirmed.size)
            assertEquals(0, withConf.lockedUntilRefund.size)
            assertEquals(6, withConf.readyForRefund.size)
            assertEquals(30_000_000.sat, withConf.readyForRefund.balance)
        }

        wallet.stop()
        client.stop()
    }

    @Test
    fun `multiple addresses`() = runSuspendTest(timeout = 15.seconds) {
        val client = connectToMainnetServer()
        val wallet = ElectrumMiniWallet(Block.LivenetGenesisBlock.hash, client, this, loggerFactory)
        wallet.addAddress("16MmJT8VqW465GEyckWae547jKVfMB14P8")
        wallet.addAddress("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2")
        wallet.addAddress("1NHFyu1uJ1UoDjtPjqZ4Et3wNCyMGCJ1qV")

        val walletState = wallet.walletStateFlow.first { it.utxos.size == 11 }

        // this has been checked on the blockchain
        assertEquals(4 + 6 + 1, walletState.utxos.size)
        assertEquals(72_000_000.sat + 30_000_000.sat + 2_000_000.sat, walletState.totalBalance)
        assertEquals(11, walletState.utxos.size)
        // make sure txid is correct has electrum api is confusing
        assertContains(
            walletState.utxos,
            WalletState.Utxo(
                previousTx = Transaction.read("0100000001758713310361270b5ec4cae9b0196cb84fdb2f174d29f9367ad341963fa83e56010000008b483045022100d7b8759aeffe9d829a5df062420eb25017d7341244e49cfede16136a0c0b8dd2022031b42048e66b1f82f7fa99a22954e2709269838ef587c20118e493ced0d63e21014104b9251638d1475b9c62e1cf03129c835bcd5ab843aa0016412e8b39e3f8f7188d3b59023bce2002a2e409ea070c7070392b65d9ae8c8631ae2672a8fbb4f62bbdffffffff02404b4c00000000001976a9143675767783fdf1922f57ab4bb783f3a88dfa609488ac404b4c00000000001976a9142b6ba7c9d796b75eef7942fc9288edd37c32f5c388ac00000000"),
                outputIndex = 1,
                blockHeight = 100_003,
                txId = TxId("971af80218684017722429be08548d1f30a2f1f220abc064380cbca5cabf7623")
            )
        )

        assertEquals(
            expected = setOf(
                Triple("16MmJT8VqW465GEyckWae547jKVfMB14P8", TxId("c1e943938e0bf2e9e6feefe22af0466514a58e9f7ed0f7ada6fd8e6dbeca0742") to 1, 39_000_000.sat),
                Triple("16MmJT8VqW465GEyckWae547jKVfMB14P8", TxId("2cf392ecf573a638f01f72c276c3b097d05eb58f39e165eacc91b8a8df09fbd8") to 0, 12_000_000.sat),
                Triple("16MmJT8VqW465GEyckWae547jKVfMB14P8", TxId("149a098d6261b7f9359a572d797c4a41b62378836a14093912618b15644ba402") to 1, 11_000_000.sat),
                Triple("16MmJT8VqW465GEyckWae547jKVfMB14P8", TxId("2dd9cb7bcebb74b02efc85570a462f22a54a613235bee11d0a2c791342a26007") to 1, 10_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", TxId("71b3dbaca67e9f9189dad3617138c19725ab541ef0b49c05a94913e9f28e3f4e") to 0, 5_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", TxId("21d2eb195736af2a40d42107e6abd59c97eb6cffd4a5a7a7709e86590ae61987") to 0, 5_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", TxId("74d681e0e03bafa802c8aa084379aa98d9fcd632ddc2ed9782b586ec87451f20") to 1, 5_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", TxId("563ea83f9641d37a36f9294d172fdb4fb86c19b0e9cac45e0b27610331138775") to 0, 5_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", TxId("971af80218684017722429be08548d1f30a2f1f220abc064380cbca5cabf7623") to 1, 5_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", TxId("b1ec9c44009147f3cee26caba45abec2610c74df9751fad14074119b5314da21") to 0, 5_000_000.sat),
                Triple("1NHFyu1uJ1UoDjtPjqZ4Et3wNCyMGCJ1qV", TxId("602839d82ac6c9aafd1a20fff5b23e11a99271e7cc238d2e48b352219b2b87ab") to 1, 2_000_000.sat),
            ),
            actual = walletState.utxos.map {
                val txOut = it.previousTx.txOut[it.outputIndex]
                val address = Bitcoin.addressFromPublicKeyScript(Block.LivenetGenesisBlock.hash, txOut.publicKeyScript.toByteArray()).result!!
                Triple(address, it.previousTx.txid to it.outputIndex, txOut.amount)
            }.toSet()
        )

        wallet.stop()
        client.stop()
    }

    @Test
    fun `parallel wallets`() = runSuspendTest(timeout = 15.seconds) {
        val client = connectToMainnetServer()
        val wallet1 = ElectrumMiniWallet(Block.LivenetGenesisBlock.hash, client, this, loggerFactory, name = "addr-16MmJT")
        val wallet2 = ElectrumMiniWallet(Block.LivenetGenesisBlock.hash, client, this, loggerFactory, name = "addr-14xb2H")
        wallet1.addAddress("16MmJT8VqW465GEyckWae547jKVfMB14P8")
        wallet2.addAddress("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2")

        val walletState1 = wallet1.walletStateFlow.first { it.utxos.size == 4 }
        val walletState2 = wallet2.walletStateFlow.first { it.utxos.size == 6 }

        assertEquals(7_200_0000.sat, walletState1.totalBalance)
        assertEquals(3_000_0000.sat, walletState2.totalBalance)

        assertEquals(4, walletState1.utxos.size)
        assertEquals(6, walletState2.utxos.size)

        wallet1.stop()
        wallet2.stop()
        client.stop()
    }
}
