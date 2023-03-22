package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomBytes64
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.blockchain.electrum.UnspentItem
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.transactions.Scripts
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.wire.*
import kotlin.test.*

class InteractiveTxTestsCommon : LightningTestSuite() {

    @Test
    fun `initiator funds more than non-initiator`() {
        val targetFeerate = FeeratePerKw(5000.sat)
        val fundingA = 120_000.sat
        val utxosA = listOf(50_000.sat, 35_000.sat, 60_000.sat)
        val fundingB = 40_000.sat
        val utxosB = listOf(100_000.sat)
        val f = createFixture(fundingA, utxosA, fundingB, utxosB, targetFeerate, 660.sat, 42)
        assertEquals(f.fundingParamsA.fundingPubkeyScript, f.fundingParamsB.fundingPubkeyScript)
        assertEquals(f.fundingParamsA.fundingAmount, 160_000.sat)
        assertEquals(f.fundingParamsA.fundingAmount, f.fundingParamsB.fundingAmount)

        val alice0 = InteractiveTxSession(f.fundingParamsA, 0.sat, 0.sat, f.fundingContributionsA)
        val bob0 = InteractiveTxSession(f.fundingParamsB, 0.sat, 0.sat, f.fundingContributionsB)
        // Alice --- tx_add_input --> Bob
        val (alice1, inputA1) = sendMessage<TxAddInput>(alice0)
        assertEquals(0xfffffffdU, inputA1.sequence)
        // Alice <-- tx_add_input --- Bob
        val (bob1, inputB1) = receiveMessage<TxAddInput>(bob0, inputA1)
        // Alice --- tx_add_input --> Bob
        val (alice2, inputA2) = receiveMessage<TxAddInput>(alice1, inputB1)
        // Alice <-- tx_add_output --- Bob
        val (bob2, outputB1) = receiveMessage<TxAddOutput>(bob1, inputA2)
        // Alice --- tx_add_input --> Bob
        val (alice3, inputA3) = receiveMessage<TxAddInput>(alice2, outputB1)
        // Alice <-- tx_complete --- Bob
        val (bob3, txCompleteB) = receiveMessage<TxComplete>(bob2, inputA3)
        // Alice --- tx_add_output --> Bob
        val (alice4, outputA1) = receiveMessage<TxAddOutput>(alice3, txCompleteB)
        // Alice <-- tx_complete --- Bob
        val (bob4, _) = receiveMessage<TxComplete>(bob3, outputA1)
        // Alice --- tx_add_output --> Bob
        val (alice5, outputA2) = receiveMessage<TxAddOutput>(alice4, txCompleteB)
        assertFalse(alice5.isComplete)
        // Alice <-- tx_complete --- Bob
        val (bob5, _) = receiveMessage<TxComplete>(bob4, outputA2)
        assertFalse(bob5.isComplete)
        // Alice --- tx_complete --> Bob
        val sharedTxA = receiveFinalMessage(alice5, txCompleteB)
        assertNotNull(sharedTxA.txComplete)
        val sharedTxB = receiveFinalMessage(bob5, sharedTxA.txComplete!!)
        assertNull(sharedTxB.txComplete)

        // Alice is responsible for adding the shared output.
        assertNotEquals(outputA1.pubkeyScript, outputA2.pubkeyScript)
        assertEquals(listOf(outputA1, outputA2).count { it.pubkeyScript == f.fundingParamsA.fundingPubkeyScript && it.amount == f.fundingParamsA.fundingAmount }, 1)

        assertEquals(sharedTxA.sharedTx.localAmountIn, 145_000.sat)
        assertEquals(sharedTxA.sharedTx.remoteAmountIn, 100_000.sat)
        assertEquals(sharedTxA.sharedTx.totalAmountIn, 245_000.sat)
        assertEquals(sharedTxA.sharedTx.fees, 7760.sat)
        assertTrue(sharedTxB.sharedTx.localFees < sharedTxA.sharedTx.localFees)

        // Bob sends signatures first as he contributed less than Alice.
        val signedTxB = sharedTxB.sharedTx.sign(f.keyManagerB, f.fundingParamsB, f.localParamsB)
        assertNotNull(signedTxB)
        assertEquals(signedTxB.localSigs.witnesses.size, 1)
        assertNull(sharedTxB.sharedTx.sign(f.keyManagerA, f.fundingParamsA, f.localParamsA))

        // Alice detects invalid signatures from Bob.
        val sigsInvalidTxId = signedTxB.localSigs.copy(txHash = randomBytes32())
        assertNull(sharedTxA.sharedTx.sign(f.keyManagerA, f.fundingParamsA, f.localParamsA)?.addRemoteSigs(f.fundingParamsA, sigsInvalidTxId))
        val sigsMissingWitness = signedTxB.localSigs.copy(witnesses = listOf())
        assertNull(sharedTxA.sharedTx.sign(f.keyManagerA, f.fundingParamsA, f.localParamsA)?.addRemoteSigs(f.fundingParamsA, sigsMissingWitness))
        val sigsInvalidWitness = signedTxB.localSigs.copy(witnesses = listOf(Script.witnessPay2wpkh(Transactions.PlaceHolderPubKey, Transactions.PlaceHolderSig)))
        assertNull(sharedTxA.sharedTx.sign(f.keyManagerA, f.fundingParamsA, f.localParamsA)?.addRemoteSigs(f.fundingParamsA, sigsInvalidWitness))

        // The resulting transaction is valid and has the right feerate.
        val signedTxA = sharedTxA.sharedTx.sign(f.keyManagerA, f.fundingParamsA, f.localParamsA)?.addRemoteSigs(f.fundingParamsA, signedTxB.localSigs)
        assertNotNull(signedTxA)
        assertNull(sharedTxA.sharedTx.sign(f.keyManagerB, f.fundingParamsB, f.localParamsB))
        assertEquals(signedTxA.localSigs.witnesses.size, 3)
        val signedTx = signedTxA.signedTx
        assertEquals(signedTxA.localSigs.txId, signedTx.txid)
        assertEquals(signedTxB.localSigs.txId, signedTx.txid)
        assertEquals(signedTx.lockTime, 42)
        assertEquals(signedTx.txIn.size, 4)
        assertEquals(signedTx.txOut.size, 3)
        Transaction.correctlySpends(signedTx, (sharedTxA.sharedTx.localInputs + sharedTxB.sharedTx.localInputs).map { it.previousTx }, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        val feerate = Transactions.fee2rate(signedTxA.tx.fees, signedTx.weight())
        assertTrue(targetFeerate <= feerate && feerate <= targetFeerate * 1.25, "unexpected feerate (target=$targetFeerate actual=$feerate)")
    }

    @Test
    fun `initiator funds less than non-initiator`() {
        val targetFeerate = FeeratePerKw(3000.sat)
        val fundingA = 10_000.sat
        val utxosA = listOf(50_000.sat)
        val fundingB = 50_000.sat
        val utxosB = listOf(80_000.sat)
        val f = createFixture(fundingA, utxosA, fundingB, utxosB, targetFeerate, 660.sat, 0)
        assertEquals(f.fundingParamsA.fundingAmount, 60_000.sat)

        val alice0 = InteractiveTxSession(f.fundingParamsA, 0.sat, 0.sat, f.fundingContributionsA)
        val bob0 = InteractiveTxSession(f.fundingParamsB, 0.sat, 0.sat, f.fundingContributionsB)
        // Even though the initiator isn't contributing, they're paying the fees for the common parts of the transaction.
        // Alice --- tx_add_input --> Bob
        val (alice1, inputA) = sendMessage<TxAddInput>(alice0)
        // Alice <-- tx_add_input --- Bob
        val (bob1, inputB) = receiveMessage<TxAddInput>(bob0, inputA)
        // Alice --- tx_add_output --> Bob
        val (alice2, outputA1) = receiveMessage<TxAddOutput>(alice1, inputB)
        // Alice <-- tx_add_output --- Bob
        val (bob2, outputB) = receiveMessage<TxAddOutput>(bob1, outputA1)
        // Alice --- tx_add_output --> Bob
        val (alice3, outputA2) = receiveMessage<TxAddOutput>(alice2, outputB)
        // Alice <-- tx_complete --- Bob
        val (bob3, txCompleteB) = receiveMessage<TxComplete>(bob2, outputA2)
        // Alice --- tx_complete --> Bob
        val sharedTxA = receiveFinalMessage(alice3, txCompleteB)
        assertNotNull(sharedTxA.txComplete)
        val sharedTxB = receiveFinalMessage(bob3, sharedTxA.txComplete!!)
        assertNull(sharedTxB.txComplete)

        // Alice is responsible for adding the shared output.
        assertNotEquals(outputA1.pubkeyScript, outputA2.pubkeyScript)
        assertEquals(listOf(outputA1, outputA2).count { it.pubkeyScript == f.fundingParamsA.fundingPubkeyScript && it.amount == f.fundingParamsA.fundingAmount }, 1)

        assertEquals(sharedTxA.sharedTx.totalAmountIn, 130_000.sat)
        assertEquals(sharedTxA.sharedTx.fees, 3024.sat)
        assertTrue(sharedTxB.sharedTx.localFees < sharedTxA.sharedTx.localFees)

        // Alice sends signatures first as she contributed less than Bob.
        val signedTxA = sharedTxA.sharedTx.sign(f.keyManagerA, f.fundingParamsA, f.localParamsA)
        assertNotNull(signedTxA)
        assertEquals(signedTxA.localSigs.witnesses.size, 1)

        // The resulting transaction is valid and has the right feerate.
        val signedTxB = sharedTxB.sharedTx.sign(f.keyManagerB, f.fundingParamsB, f.localParamsB)?.addRemoteSigs(f.fundingParamsB, signedTxA.localSigs)
        assertNotNull(signedTxB)
        assertEquals(signedTxB.localSigs.witnesses.size, 1)
        val signedTx = signedTxB.signedTx
        assertEquals(signedTxA.localSigs.txId, signedTx.txid)
        assertEquals(signedTxB.localSigs.txId, signedTx.txid)
        assertEquals(signedTx.lockTime, 0)
        assertEquals(signedTx.txIn.size, 2)
        assertEquals(signedTx.txOut.size, 3)
        Transaction.correctlySpends(signedTx, (sharedTxA.sharedTx.localInputs + sharedTxB.sharedTx.localInputs).map { it.previousTx }, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        val feerate = Transactions.fee2rate(signedTxB.tx.fees, signedTx.weight())
        assertTrue(targetFeerate <= feerate && feerate <= targetFeerate * 1.25, "unexpected feerate (target=$targetFeerate actual=$feerate)")
    }

    @Test
    fun `initiator funds more than non-initiator but contributes less`() {
        val targetFeerate = FeeratePerKw(5000.sat)
        val fundingA = 100_000.sat
        val utxosA = listOf(150_000.sat)
        val fundingB = 50_000.sat
        val utxosB = listOf(200_000.sat)
        val f = createFixture(fundingA, utxosA, fundingB, utxosB, targetFeerate, 660.sat, 0)
        assertEquals(f.fundingParamsA.fundingAmount, 150_000.sat)

        val alice0 = InteractiveTxSession(f.fundingParamsA, 0.sat, 0.sat, f.fundingContributionsA)
        val bob0 = InteractiveTxSession(f.fundingParamsB, 0.sat, 0.sat, f.fundingContributionsB)
        // Alice --- tx_add_input --> Bob
        val (alice1, inputA) = sendMessage<TxAddInput>(alice0)
        // Alice <-- tx_add_input --- Bob
        val (bob1, inputB) = receiveMessage<TxAddInput>(bob0, inputA)
        // Alice --- tx_add_output --> Bob
        val (alice2, outputA1) = receiveMessage<TxAddOutput>(alice1, inputB)
        // Alice <-- tx_add_output --- Bob
        val (bob2, outputB) = receiveMessage<TxAddOutput>(bob1, outputA1)
        // Alice --- tx_add_output --> Bob
        val (alice3, outputA2) = receiveMessage<TxAddOutput>(alice2, outputB)
        // Alice <-- tx_complete --- Bob
        val (bob3, txCompleteB) = receiveMessage<TxComplete>(bob2, outputA2)
        // Alice --- tx_complete --> Bob
        val sharedTxA = receiveFinalMessage(alice3, txCompleteB)
        assertNotNull(sharedTxA.txComplete)
        val sharedTxB = receiveFinalMessage(bob3, sharedTxA.txComplete!!)
        assertNull(sharedTxB.txComplete)

        // Alice is responsible for adding the shared output.
        assertNotEquals(outputA1.pubkeyScript, outputA2.pubkeyScript)
        assertEquals(listOf(outputA1, outputA2).count { it.pubkeyScript == f.fundingParamsA.fundingPubkeyScript && it.amount == f.fundingParamsA.fundingAmount }, 1)

        assertEquals(sharedTxA.sharedTx.totalAmountIn, 350_000.sat)
        assertEquals(sharedTxA.sharedTx.fees, 5040.sat)
        assertTrue(sharedTxA.sharedTx.remoteFees < sharedTxA.sharedTx.localFees)

        // Alice contributes more than Bob to the funding output, but Bob's inputs are bigger than Alice's, so Alice must sign first.
        val signedTxA = sharedTxA.sharedTx.sign(f.keyManagerA, f.fundingParamsA, f.localParamsA)
        assertNotNull(signedTxA)
        assertEquals(signedTxA.localSigs.witnesses.size, 1)

        // The resulting transaction is valid and has the right feerate.
        val signedTxB = sharedTxB.sharedTx.sign(f.keyManagerB, f.fundingParamsB, f.localParamsB)?.addRemoteSigs(f.fundingParamsB, signedTxA.localSigs)
        assertNotNull(signedTxB)
        Transaction.correctlySpends(signedTxB.signedTx, (sharedTxA.sharedTx.localInputs + sharedTxB.sharedTx.localInputs).map { it.previousTx }, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        val feerate = Transactions.fee2rate(signedTxB.tx.fees, signedTxB.signedTx.weight())
        assertTrue(targetFeerate <= feerate && feerate <= targetFeerate * 1.25, "unexpected feerate (target=$targetFeerate actual=$feerate)")
    }

    @Test
    fun `non-initiator does not contribute`() {
        val targetFeerate = FeeratePerKw(2500.sat)
        val fundingA = 150_000.sat
        val utxosA = listOf(80_000.sat, 120_000.sat)
        val f = createFixture(fundingA, utxosA, 0.sat, listOf(), targetFeerate, 330.sat, 0)
        assertEquals(f.fundingParamsA.fundingAmount, 150_000.sat)

        val alice0 = InteractiveTxSession(f.fundingParamsA, 0.sat, 0.sat, f.fundingContributionsA)
        val bob0 = InteractiveTxSession(f.fundingParamsB, 0.sat, 0.sat, f.fundingContributionsB)
        // Alice --- tx_add_input --> Bob
        val (alice1, inputA1) = sendMessage<TxAddInput>(alice0)
        // Alice <-- tx_complete --- Bob
        val (bob1, txCompleteB) = receiveMessage<TxComplete>(bob0, inputA1)
        // Alice --- tx_add_input --> Bob
        val (alice2, inputA2) = receiveMessage<TxAddInput>(alice1, txCompleteB)
        // Alice <-- tx_complete --- Bob
        val (bob2, _) = receiveMessage<TxComplete>(bob1, inputA2)
        // Alice --- tx_add_output --> Bob
        val (alice3, outputA1) = receiveMessage<TxAddOutput>(alice2, txCompleteB)
        // Alice <-- tx_complete --- Bob
        val (bob3, _) = receiveMessage<TxComplete>(bob2, outputA1)
        // Alice --- tx_add_output --> Bob
        val (alice4, outputA2) = receiveMessage<TxAddOutput>(alice3, txCompleteB)
        // Alice <-- tx_complete --- Bob
        val (bob4, _) = receiveMessage<TxComplete>(bob3, outputA2)
        // Alice --- tx_complete --> Bob
        val sharedTxA = receiveFinalMessage(alice4, txCompleteB)
        assertNotNull(sharedTxA.txComplete)
        val sharedTxB = receiveFinalMessage(bob4, sharedTxA.txComplete!!)
        assertNull(sharedTxB.txComplete)

        // Alice is responsible for adding the shared output.
        assertNotEquals(outputA1.pubkeyScript, outputA2.pubkeyScript)
        assertEquals(listOf(outputA1, outputA2).count { it.pubkeyScript == f.fundingParamsA.fundingPubkeyScript && it.amount == f.fundingParamsA.fundingAmount }, 1)

        assertEquals(sharedTxA.sharedTx.totalAmountIn, 200_000.sat)
        assertEquals(sharedTxA.sharedTx.fees, 2205.sat)
        assertEquals(sharedTxA.sharedTx.localFees, 2205.sat)
        assertEquals(sharedTxA.sharedTx.remoteFees, 0.sat)
        assertEquals(sharedTxB.sharedTx.localFees, 0.sat)
        assertEquals(sharedTxB.sharedTx.remoteFees, 2205.sat)

        // Bob sends signatures first as he did not contribute at all.
        val signedTxB = sharedTxB.sharedTx.sign(LocalKeyManager(randomBytes64(), Block.RegtestGenesisBlock.hash), f.fundingParamsB, f.localParamsB)
        assertNotNull(signedTxB)
        assertEquals(signedTxB.localSigs.witnesses.size, 0)

        // The resulting transaction is valid and has the right feerate.
        val signedTxA = sharedTxA.sharedTx.sign(f.keyManagerA, f.fundingParamsA, f.localParamsA)?.addRemoteSigs(f.fundingParamsA, signedTxB.localSigs)
        assertNotNull(signedTxA)
        assertEquals(signedTxA.localSigs.witnesses.size, 2)
        val signedTx = signedTxA.signedTx
        assertEquals(signedTxA.localSigs.txId, signedTx.txid)
        assertEquals(signedTxB.localSigs.txId, signedTx.txid)
        assertEquals(signedTx.lockTime, 0)
        assertEquals(signedTx.txIn.size, 2)
        assertEquals(signedTx.txOut.size, 2)
        Transaction.correctlySpends(signedTx, sharedTxA.sharedTx.localInputs.map { it.previousTx }, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        val feerate = Transactions.fee2rate(signedTxA.tx.fees, signedTx.weight())
        assertTrue(targetFeerate <= feerate && feerate <= targetFeerate * 1.25, "unexpected feerate (target=$targetFeerate actual=$feerate)")
    }

    @Test
    fun `initiator and non-initiator splice-in`() {
        val targetFeerate = FeeratePerKw(1000.sat)
        val balanceA = 100_000.sat
        val fundingA = 130_000.sat
        val utxosA = listOf(85_000.sat)
        val balanceB = 50_000.sat
        val fundingB = 75_000.sat
        val utxosB = listOf(80_000.sat)
        val f = createSpliceFixture(balanceA, fundingA, utxosA, listOf(), balanceB, fundingB, utxosB, listOf(), targetFeerate, 330.sat, 0)
        assertEquals(f.fundingParamsA.fundingAmount, 205_000.sat)
        assertNotNull(f.fundingParamsA.sharedInput)
        assertNotNull(f.fundingParamsB.sharedInput)

        val alice0 = InteractiveTxSession(f.fundingParamsA, balanceA, balanceB, f.fundingContributionsA)
        val bob0 = InteractiveTxSession(f.fundingParamsB, balanceB, balanceA, f.fundingContributionsB)
        // Alice --- tx_add_input --> Bob
        val (alice1, inputA1) = sendMessage<TxAddInput>(alice0)
        // Alice <-- tx_add_input --- Bob
        val (bob1, inputB) = receiveMessage<TxAddInput>(bob0, inputA1)
        // Alice --- tx_add_input --> Bob
        val (alice2, inputA2) = receiveMessage<TxAddInput>(alice1, inputB)
        // Alice <-- tx_add_output --- Bob
        val (bob2, outputB) = receiveMessage<TxAddOutput>(bob1, inputA2)
        // Alice --- tx_add_output --> Bob
        val (alice3, outputA1) = receiveMessage<TxAddOutput>(alice2, outputB)
        // Alice <-- tx_complete --- Bob
        val (bob3, txCompleteB) = receiveMessage<TxComplete>(bob2, outputA1)
        // Alice --- tx_add_output --> Bob
        val (alice4, outputA2) = receiveMessage<TxAddOutput>(alice3, txCompleteB)
        // Alice <-- tx_complete --- Bob
        val (bob4, _) = receiveMessage<TxComplete>(bob3, outputA2)
        // Alice --- tx_complete --> Bob
        val sharedTxA = receiveFinalMessage(alice4, txCompleteB)
        assertNotNull(sharedTxA.txComplete)
        val sharedTxB = receiveFinalMessage(bob4, sharedTxA.txComplete!!)
        assertNull(sharedTxB.txComplete)

        // Alice is responsible for adding the shared input and the shared output.
        assertEquals(listOf(inputA1, inputA2).count { it.sharedInput == f.fundingParamsA.sharedInput?.info?.outPoint }, 1)
        assertNotEquals(outputA1.pubkeyScript, outputA2.pubkeyScript)
        assertEquals(listOf(outputA1, outputA2).count { it.pubkeyScript == f.fundingParamsA.fundingPubkeyScript && it.amount == f.fundingParamsA.fundingAmount }, 1)

        assertEquals(sharedTxA.sharedTx.totalAmountIn, 315_000.sat)
        assertNotNull(sharedTxA.sharedTx.sharedInput)
        assertEquals(sharedTxA.sharedTx.localFees, 998.sat)
        assertEquals(sharedTxA.sharedTx.remoteFees, 398.sat)
        assertNotNull(sharedTxB.sharedTx.sharedInput)
        assertEquals(sharedTxB.sharedTx.localFees, 398.sat)
        assertEquals(sharedTxB.sharedTx.remoteFees, 998.sat)

        // Bob sends signatures first as he contributed less than Alice.
        val signedTxB = sharedTxB.sharedTx.sign(f.keyManagerB, f.fundingParamsB, f.localParamsB)
        assertNotNull(signedTxB)
        assertEquals(signedTxB.localSigs.witnesses.size, 1)
        assertNotNull(signedTxB.localSigs.previousFundingTxSig)

        // The resulting transaction is valid and has the right feerate.
        val signedTxA = sharedTxA.sharedTx.sign(f.keyManagerA, f.fundingParamsA, f.localParamsA)?.addRemoteSigs(f.fundingParamsA, signedTxB.localSigs)
        assertNotNull(signedTxA)
        assertEquals(signedTxA.localSigs.witnesses.size, 1)
        assertNotNull(signedTxA.localSigs.previousFundingTxSig)
        val signedTx = signedTxA.signedTx
        assertEquals(signedTxA.localSigs.txId, signedTx.txid)
        assertEquals(signedTxB.localSigs.txId, signedTx.txid)
        assertEquals(signedTx.lockTime, 0)
        assertEquals(signedTx.txIn.size, 3)
        assertEquals(signedTx.txOut.size, 3)
        Transaction.correctlySpends(signedTx, previousOutputs(f.fundingParamsA, sharedTxA.sharedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        val feerate = Transactions.fee2rate(signedTxA.tx.fees, signedTx.weight())
        assertTrue(targetFeerate <= feerate && feerate <= targetFeerate * 1.25, "unexpected feerate (target=$targetFeerate actual=$feerate)")
    }

    @Test
    fun `initiator and non-initiator splice-out -- single`() {
        // Alice and Bob decide to splice funds out of the channel, and deduce on-chain fees from their new channel contribution.
        val balanceA = 100_000.sat
        val spliceOutputsA = listOf(TxOut(50_000.sat, Script.pay2wpkh(randomKey().publicKey())))
        val fundingA = 49_000.sat
        val balanceB = 90_000.sat
        val spliceOutputsB = listOf(TxOut(30_000.sat, Script.pay2wpkh(randomKey().publicKey())))
        val fundingB = 59_500.sat
        val f = createSpliceFixture(balanceA, fundingA, listOf(), spliceOutputsA, balanceB, fundingB, listOf(), spliceOutputsB, FeeratePerKw(1000.sat), 330.sat, 0)
        assertEquals(f.fundingParamsA.fundingAmount, 108_500.sat)
        assertNotNull(f.fundingParamsA.sharedInput)
        assertNotNull(f.fundingParamsB.sharedInput)

        val alice0 = InteractiveTxSession(f.fundingParamsA, balanceA, balanceB, f.fundingContributionsA)
        val bob0 = InteractiveTxSession(f.fundingParamsB, balanceB, balanceA, f.fundingContributionsB)
        // Alice --- tx_add_input --> Bob
        val (alice1, inputA) = sendMessage<TxAddInput>(alice0)
        // Alice <-- tx_add_output --- Bob
        val (bob1, outputB) = receiveMessage<TxAddOutput>(bob0, inputA)
        // Alice --- tx_add_output --> Bob
        val (alice2, outputA1) = receiveMessage<TxAddOutput>(alice1, outputB)
        // Alice <-- tx_complete --- Bob
        val (bob2, txCompleteB) = receiveMessage<TxComplete>(bob1, outputA1)
        // Alice --- tx_add_output --> Bob
        val (alice3, outputA2) = receiveMessage<TxAddOutput>(alice2, txCompleteB)
        // Alice <-- tx_complete --- Bob
        val (bob3, _) = receiveMessage<TxComplete>(bob2, outputA2)
        // Alice --- tx_complete --> Bob
        val sharedTxA = receiveFinalMessage(alice3, txCompleteB)
        assertNotNull(sharedTxA.txComplete)
        val sharedTxB = receiveFinalMessage(bob3, sharedTxA.txComplete!!)
        assertNull(sharedTxB.txComplete)

        // Alice is responsible for adding the shared input and the shared output.
        assertNull(inputA.previousTx)
        assertEquals(inputA.sharedInput, f.fundingParamsA.sharedInput?.info?.outPoint)
        assertNotEquals(outputA1.pubkeyScript, outputA2.pubkeyScript)
        assertEquals(listOf(outputA1, outputA2).count { it.pubkeyScript == f.fundingParamsA.fundingPubkeyScript && it.amount == f.fundingParamsA.fundingAmount }, 1)

        assertEquals(sharedTxA.sharedTx.totalAmountIn, 190_000.sat)
        assertNotNull(sharedTxA.sharedTx.sharedInput)
        assertEquals(sharedTxA.sharedTx.localFees, 1000.sat)
        assertEquals(sharedTxA.sharedTx.remoteFees, 500.sat)
        assertNotNull(sharedTxB.sharedTx.sharedInput)
        assertEquals(sharedTxB.sharedTx.localFees, 500.sat)
        assertEquals(sharedTxB.sharedTx.remoteFees, 1000.sat)

        // Bob sends signatures first as he did not contribute.
        val signedTxB = sharedTxB.sharedTx.sign(f.keyManagerB, f.fundingParamsB, f.localParamsB)
        assertNotNull(signedTxB)
        assertTrue(signedTxB.localSigs.witnesses.isEmpty())
        assertNotNull(signedTxB.localSigs.previousFundingTxSig)

        // The resulting transaction is valid.
        val signedTxA = sharedTxA.sharedTx.sign(f.keyManagerA, f.fundingParamsA, f.localParamsA)?.addRemoteSigs(f.fundingParamsA, signedTxB.localSigs)
        assertNotNull(signedTxA)
        assertTrue(signedTxA.localSigs.witnesses.isEmpty())
        assertNotNull(signedTxA.localSigs.previousFundingTxSig)
        val signedTx = signedTxA.signedTx
        assertEquals(signedTx.txIn.size, 1)
        assertEquals(signedTx.txOut.size, 3)
        assertTrue(signedTx.txOut.containsAll(spliceOutputsA))
        assertTrue(signedTx.txOut.containsAll(spliceOutputsB))
        Transaction.correctlySpends(signedTx, previousOutputs(f.fundingParamsA, sharedTxA.sharedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    @Test
    fun `initiator and non-initiator splice-out -- multiple`() {
        // Alice and Bob decide to splice funds out of the channel, and deduce on-chain fees from their new channel contribution.
        val balanceA = 150_000.sat
        val spliceOutputsA = listOf(20_000.sat, 15_000.sat, 15_000.sat).map { TxOut(it, Script.pay2wpkh(randomKey().publicKey())) }
        val fundingA = 99_000.sat
        val balanceB = 100_000.sat
        val spliceOutputsB = listOf(25_000.sat, 15_000.sat).map { TxOut(it, Script.pay2wpkh(randomKey().publicKey())) }
        val fundingB = 59_500.sat
        val f = createSpliceFixture(balanceA, fundingA, listOf(), spliceOutputsA, balanceB, fundingB, listOf(), spliceOutputsB, FeeratePerKw(1000.sat), 330.sat, 0)
        assertEquals(f.fundingParamsA.fundingAmount, 158_500.sat)
        assertNotNull(f.fundingParamsA.sharedInput)
        assertNotNull(f.fundingParamsB.sharedInput)

        val alice0 = InteractiveTxSession(f.fundingParamsA, balanceA, balanceB, f.fundingContributionsA)
        val bob0 = InteractiveTxSession(f.fundingParamsB, balanceB, balanceA, f.fundingContributionsB)
        // Alice --- tx_add_input --> Bob
        val (alice1, inputA) = sendMessage<TxAddInput>(alice0)
        // Alice <-- tx_add_output --- Bob
        val (bob1, outputB1) = receiveMessage<TxAddOutput>(bob0, inputA)
        // Alice --- tx_add_output --> Bob
        val (alice2, outputA1) = receiveMessage<TxAddOutput>(alice1, outputB1)
        // Alice <-- tx_add_output --- Bob
        val (bob2, outputB2) = receiveMessage<TxAddOutput>(bob1, outputA1)
        // Alice --- tx_add_output --> Bob
        val (alice3, outputA2) = receiveMessage<TxAddOutput>(alice2, outputB2)
        // Alice <-- tx_complete --- Bob
        val (bob3, txCompleteB) = receiveMessage<TxComplete>(bob2, outputA2)
        // Alice --- tx_add_output --> Bob
        val (alice4, outputA3) = receiveMessage<TxAddOutput>(alice3, txCompleteB)
        // Alice <-- tx_complete --- Bob
        val (bob4, _) = receiveMessage<TxComplete>(bob3, outputA3)
        // Alice --- tx_add_output --> Bob
        val (alice5, outputA4) = receiveMessage<TxAddOutput>(alice4, txCompleteB)
        // Alice <-- tx_complete --- Bob
        val (bob5, _) = receiveMessage<TxComplete>(bob4, outputA4)
        // Alice --- tx_complete --> Bob
        val sharedTxA = receiveFinalMessage(alice5, txCompleteB)
        assertNotNull(sharedTxA.txComplete)
        val sharedTxB = receiveFinalMessage(bob5, sharedTxA.txComplete!!)
        assertNull(sharedTxB.txComplete)

        // Alice is responsible for adding the shared input and the shared output.
        assertNull(inputA.previousTx)
        assertEquals(inputA.sharedInput, f.fundingParamsA.sharedInput?.info?.outPoint)
        assertEquals(listOf(outputA1, outputA2, outputA3, outputA4).count { it.pubkeyScript == f.fundingParamsA.fundingPubkeyScript && it.amount == f.fundingParamsA.fundingAmount }, 1)

        assertEquals(sharedTxA.sharedTx.totalAmountIn, 250_000.sat)
        assertNotNull(sharedTxA.sharedTx.sharedInput)
        assertEquals(sharedTxA.sharedTx.localFees, 1000.sat)
        assertEquals(sharedTxA.sharedTx.remoteFees, 500.sat)
        assertNotNull(sharedTxB.sharedTx.sharedInput)
        assertEquals(sharedTxB.sharedTx.localFees, 500.sat)
        assertEquals(sharedTxB.sharedTx.remoteFees, 1000.sat)

        // Bob sends signatures first as he did not contribute.
        val signedTxB = sharedTxB.sharedTx.sign(f.keyManagerB, f.fundingParamsB, f.localParamsB)
        assertNotNull(signedTxB)
        assertTrue(signedTxB.localSigs.witnesses.isEmpty())
        assertNotNull(signedTxB.localSigs.previousFundingTxSig)

        // The resulting transaction is valid.
        val signedTxA = sharedTxA.sharedTx.sign(f.keyManagerA, f.fundingParamsA, f.localParamsA)?.addRemoteSigs(f.fundingParamsA, signedTxB.localSigs)
        assertNotNull(signedTxA)
        assertTrue(signedTxA.localSigs.witnesses.isEmpty())
        assertNotNull(signedTxA.localSigs.previousFundingTxSig)
        val signedTx = signedTxA.signedTx
        assertEquals(signedTx.txIn.size, 1)
        assertEquals(signedTx.txOut.size, 6)
        assertTrue(signedTx.txOut.containsAll(spliceOutputsA))
        assertTrue(signedTx.txOut.containsAll(spliceOutputsB))
        Transaction.correctlySpends(signedTx, previousOutputs(f.fundingParamsA, sharedTxA.sharedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    @Test
    fun `initiator and non-initiator combine splice-in and splice-out`() {
        val targetFeerate = FeeratePerKw(1000.sat)
        val balanceA = 150_000.sat
        val fundingA = 175_000.sat
        val spliceOutputsA = listOf(TxOut(30_000.sat, Script.pay2wpkh(randomKey().publicKey())))
        val utxosA = listOf(75_000.sat)
        val balanceB = 100_000.sat
        val fundingB = 115_000.sat
        val spliceOutputsB = listOf(TxOut(10_000.sat, Script.pay2wpkh(randomKey().publicKey())))
        val utxosB = listOf(50_000.sat)
        val f = createSpliceFixture(balanceA, fundingA, utxosA, spliceOutputsA, balanceB, fundingB, utxosB, spliceOutputsB, targetFeerate, 330.sat, 0)
        assertEquals(f.fundingParamsA.fundingAmount, 290_000.sat)
        assertNotNull(f.fundingParamsA.sharedInput)
        assertNotNull(f.fundingParamsB.sharedInput)

        val alice0 = InteractiveTxSession(f.fundingParamsA, balanceA, balanceB, f.fundingContributionsA)
        val bob0 = InteractiveTxSession(f.fundingParamsB, balanceB, balanceA, f.fundingContributionsB)
        // Alice --- tx_add_input --> Bob
        val (alice1, inputA1) = sendMessage<TxAddInput>(alice0)
        // Alice <-- tx_add_input --- Bob
        val (bob1, inputB) = receiveMessage<TxAddInput>(bob0, inputA1)
        // Alice --- tx_add_input --> Bob
        val (alice2, inputA2) = receiveMessage<TxAddInput>(alice1, inputB)
        // Alice <-- tx_add_output --- Bob
        val (bob2, outputB1) = receiveMessage<TxAddOutput>(bob1, inputA2)
        // Alice --- tx_add_output --> Bob
        val (alice3, outputA1) = receiveMessage<TxAddOutput>(alice2, outputB1)
        // Alice <-- tx_add_output --- Bob
        val (bob3, outputB2) = receiveMessage<TxAddOutput>(bob2, outputA1)
        // Alice --- tx_add_output --> Bob
        val (alice4, outputA2) = receiveMessage<TxAddOutput>(alice3, outputB2)
        // Alice <-- tx_complete --- Bob
        val (bob4, txCompleteB) = receiveMessage<TxComplete>(bob3, outputA2)
        // Alice --- tx_add_output --> Bob
        val (alice5, outputA3) = receiveMessage<TxAddOutput>(alice4, txCompleteB)
        // Alice <-- tx_complete --- Bob
        val (bob5, _) = receiveMessage<TxComplete>(bob4, outputA3)
        // Alice --- tx_complete --> Bob
        val sharedTxA = receiveFinalMessage(alice5, txCompleteB)
        assertNotNull(sharedTxA.txComplete)
        val sharedTxB = receiveFinalMessage(bob5, sharedTxA.txComplete!!)
        assertNull(sharedTxB.txComplete)

        // Alice is responsible for adding the shared input and the shared output.
        assertEquals(listOf(inputA1, inputA2).count { it.sharedInput == f.fundingParamsA.sharedInput?.info?.outPoint }, 1)
        assertEquals(listOf(outputA1, outputA2, outputA3).count { it.pubkeyScript == f.fundingParamsA.fundingPubkeyScript && it.amount == f.fundingParamsA.fundingAmount }, 1)

        assertEquals(sharedTxA.sharedTx.totalAmountIn, 375_000.sat)
        assertNotNull(sharedTxA.sharedTx.sharedInput)
        assertEquals(sharedTxA.sharedTx.localFees, 1122.sat)
        assertEquals(sharedTxA.sharedTx.remoteFees, 522.sat)
        assertNotNull(sharedTxB.sharedTx.sharedInput)
        assertEquals(sharedTxB.sharedTx.localFees, 522.sat)
        assertEquals(sharedTxB.sharedTx.remoteFees, 1122.sat)

        // Bob sends signatures first as he did not contribute.
        val signedTxB = sharedTxB.sharedTx.sign(f.keyManagerB, f.fundingParamsB, f.localParamsB)
        assertNotNull(signedTxB)
        assertEquals(signedTxB.localSigs.witnesses.size, 1)
        assertNotNull(signedTxB.localSigs.previousFundingTxSig)

        // The resulting transaction is valid.
        val signedTxA = sharedTxA.sharedTx.sign(f.keyManagerA, f.fundingParamsA, f.localParamsA)?.addRemoteSigs(f.fundingParamsA, signedTxB.localSigs)
        assertNotNull(signedTxA)
        assertEquals(signedTxA.localSigs.witnesses.size, 1)
        assertNotNull(signedTxA.localSigs.previousFundingTxSig)
        val signedTx = signedTxA.signedTx
        assertEquals(signedTx.txIn.size, 3)
        assertEquals(signedTx.txOut.size, 5)
        assertTrue(signedTx.txOut.containsAll(spliceOutputsA))
        assertTrue(signedTx.txOut.containsAll(spliceOutputsB))
        Transaction.correctlySpends(signedTx, previousOutputs(f.fundingParamsA, sharedTxA.sharedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        val feerate = Transactions.fee2rate(signedTxA.tx.fees, signedTx.weight())
        assertTrue(targetFeerate <= feerate && feerate <= targetFeerate * 1.25, "unexpected feerate (target=$targetFeerate actual=$feerate)")
    }

    @Test
    fun `remove input - output`() {
        val f = createFixture(100_000.sat, listOf(150_000.sat), 0.sat, listOf(), FeeratePerKw(2500.sat), 330.sat, 0)
        assertEquals(f.fundingParamsA.fundingAmount, 100_000.sat)

        // In this flow we introduce dummy inputs/outputs from Bob to Alice that are then removed.
        val alice0 = InteractiveTxSession(f.fundingParamsA, 0.sat, 0.sat, f.fundingContributionsA)
        // Alice --- tx_add_input --> Bob
        val (alice1, inputA) = sendMessage<TxAddInput>(alice0)
        // Alice <-- tx_add_input --- Bob
        val inputB = TxAddInput(f.channelId, 1, Transaction(2, listOf(), listOf(TxOut(250_000.sat, Script.pay2wpkh(randomKey().publicKey()))), 0), 0, 0u)
        // Alice --- tx_add_output --> Bob
        val (alice2, _) = receiveMessage<TxAddOutput>(alice1, inputB)
        // Alice <-- tx_add_output --- Bob
        val outputB = TxAddOutput(f.channelId, 3, 250_000.sat, Script.write(Script.pay2wpkh(randomKey().publicKey())).byteVector())
        // Alice --- tx_add_output --> Bob
        val (alice3, _) = receiveMessage<TxAddOutput>(alice2, outputB)
        // Alice <-- tx_remove_input --- Bob
        val removeInputB = TxRemoveInput(f.channelId, 1)
        // Alice --- tx_complete --> Bob
        val (alice4, _) = receiveMessage<TxComplete>(alice3, removeInputB)
        // Alice <-- tx_remove_output --- Bob
        val remoteOutputB = TxRemoveOutput(f.channelId, 3)
        // Alice --- tx_complete --> Bob
        val (alice5, _) = receiveMessage<TxComplete>(alice4, remoteOutputB)
        // Alice <-- tx_complete --- Bob
        val sharedTxA = receiveFinalMessage(alice5, TxComplete(f.channelId))
        assertNull(sharedTxA.txComplete)
        assertEquals(sharedTxA.sharedTx.remoteAmountIn, 0.sat)

        // The resulting transaction doesn't contain Bob's removed inputs and outputs.
        val tx = sharedTxA.sharedTx.buildUnsignedTx()
        assertEquals(tx.lockTime, 0)
        assertEquals(tx.txIn.size, 1)
        assertEquals(tx.txIn.first().outPoint, OutPoint(inputA.previousTx!!, inputA.previousTxOutput))
        assertEquals(tx.txOut.size, 2)
    }

    @Test
    fun `cannot contribute unusable or invalid inputs`() {
        val privKey = randomKey()
        val pubKey = privKey.publicKey()
        val fundingScript = Script.write(Script.pay2wsh(Script.write(Script.createMultiSigMofN(2, listOf(randomKey().publicKey(), randomKey().publicKey()))))).byteVector()
        val fundingParams = InteractiveTxParams(randomBytes32(), true, 150_000.sat, 50_000.sat, fundingScript, 0, 660.sat, FeeratePerKw(2500.sat))
        run {
            val previousTx = Transaction(2, listOf(), listOf(TxOut(650.sat, Script.pay2wpkh(pubKey))), 0)
            val result = FundingContributions.create(fundingParams, listOf(WalletState.Utxo(previousTx, 0, 0))).left
            assertNotNull(result)
            assertIs<FundingContributionFailure.InputBelowDust>(result)
        }
        run {
            val previousTx = Transaction(2, listOf(), listOf(TxOut(10_000.sat, Script.pay2wpkh(pubKey)), TxOut(10_000.sat, Script.pay2pkh(randomKey().publicKey()))), 0)
            val result = FundingContributions.create(fundingParams, listOf(WalletState.Utxo(previousTx, 1, 0))).left
            assertNotNull(result)
            assertIs<FundingContributionFailure.NonPay2wpkhInput>(result)
        }
        run {
            val txIn = (1..400).map { TxIn(OutPoint(randomBytes32(), 3), ByteVector.empty, 0, Script.witnessPay2wpkh(pubKey, Transactions.PlaceHolderSig)) }
            val txOut = (1..400).map { i -> TxOut(1000.sat * i, Script.pay2wpkh(pubKey)) }
            val previousTx = Transaction(2, txIn, txOut, 0)
            val result = FundingContributions.create(fundingParams, listOf(WalletState.Utxo(previousTx, 53, 0))).left
            assertNotNull(result)
            assertIs<FundingContributionFailure.InputTxTooLarge>(result)
        }
        run {
            val previousTx = Transaction(2, listOf(), listOf(TxOut(80_000.sat, Script.pay2wpkh(pubKey)), TxOut(60_000.sat, Script.pay2wpkh(pubKey))), 0)
            val result = FundingContributions.create(fundingParams, listOf(WalletState.Utxo(previousTx, 0, 0), WalletState.Utxo(previousTx, 1, 0))).left
            assertNotNull(result)
            assertIs<FundingContributionFailure.NotEnoughFunding>(result)
        }
        run {
            val previousTx = Transaction(2, listOf(), listOf(TxOut(80_000.sat, Script.pay2wpkh(pubKey)), TxOut(70_001.sat, Script.pay2wpkh(pubKey))), 0)
            val result = FundingContributions.create(fundingParams, listOf(WalletState.Utxo(previousTx, 0, 0), WalletState.Utxo(previousTx, 1, 0))).left
            assertNotNull(result)
            assertIs<FundingContributionFailure.NotEnoughFees>(result)
        }
    }

    @Test
    fun `invalid input`() {
        // Create a transaction with a mix of segwit and non-segwit inputs.
        val previousOutputs = listOf(
            TxOut(2500.sat, Script.pay2wpkh(randomKey().publicKey())),
            TxOut(2500.sat, Script.pay2pkh(randomKey().publicKey())),
            TxOut(2500.sat, Script.pay2wpkh(randomKey().publicKey())),
        )
        val previousTx = Transaction(2, listOf(), previousOutputs, 0)
        val f = createFixture(100_000.sat, listOf(120_000.sat), 0.sat, listOf(), FeeratePerKw(5000.sat), 330.sat, 0)
        val testCases = mapOf(
            TxAddInput(f.channelId, 0, previousTx, 0, 0U) to InteractiveTxSessionAction.InvalidSerialId(f.channelId, 0),
            TxAddInput(f.channelId, 1, previousTx, 0, 0U) to InteractiveTxSessionAction.DuplicateSerialId(f.channelId, 1),
            TxAddInput(f.channelId, 3, previousTx, 0, 0U) to InteractiveTxSessionAction.DuplicateInput(f.channelId, 3, previousTx.txid, 0),
            TxAddInput(f.channelId, 5, previousTx, 3, 0U) to InteractiveTxSessionAction.InputOutOfBounds(f.channelId, 5, previousTx.txid, 3),
            TxAddInput(f.channelId, 7, previousTx, 1, 0U) to InteractiveTxSessionAction.NonSegwitInput(f.channelId, 7, previousTx.txid, 1),
            TxAddInput(f.channelId, 9, previousTx, 2, 0xfffffffeU) to InteractiveTxSessionAction.NonReplaceableInput(f.channelId, 9, previousTx.txid, 2, 0xfffffffe),
            TxAddInput(f.channelId, 9, previousTx, 2, 0xffffffffU) to InteractiveTxSessionAction.NonReplaceableInput(f.channelId, 9, previousTx.txid, 2, 0xffffffff),
        )
        testCases.forEach { (input, expected) ->
            val alice0 = InteractiveTxSession(f.fundingParamsA, 0.sat, 0.sat, f.fundingContributionsA)
            // Alice --- tx_add_input --> Bob
            val (alice1, _) = sendMessage<TxAddInput>(alice0)
            // Alice <-- tx_add_input --- Bob
            val (alice2, _) = receiveMessage<TxAddOutput>(alice1, TxAddInput(f.channelId, 1, previousTx, 0, 0u))
            // Alice <-- tx_add_input --- Bob
            val failure = receiveInvalidMessage(alice2, input)
            assertEquals(failure, expected)
        }
    }

    @Test
    fun `allow all output types`() {
        val f = createFixture(100_000.sat, listOf(120_000.sat), 0.sat, listOf(), FeeratePerKw(5000.sat), 330.sat, 0)
        val testCases = listOf(
            TxAddOutput(f.channelId, 1, 25_000.sat, Script.write(Script.pay2pkh(randomKey().publicKey())).byteVector()),
            TxAddOutput(f.channelId, 1, 25_000.sat, Script.write(Script.pay2sh(listOf(OP_1))).byteVector()),
            TxAddOutput(f.channelId, 1, 25_000.sat, Script.write(listOf(OP_1)).byteVector()),
        )
        testCases.forEach { output ->
            val alice0 = InteractiveTxSession(f.fundingParamsA, 0.sat, 0.sat, f.fundingContributionsA)
            // Alice --- tx_add_input --> Bob
            val (alice1, _) = sendMessage<TxAddInput>(alice0)
            // Alice <-- tx_add_output --- Bob
            val (alice2, _) = receiveMessage<TxAddOutput>(alice1, output)
            assertEquals(alice2.outputsReceivedCount, 1)
            assertFalse(alice2.isComplete)
        }
    }

    @Test
    fun `invalid output`() {
        val f = createFixture(100_000.sat, listOf(120_000.sat), 0.sat, listOf(), FeeratePerKw(5000.sat), 330.sat, 0)
        val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey())).byteVector()
        val testCases = mapOf(
            TxAddOutput(f.channelId, 0, 25_000.sat, validScript) to InteractiveTxSessionAction.InvalidSerialId(f.channelId, 0),
            TxAddOutput(f.channelId, 1, 45_000.sat, validScript) to InteractiveTxSessionAction.DuplicateSerialId(f.channelId, 1),
            TxAddOutput(f.channelId, 3, 329.sat, validScript) to InteractiveTxSessionAction.OutputBelowDust(f.channelId, 3, 329.sat, 330.sat),
        )
        testCases.forEach { (output, expected) ->
            val alice0 = InteractiveTxSession(f.fundingParamsA, 0.sat, 0.sat, f.fundingContributionsA)
            // Alice --- tx_add_input --> Bob
            val (alice1, _) = sendMessage<TxAddInput>(alice0)
            // Alice <-- tx_add_output --- Bob
            val (alice2, _) = receiveMessage<TxAddOutput>(alice1, TxAddOutput(f.channelId, 1, 50_000.sat, validScript))
            // Alice <-- tx_add_output --- Bob
            val failure = receiveInvalidMessage(alice2, output)
            assertEquals(failure, expected)
        }
    }

    @Test
    fun `remove unknown input - output`() {
        val f = createFixture(100_000.sat, listOf(120_000.sat), 0.sat, listOf(), FeeratePerKw(5000.sat), 330.sat, 0)
        val testCases = mapOf(
            TxRemoveOutput(f.channelId, 52) to InteractiveTxSessionAction.InvalidSerialId(f.channelId, 52),
            TxRemoveOutput(f.channelId, 53) to InteractiveTxSessionAction.UnknownSerialId(f.channelId, 53),
            TxRemoveInput(f.channelId, 56) to InteractiveTxSessionAction.InvalidSerialId(f.channelId, 56),
            TxRemoveInput(f.channelId, 57) to InteractiveTxSessionAction.UnknownSerialId(f.channelId, 57),
        )
        testCases.forEach { (msg, expected) ->
            val alice0 = InteractiveTxSession(f.fundingParamsA, 0.sat, 0.sat, f.fundingContributionsA)
            // Alice --- tx_add_input --> Bob
            val (alice1, _) = sendMessage<TxAddInput>(alice0)
            // Alice <-- tx_remove_(in|out)put --- Bob
            val failure = receiveInvalidMessage(alice1, msg)
            assertEquals(failure, expected)
        }
    }

    @Test
    fun `too many protocol rounds`() {
        val f = createFixture(100_000.sat, listOf(120_000.sat), 0.sat, listOf(), FeeratePerKw(5000.sat), 330.sat, 0)
        val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey())).byteVector()
        var (alice, _) = InteractiveTxSession(f.fundingParamsA, 0.sat, 0.sat, f.fundingContributionsA).send()
        (1..InteractiveTxSession.MAX_INPUTS_OUTPUTS_RECEIVED).forEach { i ->
            // Alice --- tx_message --> Bob
            val (alice1, _) = alice.receive(TxAddOutput(f.channelId, 2 * i.toLong() + 1, 2500.sat, validScript))
            alice = alice1
        }
        val failure = receiveInvalidMessage(alice, TxAddOutput(f.channelId, 15001, 2500.sat, validScript))
        assertEquals(failure, InteractiveTxSessionAction.TooManyInteractiveTxRounds(f.channelId))
    }

    @Test
    fun `too many inputs`() {
        val f = createFixture(100_000.sat, listOf(120_000.sat), 0.sat, listOf(), FeeratePerKw(5000.sat), 330.sat, 0)
        var (alice, _) = InteractiveTxSession(f.fundingParamsA, 0.sat, 0.sat, f.fundingContributionsA).send()
        (1..252).forEach { i ->
            // Alice --- tx_message --> Bob
            val (alice1, _) = alice.receive(createTxAddInput(f.channelId, 2 * i.toLong() + 1, 5000.sat))
            alice = alice1
        }
        // Alice <-- tx_complete --- Bob
        val failure = receiveInvalidMessage(alice, TxComplete(f.channelId))
        assertIs<InteractiveTxSessionAction.InvalidTxInputOutputCount>(failure)
        assertEquals(failure.inputCount, 253)
        assertEquals(failure.outputCount, 2)
    }

    @Test
    fun `too many outputs`() {
        val f = createFixture(100_000.sat, listOf(120_000.sat), 0.sat, listOf(), FeeratePerKw(5000.sat), 330.sat, 0)
        var (alice, _) = InteractiveTxSession(f.fundingParamsA, 0.sat, 0.sat, f.fundingContributionsA).send()
        val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey())).byteVector()
        (1..252).forEach { i ->
            // Alice --- tx_message --> Bob
            // Alice --- tx_message --> Bob
            val (alice1, _) = alice.receive(TxAddOutput(f.channelId, 2 * i.toLong() + 1, 2500.sat, validScript))
            alice = alice1
        }
        // Alice <-- tx_complete --- Bob
        val failure = receiveInvalidMessage(alice, TxComplete(f.channelId))
        assertIs<InteractiveTxSessionAction.InvalidTxInputOutputCount>(failure)
        assertEquals(failure.inputCount, 1)
        assertEquals(failure.outputCount, 254)
    }

    @Test
    fun `missing funding output`() {
        val f = createFixture(100_000.sat, listOf(120_000.sat), 0.sat, listOf(), FeeratePerKw(5000.sat), 330.sat, 0)
        val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey())).byteVector()
        val bob0 = InteractiveTxSession(f.fundingParamsB, 0.sat, 0.sat, f.fundingContributionsB)
        // Alice --- tx_add_input --> Bob
        val (bob1, _) = receiveMessage<TxComplete>(bob0, createTxAddInput(f.channelId, 0, 150_000.sat))
        // Alice --- tx_add_output --> Bob
        val (bob2, _) = receiveMessage<TxComplete>(bob1, TxAddOutput(f.channelId, 2, 125_000.sat, validScript))
        // Alice --- tx_complete --> Bob
        val failure = receiveInvalidMessage(bob2, TxComplete(f.channelId))
        assertIs<InteractiveTxSessionAction.InvalidTxSharedOutput>(failure)
    }

    @Test
    fun `multiple funding outputs`() {
        val f = createFixture(100_000.sat, listOf(120_000.sat), 0.sat, listOf(), FeeratePerKw(5000.sat), 330.sat, 0)
        val bob0 = InteractiveTxSession(f.fundingParamsB, 0.sat, 0.sat, f.fundingContributionsB)
        // Alice --- tx_add_input --> Bob
        val (bob1, _) = receiveMessage<TxComplete>(bob0, createTxAddInput(f.channelId, 0, 150_000.sat))
        // Alice --- tx_add_output --> Bob
        val (bob2, _) = receiveMessage<TxComplete>(bob1, TxAddOutput(f.channelId, 2, 100_000.sat, f.fundingParamsB.fundingPubkeyScript))
        // Alice --- tx_add_output --> Bob
        val (bob3, _) = receiveMessage<TxComplete>(bob2, TxAddOutput(f.channelId, 4, 100_000.sat, f.fundingParamsB.fundingPubkeyScript))
        // Alice --- tx_complete --> Bob
        val failure = receiveInvalidMessage(bob3, TxComplete(f.channelId))
        assertIs<InteractiveTxSessionAction.InvalidTxSharedOutput>(failure)
    }

    @Test
    fun `missing shared input`() {
        val balanceA = 100_000.sat
        val spliceOutputA = TxOut(20_000.sat, Script.pay2wpkh(randomKey().publicKey()))
        val fundingA = 75_000.sat
        val f = createSpliceFixture(balanceA, fundingA, listOf(), listOf(spliceOutputA), 0.sat, 0.sat, listOf(), listOf(), FeeratePerKw(5000.sat), 330.sat, 0)
        val bob0 = InteractiveTxSession(f.fundingParamsB, 0.sat, balanceA, f.fundingContributionsB)
        // Alice --- tx_add_output --> Bob
        val (bob1, _) = receiveMessage<TxComplete>(bob0, TxAddOutput(f.channelId, 0, f.fundingParamsB.fundingAmount, f.fundingParamsB.fundingPubkeyScript))
        // Alice --- tx_add_output --> Bob
        val (bob2, _) = receiveMessage<TxComplete>(bob1, TxAddOutput(f.channelId, 2, spliceOutputA.amount, spliceOutputA.publicKeyScript))
        // Alice --- tx_complete --> Bob
        val failure = receiveInvalidMessage(bob2, TxComplete(f.channelId))
        assertIs<InteractiveTxSessionAction.InvalidTxSharedInput>(failure)
    }

    @Test
    fun `invalid funding amount`() {
        val f = createFixture(100_000.sat, listOf(120_000.sat), 0.sat, listOf(), FeeratePerKw(5000.sat), 330.sat, 0)
        val bob0 = InteractiveTxSession(f.fundingParamsB, 0.sat, 0.sat, f.fundingContributionsB)
        // Alice --- tx_add_input --> Bob
        val (bob1, _) = receiveMessage<TxComplete>(bob0, createTxAddInput(f.channelId, 0, 150_000.sat))
        // Alice --- tx_add_output --> Bob
        val failure = receiveInvalidMessage(bob1, TxAddOutput(f.channelId, 2, 100_001.sat, f.fundingParamsB.fundingPubkeyScript))
        assertIs<InteractiveTxSessionAction.InvalidTxSharedAmount>(failure)
        assertEquals(failure.expected, 100_000.sat)
        assertEquals(failure.amount, 100_001.sat)
    }

    @Test
    fun `funding amount drops below reserve`() {
        val balanceA = 100_000.sat
        val spliceOutputsA = listOf(TxOut(90_000.sat, Script.pay2wpkh(randomKey().publicKey())))
        val fundingA = 5_000.sat
        val balanceB = 500_000.sat
        val f = createSpliceFixture(balanceA, fundingA, listOf(), spliceOutputsA, balanceB, balanceB, listOf(), listOf(), FeeratePerKw(1000.sat), 330.sat, 0)
        assertEquals(f.fundingParamsA.fundingAmount, 505_000.sat)
        assertNotNull(f.fundingParamsA.sharedInput)
        assertNotNull(f.fundingParamsB.sharedInput)

        val alice0 = InteractiveTxSession(f.fundingParamsA, balanceA, balanceB, f.fundingContributionsA)
        val bob0 = InteractiveTxSession(f.fundingParamsB, balanceB, balanceA, f.fundingContributionsB)
        // Alice --- tx_add_input --> Bob
        val (alice1, inputA) = sendMessage<TxAddInput>(alice0)
        // Alice <-- tx_complete --- Bob
        val (bob1, txCompleteB) = receiveMessage<TxComplete>(bob0, inputA)
        // Alice --- tx_add_output --> Bob
        val (alice2, outputA1) = receiveMessage<TxAddOutput>(alice1, txCompleteB)
        // Alice <-- tx_complete --- Bob
        val (bob2, _) = receiveMessage<TxComplete>(bob1, outputA1)
        // Alice --- tx_add_output --> Bob
        val (_, outputA2) = receiveMessage<TxAddOutput>(alice2, txCompleteB)
        // Alice <-- tx_complete --- Bob
        val (bob3, _) = receiveMessage<TxComplete>(bob2, outputA2)
        // Alice --- tx_complete --> Bob
        val failure = receiveInvalidMessage(bob3, TxComplete(f.channelId))
        assertIs<InteractiveTxSessionAction.InvalidTxBelowReserve>(failure)
    }

    @Test
    fun `missing previous tx`() {
        val f = createFixture(100_000.sat, listOf(120_000.sat), 0.sat, listOf(), FeeratePerKw(5000.sat), 330.sat, 0)
        val bob0 = InteractiveTxSession(f.fundingParamsB, 0.sat, 0.sat, f.fundingContributionsB)
        // Alice --- tx_add_output --> Bob
        val failure = receiveInvalidMessage(bob0, TxAddInput(f.channelId, 0, null, 3, 0u))
        assertIs<InteractiveTxSessionAction.PreviousTxMissing>(failure)
    }

    @Test
    fun `total input amount too low`() {
        val f = createFixture(100_000.sat, listOf(120_000.sat), 0.sat, listOf(), FeeratePerKw(5000.sat), 330.sat, 0)
        val bob0 = InteractiveTxSession(f.fundingParamsB, 0.sat, 0.sat, f.fundingContributionsB)
        val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey())).byteVector()
        // Alice --- tx_add_input --> Bob
        val (bob1, _) = receiveMessage<TxComplete>(bob0, createTxAddInput(f.channelId, 0, 150_000.sat))
        // Alice --- tx_add_output --> Bob
        val (bob2, _) = receiveMessage<TxComplete>(bob1, TxAddOutput(f.channelId, 2, 100_000.sat, f.fundingParamsB.fundingPubkeyScript))
        // Alice --- tx_add_output --> Bob
        val (bob3, _) = receiveMessage<TxComplete>(bob2, TxAddOutput(f.channelId, 4, 51_000.sat, validScript))
        // Alice --- tx_complete --> Bob
        val failure = receiveInvalidMessage(bob3, TxComplete(f.channelId))
        assertIs<InteractiveTxSessionAction.InvalidTxChangeAmount>(failure)
    }

    @Test
    fun `minimum fee not met`() {
        val f = createFixture(100_000.sat, listOf(120_000.sat), 0.sat, listOf(), FeeratePerKw(5000.sat), 330.sat, 0)
        val bob0 = InteractiveTxSession(f.fundingParamsB, 0.sat, 0.sat, f.fundingContributionsB)
        val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey())).byteVector()
        // Alice --- tx_add_input --> Bob
        val (bob1, _) = receiveMessage<TxComplete>(bob0, createTxAddInput(f.channelId, 0, 150_000.sat))
        // Alice --- tx_add_output --> Bob
        val (bob2, _) = receiveMessage<TxComplete>(bob1, TxAddOutput(f.channelId, 2, 100_000.sat, f.fundingParamsB.fundingPubkeyScript))
        // Alice --- tx_add_output --> Bob
        val (bob3, _) = receiveMessage<TxComplete>(bob2, TxAddOutput(f.channelId, 4, 49_999.sat, validScript))
        // Alice --- tx_complete --> Bob
        val failure = receiveInvalidMessage(bob3, TxComplete(f.channelId))
        assertIs<InteractiveTxSessionAction.InvalidTxFeerate>(failure)
        assertEquals(failure.targetFeerate, FeeratePerKw(5000.sat))
    }

    @Test
    fun `previous attempts not double-spent`() {
        val f = createFixture(100_000.sat, listOf(120_000.sat), 0.sat, listOf(), FeeratePerKw(5000.sat), 330.sat, 0)
        val sharedOutput = InteractiveTxOutput.Shared(0, f.fundingParamsA.fundingPubkeyScript, f.fundingParamsA.localAmount, f.fundingParamsA.remoteAmount)
        val previousTx1 = Transaction(2, listOf(), listOf(TxOut(150_000.sat, Script.pay2wpkh(randomKey().publicKey()))), 0)
        val previousTx2 = Transaction(2, listOf(), listOf(TxOut(160_000.sat, Script.pay2wpkh(randomKey().publicKey())), TxOut(175_000.sat, Script.pay2wpkh(randomKey().publicKey()))), 0)
        val validScript = Script.write(Script.pay2wpkh(randomKey().publicKey())).byteVector()
        val firstAttempt = FullySignedSharedTransaction(
            SharedTransaction(null, sharedOutput, listOf(), listOf(InteractiveTxInput.Remote(2, OutPoint(previousTx1, 0), TxOut(125_000.sat, validScript), 0u)), listOf(), listOf(), 0),
            TxSignatures(f.channelId, randomBytes32(), listOf()),
            TxSignatures(f.channelId, randomBytes32(), listOf(Script.witnessPay2wpkh(randomKey().publicKey(), ByteVector64.Zeroes))),
            sharedSigs = null
        )
        val secondAttempt = PartiallySignedSharedTransaction(
            SharedTransaction(null, sharedOutput, listOf(), firstAttempt.tx.remoteInputs + listOf(InteractiveTxInput.Remote(4, OutPoint(previousTx2, 1), TxOut(150_000.sat, validScript), 0u)), listOf(), listOf(), 0),
            TxSignatures(f.channelId, randomBytes32(), listOf()),
        )
        val bob0 = InteractiveTxSession(f.fundingParamsB, 0.sat, 0.sat, f.fundingContributionsB, listOf(firstAttempt, secondAttempt))
        // Alice --- tx_add_input --> Bob
        val (bob1, _) = receiveMessage<TxComplete>(bob0, TxAddInput(f.channelId, 4, previousTx2, 1, 0u))
        // Alice --- tx_add_output --> Bob
        val (bob2, _) = receiveMessage<TxComplete>(bob1, TxAddOutput(f.channelId, 6, f.fundingParamsB.fundingAmount, f.fundingParamsB.fundingPubkeyScript))
        // Alice --- tx_add_output --> Bob
        val (bob3, _) = receiveMessage<TxComplete>(bob2, TxAddOutput(f.channelId, 8, 25_000.sat, validScript))
        // Alice --- tx_complete --> Bob
        val failure = receiveInvalidMessage(bob3, TxComplete(f.channelId))
        assertIs<InteractiveTxSessionAction.InvalidTxDoesNotDoubleSpendPreviousTx>(failure)
    }

    @Test
    fun `reference test vector`() {
        val channelId = ByteVector32.Zeroes
        val parentTx = Transaction.read(
            "02000000000101f86fd1d0db3ac5a72df968622f31e6b5e6566a09e29206d7c7a55df90e181de800000000171600141fb9623ffd0d422eacc450fd1e967efc477b83ccffffffff0580b2e60e00000000220020fd89acf65485df89797d9ba7ba7a33624ac4452f00db08107f34257d33e5b94680b2e60e0000000017a9146a235d064786b49e7043e4a042d4cc429f7eb6948780b2e60e00000000160014fbb4db9d85fba5e301f4399e3038928e44e37d3280b2e60e0000000017a9147ecd1b519326bc13b0ec716e469b58ed02b112a087f0006bee0000000017a914f856a70093da3a5b5c4302ade033d4c2171705d387024730440220696f6cee2929f1feb3fd6adf024ca0f9aa2f4920ed6d35fb9ec5b78c8408475302201641afae11242160101c6f9932aeb4fcd1f13a9c6df5d1386def000ea259a35001210381d7d5b1bc0d7600565d827242576d9cb793bfe0754334af82289ee8b65d137600000000"
        )
        val sharedOutput = InteractiveTxOutput.Shared(44, ByteVector("0020297b92c238163e820b82486084634b4846b86a3c658d87b9384192e6bea98ec5"), 200_000_000.sat, 200_000_000.sat)

        val initiatorTx = run {
            val initiatorInput = InteractiveTxInput.Local(20, parentTx, 0, 4294967293u)
            val initiatorOutput = InteractiveTxOutput.Local.Change(30, 49999845.sat, ByteVector("00141ca1cca8855bad6bc1ea5436edd8cff10b7e448b"))
            val nonInitiatorInput = InteractiveTxInput.Remote(11, OutPoint(parentTx, 2), parentTx.txOut[2], 4294967293u)
            val nonInitiatorOutput = InteractiveTxOutput.Remote(33, 49999900.sat, ByteVector("001444cb0c39f93ecc372b5851725bd29d865d333b10"))
            SharedTransaction(null, sharedOutput, listOf(initiatorInput), listOf(nonInitiatorInput), listOf(initiatorOutput), listOf(nonInitiatorOutput), 120)
        }
        assertEquals(initiatorTx.localFees, 155.sat)
        assertEquals(initiatorTx.remoteFees, 100.sat)

        val nonInitiatorTx = run {
            val initiatorInput = InteractiveTxInput.Remote(20, OutPoint(parentTx, 0), parentTx.txOut[0], 4294967293u)
            val initiatorOutput = InteractiveTxOutput.Remote(30, 49999845.sat, ByteVector("00141ca1cca8855bad6bc1ea5436edd8cff10b7e448b"))
            val nonInitiatorInput = InteractiveTxInput.Local(11, parentTx, 2, 4294967293u)
            val nonInitiatorOutput = InteractiveTxOutput.Local.Change(33, 49999900.sat, ByteVector("001444cb0c39f93ecc372b5851725bd29d865d333b10"))
            SharedTransaction(null, sharedOutput, listOf(nonInitiatorInput), listOf(initiatorInput), listOf(nonInitiatorOutput), listOf(initiatorOutput), 120)
        }
        assertEquals(nonInitiatorTx.localFees, 100.sat)
        assertEquals(nonInitiatorTx.remoteFees, 155.sat)

        val unsignedTx = Transaction.read(
            "0200000002b932b0669cd0394d0d5bcc27e01ab8c511f1662a6799925b346c0cf18fca03430200000000fdffffffb932b0669cd0394d0d5bcc27e01ab8c511f1662a6799925b346c0cf18fca03430000000000fdffffff03e5effa02000000001600141ca1cca8855bad6bc1ea5436edd8cff10b7e448b1cf0fa020000000016001444cb0c39f93ecc372b5851725bd29d865d333b100084d71700000000220020297b92c238163e820b82486084634b4846b86a3c658d87b9384192e6bea98ec578000000"
        )
        assertEquals(initiatorTx.buildUnsignedTx().txid, unsignedTx.txid)
        assertEquals(nonInitiatorTx.buildUnsignedTx().txid, unsignedTx.txid)

        val initiatorWitness = ScriptWitness(
            listOf(
                ByteVector("68656c6c6f2074686572652c2074686973206973206120626974636f6e212121"),
                ByteVector("82012088a820add57dfe5277079d069ca4ad4893c96de91f88ffb981fdc6a2a34d5336c66aff87")
            )
        )
        val initiatorSigs = TxSignatures(channelId, unsignedTx, listOf(initiatorWitness), null)
        val nonInitiatorWitness = ScriptWitness(
            listOf(
                ByteVector("304402207de9ba56bb9f641372e805782575ee840a899e61021c8b1572b3ec1d5b5950e9022069e9ba998915dae193d3c25cb89b5e64370e6a3a7755e7f31cf6d7cbc2a49f6d01"),
                ByteVector("034695f5b7864c580bf11f9f8cb1a94eb336f2ce9ef872d2ae1a90ee276c772484")
            )
        )
        val nonInitiatorSigs = TxSignatures(channelId, unsignedTx, listOf(nonInitiatorWitness), null)
        val initiatorSignedTx = FullySignedSharedTransaction(initiatorTx, initiatorSigs, nonInitiatorSigs, null)
        assertEquals(initiatorSignedTx.feerate, FeeratePerKw(262.sat))
        val nonInitiatorSignedTx = FullySignedSharedTransaction(nonInitiatorTx, nonInitiatorSigs, initiatorSigs, null)
        assertEquals(nonInitiatorSignedTx.feerate, FeeratePerKw(262.sat))
        val signedTx = Transaction.read(
            "02000000000102b932b0669cd0394d0d5bcc27e01ab8c511f1662a6799925b346c0cf18fca03430200000000fdffffffb932b0669cd0394d0d5bcc27e01ab8c511f1662a6799925b346c0cf18fca03430000000000fdffffff03e5effa02000000001600141ca1cca8855bad6bc1ea5436edd8cff10b7e448b1cf0fa020000000016001444cb0c39f93ecc372b5851725bd29d865d333b100084d71700000000220020297b92c238163e820b82486084634b4846b86a3c658d87b9384192e6bea98ec50247304402207de9ba56bb9f641372e805782575ee840a899e61021c8b1572b3ec1d5b5950e9022069e9ba998915dae193d3c25cb89b5e64370e6a3a7755e7f31cf6d7cbc2a49f6d0121034695f5b7864c580bf11f9f8cb1a94eb336f2ce9ef872d2ae1a90ee276c772484022068656c6c6f2074686572652c2074686973206973206120626974636f6e2121212782012088a820add57dfe5277079d069ca4ad4893c96de91f88ffb981fdc6a2a34d5336c66aff8778000000"
        )
        assertEquals(initiatorSignedTx.signedTx, signedTx)
        assertEquals(initiatorSignedTx.signedTx, nonInitiatorSignedTx.signedTx)
    }

    companion object {
        data class Fixture(
            val channelId: ByteVector32,
            val keyManagerA: KeyManager,
            val localParamsA: LocalParams,
            val fundingParamsA: InteractiveTxParams,
            val fundingContributionsA: FundingContributions,
            val keyManagerB: KeyManager,
            val localParamsB: LocalParams,
            val fundingParamsB: InteractiveTxParams,
            val fundingContributionsB: FundingContributions
        )

        private fun createFixture(
            fundingAmountA: Satoshi,
            utxosA: List<Satoshi>,
            fundingAmountB: Satoshi,
            utxosB: List<Satoshi>,
            targetFeerate: FeeratePerKw,
            dustLimit: Satoshi,
            lockTime: Long
        ): Fixture {
            val channelId = randomBytes32()
            val localParamsA = TestConstants.Alice.channelParams()
            val localParamsB = TestConstants.Bob.channelParams()
            val channelKeysA = localParamsA.channelKeys(TestConstants.Alice.keyManager)
            val channelKeysB = localParamsB.channelKeys(TestConstants.Bob.keyManager)
            val fundingScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(channelKeysA.fundingPubKey, channelKeysB.fundingPubKey))).byteVector()
            val fundingParamsA = InteractiveTxParams(channelId, true, fundingAmountA, fundingAmountB, fundingScript, lockTime, dustLimit, targetFeerate)
            val fundingParamsB = InteractiveTxParams(channelId, false, fundingAmountB, fundingAmountA, fundingScript, lockTime, dustLimit, targetFeerate)
            val walletA = createWallet(TestConstants.Alice.keyManager, utxosA)
            val contributionsA = FundingContributions.create(fundingParamsA, null, walletA.utxos, listOf(), randomKey().publicKey())
            assertNotNull(contributionsA.right)
            val walletB = createWallet(TestConstants.Bob.keyManager, utxosB)
            val contributionsB = FundingContributions.create(fundingParamsB, null, walletB.utxos, listOf(), randomKey().publicKey())
            assertNotNull(contributionsB.right)
            return Fixture(channelId, TestConstants.Alice.keyManager, localParamsA, fundingParamsA, contributionsA.right!!, TestConstants.Bob.keyManager, localParamsB, fundingParamsB, contributionsB.right!!)
        }

        private fun createSpliceFixture(
            balanceA: Satoshi,
            fundingAmountA: Satoshi,
            utxosA: List<Satoshi>,
            outputsA: List<TxOut>,
            balanceB: Satoshi,
            fundingAmountB: Satoshi,
            utxosB: List<Satoshi>,
            outputsB: List<TxOut>,
            targetFeerate: FeeratePerKw,
            dustLimit: Satoshi,
            lockTime: Long
        ): Fixture {
            val channelId = randomBytes32()
            val localParamsA = TestConstants.Alice.channelParams()
            val localParamsB = TestConstants.Bob.channelParams()
            val channelKeysA = localParamsA.channelKeys(TestConstants.Alice.keyManager)
            val channelKeysB = localParamsB.channelKeys(TestConstants.Bob.keyManager)
            val redeemScript = Scripts.multiSig2of2(channelKeysA.fundingPubKey, channelKeysB.fundingPubKey)
            val fundingScript = Script.write(Script.pay2wsh(redeemScript)).byteVector()
            val previousFundingTx = Transaction(2, listOf(TxIn(OutPoint(randomBytes32(), 0), 0)), listOf(TxOut(balanceA + balanceB, fundingScript)), 0)
            val inputInfo = Transactions.InputInfo(OutPoint(previousFundingTx, 0), previousFundingTx.txOut[0], redeemScript)
            val sharedInputA = SharedFundingInput.Multisig2of2(inputInfo, channelKeysA.fundingPubKey, channelKeysB.fundingPubKey)
            val sharedInputB = SharedFundingInput.Multisig2of2(inputInfo, channelKeysB.fundingPubKey, channelKeysA.fundingPubKey)
            val fundingParamsA = InteractiveTxParams(channelId, true, fundingAmountA, fundingAmountB, sharedInputA, fundingScript, outputsA, lockTime, dustLimit, targetFeerate)
            val fundingParamsB = InteractiveTxParams(channelId, false, fundingAmountB, fundingAmountA, sharedInputB, fundingScript, outputsB, lockTime, dustLimit, targetFeerate)
            val walletA = createWallet(TestConstants.Alice.keyManager, utxosA)
            val contributionsA = FundingContributions.create(fundingParamsA, Pair(sharedInputA, balanceA), walletA.utxos, outputsA, randomKey().publicKey())
            assertNotNull(contributionsA.right)
            val walletB = createWallet(TestConstants.Bob.keyManager, utxosB)
            val contributionsB = FundingContributions.create(fundingParamsB, Pair(sharedInputB, balanceB), walletB.utxos, outputsB, randomKey().publicKey())
            assertNotNull(contributionsB.right)
            return Fixture(channelId, TestConstants.Alice.keyManager, localParamsA, fundingParamsA, contributionsA.right!!, TestConstants.Bob.keyManager, localParamsB, fundingParamsB, contributionsB.right!!)
        }

        private inline fun <reified M : InteractiveTxConstructionMessage> sendMessage(sender: InteractiveTxSession): Pair<InteractiveTxSession, M> {
            val (sender1, action1) = sender.send()
            assertIs<InteractiveTxSessionAction.SendMessage>(action1)
            assertIs<M>(action1.msg)
            return Pair(sender1, action1.msg as M)
        }

        private inline fun <reified M : InteractiveTxConstructionMessage> receiveMessage(receiver: InteractiveTxSession, msg: InteractiveTxConstructionMessage): Pair<InteractiveTxSession, M> {
            val (receiver1, action1) = receiver.receive(msg)
            assertIs<InteractiveTxSessionAction.SendMessage>(action1)
            assertIs<M>(action1.msg)
            return Pair(receiver1, action1.msg as M)
        }

        private fun receiveFinalMessage(receiver: InteractiveTxSession, msg: TxComplete): InteractiveTxSessionAction.SignSharedTx {
            val (receiver1, action1) = receiver.receive(msg)
            assertIs<InteractiveTxSessionAction.SignSharedTx>(action1)
            assertTrue(receiver1.isComplete)
            return action1
        }

        private fun receiveInvalidMessage(receiver: InteractiveTxSession, msg: InteractiveTxConstructionMessage): InteractiveTxSessionAction.RemoteFailure {
            val (_, action1) = receiver.receive(msg)
            assertIs<InteractiveTxSessionAction.RemoteFailure>(action1)
            return action1
        }

        private fun createWallet(keyManager: KeyManager, amounts: List<Satoshi>): WalletState {
            val privateKey = keyManager.bip84PrivateKey(account = 1, addressIndex = 0)
            val address = keyManager.bip84Address(account = 1, addressIndex = 0)
            val utxos = amounts.map { amount ->
                val txIn = listOf(TxIn(OutPoint(randomBytes32(), 2), 0))
                val txOut = listOf(TxOut(amount, Script.pay2wpkh(privateKey.publicKey())), TxOut(150.sat, Script.pay2wpkh(randomKey().publicKey())))
                val parentTx = Transaction(2, txIn, txOut, 0)
                Pair(UnspentItem(parentTx.txid, 0, amount.toLong(), 0), parentTx)
            }
            return WalletState(mapOf(address to utxos.map { it.first }), utxos.associate { it.second.txid to it.second })
        }

        private fun createTxAddInput(channelId: ByteVector32, serialId: Long, amount: Satoshi): TxAddInput {
            val previousTx = Transaction(2, listOf(), listOf(TxOut(amount, Script.pay2wpkh(randomKey().publicKey()))), 0)
            return TxAddInput(channelId, serialId, previousTx, 0, 0u)
        }

        private fun previousOutputs(fundingParams: InteractiveTxParams, sharedTx: SharedTransaction): Map<OutPoint, TxOut> = buildMap {
            fundingParams.sharedInput?.let { put(it.info.outPoint, it.info.txOut) }
            sharedTx.localInputs.forEach { put(it.outPoint, it.previousTx.txOut[it.previousTxOutput.toInt()]) }
            sharedTx.remoteInputs.forEach { put(it.outPoint, it.txOut) }
        }
    }
}