package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.crypto.CommitmentPublicKeys
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.crypto.RemoteCommitmentKeys
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.transactions.Scripts
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.toByteVector32
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RecoveryTestsCommon {

    @Test
    fun `use funding pubkeys from published commitment to spend our output`() {
        // Alice creates and uses a LN channel to Bob
        val (alice, bob) = TestsHelper.reachNormal()
        val (alice1, _) = TestsHelper.addHtlc(MilliSatoshi(50000), alice, bob).first

        // Alice force-closes the channel and publishes her commit tx
        val (_, actions) = alice1.process(ChannelCommand.Close.ForceClose)
        val transactions = actions.findPublishTxs()
        val commitTx = transactions[0]
        val aliceTx = transactions[1]

        // how can Bob find and spend his output in Alice's published commit tx with just his wallet seed (derived from his mnemonic words) and nothing else?

        // extract funding pubkeys from the commit tx witness, which is a multisig 2-of-2
        val redeemScript = Script.parse(commitTx.txIn[0].witness.last())
        assertTrue(redeemScript.size == 5 && redeemScript[0] == OP_2 && redeemScript[3] == OP_2 && redeemScript[4] == OP_CHECKMULTISIG)
        val pub1 = PublicKey((redeemScript[1] as OP_PUSHDATA).data)
        val pub2 = PublicKey((redeemScript[2] as OP_PUSHDATA).data)

        // use Bob's mnemonic words to initialise his key manager
        val seed = MnemonicCode.toSeed(TestConstants.Bob.mnemonics, "").toByteVector32()
        val keyManager = LocalKeyManager(seed, Chain.Regtest, TestConstants.aliceSwapInServerXpub)

        // recompute our channel keys from the extracted funding pubkey and see if we can find and spend our output
        // we only need our payment key and basepoint for our main output
        fun findAndSpend(fundingKey: PublicKey): Transaction? {
            val channelKeys = keyManager.recoverChannelKeys(fundingKey)
            val commitKeys = RemoteCommitmentKeys(
                ourPaymentKey = channelKeys.paymentKey,
                theirDelayedPaymentPublicKey = randomKey().publicKey(),
                ourPaymentBasePoint = channelKeys.paymentBasepoint,
                ourHtlcKey = randomKey(),
                theirHtlcPublicKey = randomKey().publicKey(),
                revocationPublicKey = randomKey().publicKey()
            )
            val finalScript = Script.write(Script.pay2wpkh(fundingKey)).byteVector()
            val mainTx = Transactions.ClaimRemoteDelayedOutputTx.createUnsignedTx(
                commitKeys,
                commitTx,
                TestConstants.Bob.nodeParams.dustLimit,
                finalScript,
                FeeratePerKw(750.sat()),
                Transactions.CommitmentFormat.AnchorOutputs
            ).map { it.sign().tx }.right
            mainTx?.let { Transaction.correctlySpends(it, commitTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }
            return mainTx
        }

        // this is the script of the output that we're spending
        val bobTx = findAndSpend(pub1) ?: findAndSpend(pub2)!!
        assertNotEquals(aliceTx, bobTx)

        val outputScript = Script.parse(commitTx.txOut[bobTx.txIn[0].outPoint.index.toInt()].publicKeyScript)

        // this is what our main output script should be
        fun ourDelayedOutputScript(pub: PublicKey): List<ScriptElt> {
            val channelKeys = keyManager.recoverChannelKeys(pub)
            val commitKeys = CommitmentPublicKeys(
                localDelayedPaymentPublicKey = randomKey().publicKey(),
                remotePaymentPublicKey = channelKeys.paymentBasepoint,
                localHtlcPublicKey = randomKey().publicKey(),
                remoteHtlcPublicKey = randomKey().publicKey(),
                revocationPublicKey = randomKey().publicKey()
            )
            return Script.pay2wsh(Scripts.toRemoteDelayed(commitKeys))
        }

        assertTrue(outputScript == ourDelayedOutputScript(pub1) || outputScript == ourDelayedOutputScript(pub2))
    }

}
