package fr.acinq.lightning

import fr.acinq.bitcoin.*
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.transactions.Scripts
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector
import kotlin.test.Test

class UnlockSwapInTestsCommon : LightningTestSuite() {
    @Test
    fun `user sig swap-in`() {
        val wordlist = "time goose parade weather erase hood music card ladder meadow neck melt"
        val seed = MnemonicCode.toSeed(wordlist, "").toByteVector()
        val chain = NodeParams.Chain.Testnet
        val remoteSwapInXpub = "tpubDAmCFB21J9ExKBRPDcVxSvGs9jtcf8U1wWWbS1xTYmnUsuUHPCoFdCnEGxLE3THSWcQE48GHJnyz8XPbYUivBMbLSMBifFd3G9KmafkM9og"
        val keyManager = LocalKeyManager(seed, chain, remoteSwapInXpub)
        println("chain: $chain")
        println("nodeId: ${keyManager.nodeKeys.nodeKey.publicKey}")

        val parentAmount = 12_300.sat
        val unsignedTx =
            Transaction.read("02000000000101fff219b918475e8a94a52c040b2a6e365709085d7c2115ad84ef37131082281101000000000000000001a72e000000000000220020509d2344cb556558f445cead2bec51f07f64e2c98f1d44f731077164649234390309300602010002010001093006020100020100014d2102ebf48b7f7ea18beeefad3edc0f228cb3a2e32c961800fd7668af9d3bc27def50ad2102eaeaebe26cbef620e88baa49a2d406e0ebb61dd77255a8f66a7128aec3f08ec9ac7364024065b26800000000")
        val userSig = Transactions.signSwapInputUser(
            fundingTx = unsignedTx,
            index = 0,
            parentTxOut = TxOut(parentAmount, ByteVector.empty),
            userKey = keyManager.swapInOnChainWallet.userPrivateKey,
            serverKey = keyManager.swapInOnChainWallet.remoteServerPublicKey,
            refundDelay = 144 * 30 * 6
        )

        println("txId: ${unsignedTx.txid}")
        println("userSig: $userSig")
    }

    @Test
    fun `build fully signed tx swap-in`() {
        val unsignedTx =
            Transaction.read("02000000000101fff219b918475e8a94a52c040b2a6e365709085d7c2115ad84ef37131082281101000000000000000001a72e000000000000220020509d2344cb556558f445cead2bec51f07f64e2c98f1d44f731077164649234390309300602010002010001093006020100020100014d2102ebf48b7f7ea18beeefad3edc0f228cb3a2e32c961800fd7668af9d3bc27def50ad2102eaeaebe26cbef620e88baa49a2d406e0ebb61dd77255a8f66a7128aec3f08ec9ac7364024065b26800000000")

        val userKey = PublicKey.fromHex("03355ddec28be82d5c6582ab2f89f7efd310f5efd070cbb7d17b15dd993d093ff3")
        val serverKey = PublicKey.fromHex("0255f4048d1ffd808babebfa09a5f8499e748322920faca0deaf1342c8eb50f9ff")

        val userSig = ByteVector64("a61bd3a5ecd7320604c12294a3fa82229c02d9dcb7986fb83b10f411df131558189f5371437cae884b458b31dd8579f0ec1e2a3e78dbd930584a159d0f5f57f2")
        val serverSig = ByteVector64("0270a4703f15722b86410644bf768db055474be607010b56b044ae7acf6dd61171127eb8d940df77b32287f634e76200a64ba3fc954ac96708dbc605142b4b75")

        val swapInRefundDelay = 144 * 30 * 6

        val witness = Scripts.witnessSwapIn2of2(userSig, userKey, serverSig, serverKey, swapInRefundDelay)
        val signedTx = unsignedTx.copy(txIn = listOf(unsignedTx.txIn.first().copy(witness = witness)))
        println("signedTx: $signedTx")
    }

}