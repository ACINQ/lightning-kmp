package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.tests.utils.LightningTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals

class WalletStateUnitTest : LightningTestSuite() {

    @Test
    fun `sign a p2wpkh tx`() {

        // this tx pays 0.038 BTC to p2wpkh tb1qtkl49wxh4a8mt7dhtwqg7z5zs3yn2vdn6qcz7p
        val parentTx =
            Transaction.Companion.read("01000000000101c37055af18a2ab1714e648fe5140982ceec74b6ed9376292662465c9ec3f5bf20000000000ffffffff01c0fb3900000000001600145dbf52b8d7af4fb5f9b75b808f0a8284493531b302483045022100966d17538e775b45bc1a995ff21871e0ecd428895bfe685f9016b1ea0cc2ff810220277e8f64b3827b8c79db34cfaf06af5660d9c3ebcb3f8ae857cf44a138b3cf620121030533e1d2e9b7576fef26de1f34d67887158b7af1b040850aab6024b07925d70a00000000")
        val utxo = UnspentItem(parentTx.txid, 0, 3800000, 0)

        val priv1 = PrivateKey.fromBase58("cV7LGVeY2VPuCyCSarqEqFCUNig2NzwiAEBTTA89vNRQ4Vqjfurs", Base58.Prefix.SecretKeyTestnet).first
        val pub1 = priv1.publicKey()
        val address1 = Bitcoin.computeP2WpkhAddress(pub1, Block.TestnetGenesisBlock.hash)
        assertEquals(address1, "tb1qtkl49wxh4a8mt7dhtwqg7z5zs3yn2vdn6qcz7p")
        val scriptPubKey1 = ByteVector(Script.write(Script.pay2wpkh(pub1)))
        assertEquals(scriptPubKey1, ByteVector.fromHex("00145dbf52b8d7af4fb5f9b75b808f0a8284493531b3"))


        val walletState = WalletState(
            mapOf(
                scriptPubKey1 to (listOf(utxo) to priv1),
                ByteVector(Script.write(Script.pay2wpkh(randomKey().publicKey()))) to (emptyList<UnspentItem>() to randomKey()),
                ByteVector(Script.write(Script.pay2wpkh(randomKey().publicKey()))) to (emptyList<UnspentItem>() to null)
            )
        )

        val unsignedTx = Transaction(
            version = 1,
            txIn = listOf(TxIn(utxo.outPoint, sequence = 0xffffffffL)),
            txOut = listOf(
                TxOut(
                    3700000L.toSatoshi(),
                    scriptPubKey1
                )
            ), // we reuse the same output script but if could be anything else
            lockTime = 0
        )

        val signedTx = walletState.sign(unsignedTx)
        Transaction.correctlySpends(signedTx, listOf(parentTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        assertEquals(signedTx.txid, ByteVector32.fromValidHex("fe94ff363b534adb1b880cedbbfab4ebdf2d7b4752614071446cb1fb28658696"))
        // this tx was published on testnet
    }
}