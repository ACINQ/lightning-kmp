package fr.acinq.lightning

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector

fun main() {
    print("Chain (testnet,mainnet): ")
    val chain = when (readln()) {
        "testnet" -> NodeParams.Chain.Testnet
        "mainnet" -> NodeParams.Chain.Mainnet
        else -> error("invalid chain")
    }

    print("Seed (12 words): ")
    val wordlist = readln()
    val seed = MnemonicCode.toSeed(wordlist, "").toByteVector()

    val remoteSwapInXpub = when (chain) {
        is NodeParams.Chain.Testnet -> "tpubDAmCFB21J9ExKBRPDcVxSvGs9jtcf8U1wWWbS1xTYmnUsuUHPCoFdCnEGxLE3THSWcQE48GHJnyz8XPbYUivBMbLSMBifFd3G9KmafkM9og"
        is NodeParams.Chain.Mainnet -> "xpub69q3sDXXsLuHVbmTrhqmEqYqTTsXJKahdfawXaYuUt6muf1PbZBnvqzFcwiT8Abpc13hY8BFafakwpPbVkatg9egwiMjed1cRrPM19b2Ma7"
        else -> error("invalid chain")
    }

    print("Amount (sat): ")
    val parentAmount = readln().toLong().sat

    print("Unsigned tx (hex): ")
    val unsignedTx = Transaction.read(readln())

    val keyManager = LocalKeyManager(seed, chain, remoteSwapInXpub)
    println("nodeId: ${keyManager.nodeKeys.nodeKey.publicKey}")

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