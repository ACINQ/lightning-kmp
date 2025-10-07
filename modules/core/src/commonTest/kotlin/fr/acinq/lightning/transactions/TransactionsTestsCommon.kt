package fr.acinq.lightning.transactions

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Crypto.sha256
import fr.acinq.bitcoin.Script.pay2wpkh
import fr.acinq.bitcoin.Script.write
import fr.acinq.bitcoin.crypto.musig2.Musig2
import fr.acinq.bitcoin.utils.Either
import fr.acinq.bitcoin.utils.flatMap
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomBytes64
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.ChannelSpendSignature
import fr.acinq.lightning.crypto.LocalCommitmentKeys
import fr.acinq.lightning.crypto.RemoteCommitmentKeys
import fr.acinq.lightning.io.AddLiquidityForIncomingPayment
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.transactions.CommitmentOutput.OutHtlc
import fr.acinq.lightning.transactions.Transactions.commitTxFee
import fr.acinq.lightning.transactions.Transactions.decodeTxNumber
import fr.acinq.lightning.transactions.Transactions.encodeTxNumber
import fr.acinq.lightning.transactions.Transactions.swapInputWeight
import fr.acinq.lightning.transactions.Transactions.swapInputWeightLegacy
import fr.acinq.lightning.transactions.Transactions.weight2fee
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.UpdateAddHtlc
import kotlin.random.Random
import kotlin.test.*

class TransactionsTestsCommon : LightningTestSuite() {

    private val localFundingPriv = randomKey()
    private val remoteFundingPriv = randomKey()
    private val localRevocationPriv = randomKey()
    private val localPaymentPriv = randomKey()
    private val localPaymentBasePoint = randomKey().publicKey()
    private val localDelayedPaymentPriv = randomKey()
    private val remotePaymentPriv = randomKey()
    private val localHtlcPriv = randomKey()
    private val remoteHtlcPriv = randomKey()
    // Keys used by the local node to spend outputs of its local commitment.
    private val localKeys = LocalCommitmentKeys(
        ourDelayedPaymentKey = localDelayedPaymentPriv,
        theirPaymentPublicKey = remotePaymentPriv.publicKey(),
        ourPaymentBasePoint = localPaymentBasePoint,
        ourHtlcKey = localHtlcPriv,
        theirHtlcPublicKey = remoteHtlcPriv.publicKey(),
        revocationPublicKey = localRevocationPriv.publicKey(),
    )
    // Keys used by the remote node to spend outputs of our local commitment.
    private val remoteKeys = RemoteCommitmentKeys(
        ourPaymentKey = remotePaymentPriv,
        theirDelayedPaymentPublicKey = localDelayedPaymentPriv.publicKey(),
        ourPaymentBasePoint = localPaymentBasePoint,
        ourHtlcKey = remoteHtlcPriv,
        theirHtlcPublicKey = localHtlcPriv.publicKey(),
        revocationPublicKey = localRevocationPriv.publicKey(),
    )
    private val toLocalDelay = CltvExpiryDelta(144)
    private val localDustLimit = 546.sat
    private val feerate = FeeratePerKw(22_000.sat)

    @Test
    fun `encode and decode sequence and lockTime -- one example`() {
        val txNumber = 0x11F71FB268DL

        val (sequence, lockTime) = encodeTxNumber(txNumber)
        assertEquals(0x80011F71L, sequence)
        assertEquals(0x20FB268DL, lockTime)

        val txNumber1 = decodeTxNumber(sequence, lockTime)
        assertEquals(txNumber, txNumber1)
    }

    @Test
    fun `reconstruct txNumber from sequence and lockTime`() {
        repeat(1_000) {
            val txNumber = Random.nextLong() and 0xffffffffffffL
            val (sequence, lockTime) = encodeTxNumber(txNumber)
            val txNumber1 = decodeTxNumber(sequence, lockTime)
            assertEquals(txNumber, txNumber1)
        }
    }

    @Test
    fun `compute fees`() {
        // see BOLT #3 specs
        val htlcs = setOf(
            OutgoingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, 5000000.msat, ByteVector32.Zeroes, CltvExpiry(552), TestConstants.emptyOnionPacket)),
            OutgoingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, 1000000.msat, ByteVector32.Zeroes, CltvExpiry(553), TestConstants.emptyOnionPacket)),
            IncomingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, 7000000.msat, ByteVector32.Zeroes, CltvExpiry(550), TestConstants.emptyOnionPacket)),
            IncomingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, 800000.msat, ByteVector32.Zeroes, CltvExpiry(551), TestConstants.emptyOnionPacket))
        )
        val spec = CommitmentSpec(htlcs, feerate = FeeratePerKw(5_000.sat), toLocal = 0.msat, toRemote = 0.msat)
        val fee = commitTxFee(546.sat, spec, Transactions.CommitmentFormat.AnchorOutputs)
        assertEquals(8000.sat, fee)
    }

    private fun checkExpectedWeight(actual: Int, expected: Int, commitmentFormat: Transactions.CommitmentFormat) {
        when (commitmentFormat) {
            Transactions.CommitmentFormat.AnchorOutputs -> {
                // ECDSA signatures are der-encoded, which creates some variability in signature size compared to the baseline.
                assertTrue(actual <= expected + 2, "actual=$actual, expected=$expected")
                assertTrue(actual >= expected - 2, "actual=$actual, expected=$expected")
            }
            Transactions.CommitmentFormat.SimpleTaprootChannels -> assertEquals(expected, actual)
        }
    }

    private fun testCommitAndHtlcTxs(commitmentFormat: Transactions.CommitmentFormat) {
        val fundingInfo = Transactions.makeFundingScript(localFundingPriv.publicKey(), remoteFundingPriv.publicKey(), commitmentFormat)
        val fundingTx = Transaction(2, listOf(), listOf(TxOut(1.btc, fundingInfo.pubkeyScript)), 0)
        val commitInput = Transactions.makeFundingInputInfo(fundingTx.txid, 0, 1.btc, localFundingPriv.publicKey(), remoteFundingPriv.publicKey(), commitmentFormat)
        val finalScript = write(pay2wpkh(randomKey().publicKey())).byteVector()

        val paymentPreimages = listOf(randomBytes32(), randomBytes32(), randomBytes32(), randomBytes32(), randomBytes32(), randomBytes32(), randomBytes32(), randomBytes32())
        val paymentPreimageMap = paymentPreimages.associateBy { p -> sha256(p).byteVector32() }

        // htlc1, htlc2a and htlc2b are regular IN/OUT htlcs
        val htlc1 = UpdateAddHtlc(ByteVector32.Zeroes, 0, 100.mbtc.toMilliSatoshi(), sha256(paymentPreimages[0]).byteVector32(), CltvExpiry(300), TestConstants.emptyOnionPacket)
        val htlc2a = UpdateAddHtlc(ByteVector32.Zeroes, 1, 50.mbtc.toMilliSatoshi(), sha256(paymentPreimages[1]).byteVector32(), CltvExpiry(310), TestConstants.emptyOnionPacket)
        val htlc2b = UpdateAddHtlc(ByteVector32.Zeroes, 2, 150.mbtc.toMilliSatoshi(), sha256(paymentPreimages[1]).byteVector32(), CltvExpiry(310), TestConstants.emptyOnionPacket)
        // htlc3 and htlc4 are dust IN/OUT htlcs, with an amount large enough to be included in the commit tx, but too small to be claimed at 2nd stage
        val htlc3 = UpdateAddHtlc(ByteVector32.Zeroes, 3, (localDustLimit + weight2fee(feerate, commitmentFormat.htlcTimeoutWeight)).toMilliSatoshi(), sha256(paymentPreimages[2]).byteVector32(), CltvExpiry(295), TestConstants.emptyOnionPacket)
        val htlc4 = UpdateAddHtlc(ByteVector32.Zeroes, 4, (localDustLimit + weight2fee(feerate, commitmentFormat.htlcSuccessWeight)).toMilliSatoshi(), sha256(paymentPreimages[3]).byteVector32(), CltvExpiry(300), TestConstants.emptyOnionPacket)
        // htlc5 and htlc6 are dust IN/OUT htlcs
        val htlc5 = UpdateAddHtlc(ByteVector32.Zeroes, 5, (localDustLimit * 0.9).toMilliSatoshi(), sha256(paymentPreimages[4]).byteVector32(), CltvExpiry(295), TestConstants.emptyOnionPacket)
        val htlc6 = UpdateAddHtlc(ByteVector32.Zeroes, 6, (localDustLimit * 0.9).toMilliSatoshi(), sha256(paymentPreimages[5]).byteVector32(), CltvExpiry(305), TestConstants.emptyOnionPacket)
        // htlc7 and htlc8 are at the dust limit when we ignore 2nd-stage tx fees
        val htlc7 = UpdateAddHtlc(ByteVector32.Zeroes, 7, localDustLimit.toMilliSatoshi(), sha256(paymentPreimages[6]).byteVector32(), CltvExpiry(300), TestConstants.emptyOnionPacket)
        val htlc8 = UpdateAddHtlc(ByteVector32.Zeroes, 8, localDustLimit.toMilliSatoshi(), sha256(paymentPreimages[7]).byteVector32(), CltvExpiry(302), TestConstants.emptyOnionPacket)
        val spec = CommitmentSpec(
            htlcs = setOf(
                OutgoingHtlc(htlc1),
                IncomingHtlc(htlc2a),
                IncomingHtlc(htlc2b),
                OutgoingHtlc(htlc3),
                IncomingHtlc(htlc4),
                OutgoingHtlc(htlc5),
                IncomingHtlc(htlc6),
                OutgoingHtlc(htlc7),
                IncomingHtlc(htlc8),
            ),
            feerate = feerate,
            toLocal = 400.mbtc.toMilliSatoshi(),
            toRemote = 300.mbtc.toMilliSatoshi()
        )
        val (secretLocalNonce, publicLocalNonce) = Musig2.generateNonce(randomBytes32(), Either.Left(localFundingPriv), listOf(localFundingPriv.publicKey()), null, null)
        val (secretRemoteNonce, publicRemoteNonce) = Musig2.generateNonce(randomBytes32(), Either.Left(remoteFundingPriv), listOf(remoteFundingPriv.publicKey()), null, null)
        val publicNonces = listOf(publicLocalNonce, publicRemoteNonce)

        val commitTxNumber = 0x404142434445L
        val commitTxOutputs = Transactions.makeCommitTxOutputs(localFundingPriv.publicKey(), remoteFundingPriv.publicKey(), localKeys.publicKeys, payCommitTxFees = true, localDustLimit, toLocalDelay, commitmentFormat, spec)
        val (commitTx, htlcTimeoutTxs, htlcSuccessTxs) = run {
            val txInfo = Transactions.makeCommitTx(commitInput, commitTxNumber, localPaymentPriv.publicKey(), remotePaymentPriv.publicKey(), localIsChannelOpener = true, commitTxOutputs)
            val commitTx = when (commitmentFormat) {
                Transactions.CommitmentFormat.AnchorOutputs -> {
                    val localSig = txInfo.sign(localFundingPriv, remoteFundingPriv.publicKey())
                    val remoteSig = txInfo.sign(remoteFundingPriv, localFundingPriv.publicKey())
                    assertTrue(txInfo.checkRemoteSig(localFundingPriv.publicKey(), remoteFundingPriv.publicKey(), remoteSig))
                    val invalidRemoteSig = ChannelSpendSignature.IndividualSignature(randomBytes64())
                    assertFalse(txInfo.checkRemoteSig(localFundingPriv.publicKey(), remoteFundingPriv.publicKey(), invalidRemoteSig))
                    txInfo.aggregateSigs(localFundingPriv.publicKey(), remoteFundingPriv.publicKey(), localSig, remoteSig)
                }
                Transactions.CommitmentFormat.SimpleTaprootChannels -> {
                    val localPartialSig = txInfo.partialSign(localFundingPriv, remoteFundingPriv.publicKey(), mapOf(), Transactions.LocalNonce(secretLocalNonce, publicLocalNonce), publicNonces).right!!
                    val remotePartialSig = txInfo.partialSign(remoteFundingPriv, localFundingPriv.publicKey(), mapOf(), Transactions.LocalNonce(secretRemoteNonce, publicRemoteNonce), publicNonces).right!!
                    txInfo.aggregateSigs(localFundingPriv.publicKey(), remoteFundingPriv.publicKey(), localPartialSig, remotePartialSig, mapOf()).right!!
                }
            }
            Transaction.correctlySpends(commitTx, listOf(fundingTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            // We check the expected weight of the commit input:
            val commitInputWeight = commitTx.copy(txIn = listOf(commitTx.txIn.first(), commitTx.txIn.first())).weight() - commitTx.weight()
            checkExpectedWeight(commitInputWeight, commitmentFormat.fundingInputWeight, commitmentFormat)
            val htlcTxs = Transactions.makeHtlcTxs(commitTx, commitTxOutputs, commitmentFormat)
            val expiries = htlcTxs.associate { it.htlcId to it.htlcExpiry.toLong() }
            val htlcSuccessTxs = htlcTxs.filterIsInstance<Transactions.HtlcSuccessTx>()
            val htlcTimeoutTxs = htlcTxs.filterIsInstance<Transactions.HtlcTimeoutTx>()
            when (commitmentFormat) {
                Transactions.CommitmentFormat.AnchorOutputs, Transactions.CommitmentFormat.SimpleTaprootChannels -> {
                    assertEquals(5, htlcTxs.size)
                    assertEquals(mapOf(0L to 300L, 1L to 310L, 2L to 310L, 3L to 295L, 4L to 300L), expiries)
                    assertEquals(2, htlcTimeoutTxs.size)
                    assertEquals(setOf(0L, 3L), htlcTimeoutTxs.map { it.htlcId }.toSet())
                    assertEquals(3, htlcSuccessTxs.size)
                    assertEquals(setOf(1L, 2L, 4L), htlcSuccessTxs.map { it.htlcId }.toSet())
                }
            }
            Triple(commitTx, htlcTimeoutTxs, htlcSuccessTxs)
        }

        run {
            // local spends main delayed output
            val localMain = Transactions.ClaimLocalDelayedOutputTx.createUnsignedTx(localKeys, commitTx, localDustLimit, toLocalDelay, finalScript, feerate, commitmentFormat).map { it.sign().tx }.right
            assertNotNull(localMain)
            checkExpectedWeight(localMain.weight(), commitmentFormat.toLocalDelayedWeight, commitmentFormat)
            Transaction.correctlySpends(localMain, listOf(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }
        run {
            // remote spends main delayed output
            val remoteMain = Transactions.ClaimRemoteDelayedOutputTx.createUnsignedTx(remoteKeys, commitTx, localDustLimit, finalScript, feerate, commitmentFormat).map { it.sign().tx }.right
            assertNotNull(remoteMain)
            checkExpectedWeight(remoteMain.weight(), commitmentFormat.toRemoteWeight, commitmentFormat)
            Transaction.correctlySpends(remoteMain, listOf(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }
        run {
            // remote spends local main delayed output with revocation key
            val mainPenalty = Transactions.MainPenaltyTx.createUnsignedTx(remoteKeys, localRevocationPriv, commitTx, localDustLimit, finalScript, toLocalDelay, feerate, commitmentFormat).map { it.sign().tx }.right
            assertNotNull(mainPenalty)
            checkExpectedWeight(mainPenalty.weight(), commitmentFormat.mainPenaltyWeight, commitmentFormat)
            Transaction.correctlySpends(mainPenalty, listOf(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }
        run {
            // local spends received htlc with HTLC-timeout tx
            htlcTimeoutTxs.forEach { htlcTimeoutTx ->
                val remoteSig = htlcTimeoutTx.localSig(remoteKeys)
                assertTrue(htlcTimeoutTx.checkRemoteSig(localKeys, remoteSig))
                val signedTx = htlcTimeoutTx.sign(localKeys, remoteSig).tx
                Transaction.correctlySpends(signedTx, listOf(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
                // local detects when remote doesn't use the right sighash flags
                val invalidSighash = when (commitmentFormat) {
                    Transactions.CommitmentFormat.AnchorOutputs, Transactions.CommitmentFormat.SimpleTaprootChannels -> listOf(
                        SigHash.SIGHASH_ALL,
                        SigHash.SIGHASH_ALL or SigHash.SIGHASH_ANYONECANPAY,
                        SigHash.SIGHASH_SINGLE,
                        SigHash.SIGHASH_NONE
                    )
                }
                invalidSighash.forEach { sighash ->
                    val invalidRemoteSig = htlcTimeoutTx.sign(remoteKeys.ourHtlcKey, sighash, htlcTimeoutTx.redeemInfo(remoteKeys.publicKeys), mapOf())
                    assertFalse(htlcTimeoutTx.checkRemoteSig(localKeys, invalidRemoteSig))
                }
            }
        }
        run {
            // local spends delayed output of htlc1 timeout tx
            val htlcDelayed = Transactions.HtlcDelayedTx.createUnsignedTx(localKeys, htlcTimeoutTxs[1].tx, localDustLimit, toLocalDelay, finalScript, feerate, commitmentFormat).map { it.sign().tx }.right
            assertNotNull(htlcDelayed)
            checkExpectedWeight(htlcDelayed.weight(), commitmentFormat.htlcDelayedWeight, commitmentFormat)
            Transaction.correctlySpends(htlcDelayed, listOf(htlcTimeoutTxs[1].tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            // local can't claim delayed output of htlc3 timeout tx because it is below the dust limit
            val htlcDelayed1 = Transactions.HtlcDelayedTx.createUnsignedTx(localKeys, htlcTimeoutTxs[0].tx, localDustLimit, toLocalDelay, finalScript, feerate, commitmentFormat)
            assertEquals(Either.Left(Transactions.TxGenerationSkipped.AmountBelowDustLimit), htlcDelayed1)
        }
        run {
            // local spends offered htlc with HTLC-success tx
            htlcSuccessTxs.take(3).forEach { htlcSuccessTx ->
                val preimage = paymentPreimageMap[htlcSuccessTx.paymentHash]!!
                val remoteSig = htlcSuccessTx.localSig(remoteKeys)
                val signedTx = htlcSuccessTx.sign(localKeys, remoteSig, preimage).tx
                Transaction.correctlySpends(signedTx, listOf(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
                assertTrue(htlcSuccessTx.checkRemoteSig(localKeys, remoteSig))
                // local detects when remote doesn't use the right sighash flags
                val invalidSighash = when (commitmentFormat) {
                    Transactions.CommitmentFormat.AnchorOutputs, Transactions.CommitmentFormat.SimpleTaprootChannels -> listOf(
                        SigHash.SIGHASH_ALL,
                        SigHash.SIGHASH_ALL or SigHash.SIGHASH_ANYONECANPAY,
                        SigHash.SIGHASH_SINGLE,
                        SigHash.SIGHASH_NONE
                    )
                }
                invalidSighash.forEach { sighash ->
                    val invalidRemoteSig = htlcSuccessTx.sign(remoteKeys.ourHtlcKey, sighash, htlcSuccessTx.redeemInfo(remoteKeys.publicKeys), mapOf())
                    assertFalse(htlcSuccessTx.checkRemoteSig(localKeys, invalidRemoteSig))
                }
            }
        }
        run {
            // local spends delayed output of htlc2a and htlc2b success txs
            val htlcDelayedA = Transactions.HtlcDelayedTx.createUnsignedTx(localKeys, htlcSuccessTxs[1].tx, localDustLimit, toLocalDelay, finalScript, feerate, commitmentFormat).map { it.sign().tx }.right
            assertNotNull(htlcDelayedA)
            val htlcDelayedB = Transactions.HtlcDelayedTx.createUnsignedTx(localKeys, htlcSuccessTxs[2].tx, localDustLimit, toLocalDelay, finalScript, feerate, commitmentFormat).map { it.sign().tx }.right
            assertNotNull(htlcDelayedB)
            listOf(htlcDelayedA, htlcDelayedB).forEach { checkExpectedWeight(it.weight(), commitmentFormat.htlcDelayedWeight, commitmentFormat) }
            Transaction.correctlySpends(htlcDelayedA, listOf(htlcSuccessTxs[1].tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            Transaction.correctlySpends(htlcDelayedB, listOf(htlcSuccessTxs[2].tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            // local can't claim delayed output of htlc4 success tx because it is below the dust limit
            val htlcDelayedC = Transactions.HtlcDelayedTx.createUnsignedTx(localKeys, htlcSuccessTxs[0].tx, localDustLimit, toLocalDelay, finalScript, feerate, commitmentFormat)
            assertEquals(Either.Left(Transactions.TxGenerationSkipped.AmountBelowDustLimit), htlcDelayedC)
        }
        run {
            // remote spends local->remote htlc outputs directly in case of success
            listOf(htlc1, htlc3).forEach { htlc ->
                val preimage = paymentPreimageMap[htlc.paymentHash]!!
                val htlcTx = Transactions.ClaimHtlcSuccessTx.createUnsignedTx(remoteKeys, commitTx, localDustLimit, commitTxOutputs, finalScript, htlc, preimage, feerate, commitmentFormat).map { it.sign().tx }.right
                assertNotNull(htlcTx)
                checkExpectedWeight(htlcTx.weight(), commitmentFormat.claimHtlcSuccessWeight, commitmentFormat)
                Transaction.correctlySpends(htlcTx, listOf(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            }
        }
        run {
            // remote spends htlc1's htlc-timeout tx with revocation key
            val penaltyTx = Transactions.ClaimHtlcDelayedOutputPenaltyTx.createUnsignedTxs(remoteKeys, localRevocationPriv, htlcTimeoutTxs[1].tx, localDustLimit, toLocalDelay, finalScript, feerate, commitmentFormat).first().right?.sign()?.tx
            assertNotNull(penaltyTx)
            checkExpectedWeight(penaltyTx.weight(), commitmentFormat.claimHtlcPenaltyWeight, commitmentFormat)
            Transaction.correctlySpends(penaltyTx, listOf(htlcTimeoutTxs[1].tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            // remote can't claim revoked output of htlc3's htlc-timeout tx because it is below the dust limit
            val penaltyTx1 = Transactions.ClaimHtlcDelayedOutputPenaltyTx.createUnsignedTxs(remoteKeys, localRevocationPriv, htlcTimeoutTxs[0].tx, localDustLimit, toLocalDelay, finalScript, feerate, commitmentFormat)
            assertEquals(1, penaltyTx1.size)
            assertEquals(Either.Left(Transactions.TxGenerationSkipped.AmountBelowDustLimit), penaltyTx1.first())
        }
        run {
            // remote spends remote->local htlc output directly in case of timeout
            listOf(htlc2a, htlc2b).forEach { htlc ->
                val htlcTx = Transactions.ClaimHtlcTimeoutTx.createUnsignedTx(remoteKeys, commitTx, localDustLimit, commitTxOutputs, finalScript, htlc, feerate, commitmentFormat).map { it.sign().tx }.right
                assertNotNull(htlcTx)
                checkExpectedWeight(htlcTx.weight(), commitmentFormat.claimHtlcTimeoutWeight, commitmentFormat)
                Transaction.correctlySpends(htlcTx, listOf(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            }
        }
        run {
            // remote spends htlc2a/htlc2b's htlc-success tx with revocation key
            val penaltyTxA = Transactions.ClaimHtlcDelayedOutputPenaltyTx.createUnsignedTxs(remoteKeys, localRevocationPriv, htlcSuccessTxs[1].tx, localDustLimit, toLocalDelay, finalScript, feerate, commitmentFormat).first().right?.sign()?.tx
            assertNotNull(penaltyTxA)
            val penaltyTxB = Transactions.ClaimHtlcDelayedOutputPenaltyTx.createUnsignedTxs(remoteKeys, localRevocationPriv, htlcSuccessTxs[2].tx, localDustLimit, toLocalDelay, finalScript, feerate, commitmentFormat).first().right?.sign()?.tx
            assertNotNull(penaltyTxB)
            listOf(penaltyTxA, penaltyTxB).forEach { checkExpectedWeight(it.weight(), commitmentFormat.claimHtlcPenaltyWeight, commitmentFormat) }
            Transaction.correctlySpends(penaltyTxA, listOf(htlcSuccessTxs[1].tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            Transaction.correctlySpends(penaltyTxB, listOf(htlcSuccessTxs[2].tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            // remote can't claim revoked output of htlc4's htlc-success tx because it is below the dust limit
            val penaltyTx1 = Transactions.ClaimHtlcDelayedOutputPenaltyTx.createUnsignedTxs(remoteKeys, localRevocationPriv, htlcSuccessTxs[0].tx, localDustLimit, toLocalDelay, finalScript, feerate, commitmentFormat)
            assertEquals(1, penaltyTx1.size)
            assertEquals(Either.Left(Transactions.TxGenerationSkipped.AmountBelowDustLimit), penaltyTx1.first())
        }
        run {
            // remote spends all htlc txs aggregated in a single tx
            val txIn = htlcTimeoutTxs.flatMap { it.tx.txIn } + htlcSuccessTxs.flatMap { it.tx.txIn }
            val txOut = htlcTimeoutTxs.flatMap { it.tx.txOut } + htlcSuccessTxs.flatMap { it.tx.txOut }
            val aggregatedHtlcTx = Transaction(2, txIn, txOut, 0)
            val penaltyTxs = Transactions.ClaimHtlcDelayedOutputPenaltyTx.createUnsignedTxs(remoteKeys, localRevocationPriv, aggregatedHtlcTx, localDustLimit, toLocalDelay, finalScript, feerate, commitmentFormat)
            val skipped = penaltyTxs.mapNotNull { it.left }
            val claimed = penaltyTxs.mapNotNull { it.right?.sign()?.tx }
            when (commitmentFormat) {
                Transactions.CommitmentFormat.AnchorOutputs, Transactions.CommitmentFormat.SimpleTaprootChannels -> {
                    assertEquals(5, penaltyTxs.size)
                    assertEquals(2, skipped.size)
                    assertEquals(setOf(Transactions.TxGenerationSkipped.AmountBelowDustLimit), skipped.toSet())
                    assertEquals(3, claimed.size)
                    assertEquals(3, claimed.flatMap { tx -> tx.txIn.map { txIn -> txIn.outPoint } }.toSet().size)
                }
            }
            claimed.forEach { penaltyTx ->
                checkExpectedWeight(penaltyTx.weight(), commitmentFormat.claimHtlcPenaltyWeight, commitmentFormat)
                Transaction.correctlySpends(penaltyTx, listOf(aggregatedHtlcTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            }
        }
        run {
            // remote spends htlc outputs with revocation key
            val htlcs = spec.htlcs.map { it.add }.map { Pair(it.paymentHash, it.cltvExpiry) }
            val penaltyTxs = Transactions.HtlcPenaltyTx.createUnsignedTxs(remoteKeys, localRevocationPriv, commitTx, htlcs, localDustLimit, finalScript, feerate, commitmentFormat)
            assertEquals(setOf(htlc1, htlc2a, htlc2b, htlc3, htlc4).map { it.paymentHash }.toSet(), penaltyTxs.mapNotNull { it.right?.paymentHash }.toSet()) // the first 5 htlcs are above the dust limit
            penaltyTxs.mapNotNull { it.right?.sign() }.forEach { penaltyTx ->
                val expectedWeight = if (htlcTimeoutTxs.map { it.input.outPoint }.toSet().contains(penaltyTx.input.outPoint)) {
                    commitmentFormat.htlcOfferedPenaltyWeight
                } else {
                    commitmentFormat.htlcReceivedPenaltyWeight
                }
                checkExpectedWeight(penaltyTx.tx.weight(), expectedWeight, commitmentFormat)
                Transaction.correctlySpends(penaltyTx.tx, listOf(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            }
        }
    }

    @Test
    fun `generate valid commitment and htlc transactions -- anchor outputs`() {
        testCommitAndHtlcTxs(Transactions.CommitmentFormat.AnchorOutputs)
    }

    @Test
    fun `generate valid commitment and htlc transactions -- simple taproot channels`() {
        testCommitAndHtlcTxs(Transactions.CommitmentFormat.SimpleTaprootChannels)
    }

    @Test
    fun `spend 2-of-2 legacy swap-in`() {
        val userWallet = TestConstants.Alice.keyManager.swapInOnChainWallet
        val swapInTx = Transaction(
            version = 2,
            txIn = listOf(TxIn(OutPoint(TxId(randomBytes32()), 2), 0)),
            txOut = listOf(TxOut(100_000.sat, userWallet.legacySwapInProtocol.pubkeyScript)),
            lockTime = 0
        )
        // The transaction can be spent if the user and the server produce a signature.
        run {
            val fundingTx = Transaction(
                version = 2,
                txIn = listOf(TxIn(OutPoint(swapInTx, 0), 0)),
                txOut = listOf(TxOut(90_000.sat, pay2wpkh(randomKey().publicKey()))),
                lockTime = 0
            )
            val userSig = userWallet.signSwapInputUserLegacy(fundingTx, 0, swapInTx.txOut)
            val serverKey = TestConstants.Bob.keyManager.swapInOnChainWallet.localServerPrivateKey(TestConstants.Alice.nodeParams.nodeId)
            val serverSig = userWallet.legacySwapInProtocol.signSwapInputServer(fundingTx, 0, swapInTx.txOut.first(), serverKey)
            val witness = userWallet.legacySwapInProtocol.witness(userSig, serverSig)
            val signedTx = fundingTx.updateWitness(0, witness)
            Transaction.correctlySpends(signedTx, listOf(swapInTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }
        // Or it can be spent with only the user's signature, after a delay.
        run {
            val fundingTx = Transaction(
                version = 2,
                txIn = listOf(TxIn(OutPoint(swapInTx, 0), userWallet.refundDelay.toLong())),
                txOut = listOf(TxOut(90_000.sat, pay2wpkh(randomKey().publicKey()))),
                lockTime = 0
            )
            val userSig = userWallet.signSwapInputUserLegacy(fundingTx, 0, swapInTx.txOut)
            val witness = userWallet.legacySwapInProtocol.witnessRefund(userSig)
            val signedTx = fundingTx.updateWitness(0, witness)
            Transaction.correctlySpends(signedTx, listOf(swapInTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }
    }

    @Test
    fun `spend 2-of-2 swap-in taproot-musig2 version`() {
        val userPrivateKey = PrivateKey(ByteArray(32) { 1 })
        val serverPrivateKey = PrivateKey(ByteArray(32) { 2 })
        val refundDelay = 25920

        val mnemonics = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        val seed = MnemonicCode.toSeed(mnemonics, "")
        val masterPrivateKey = DeterministicWallet.derivePrivateKey(DeterministicWallet.generate(seed), "/51'/0'/0'").copy(path = KeyPath.empty)
        val userRefundPrivateKey = DeterministicWallet.derivePrivateKey(masterPrivateKey, "0").privateKey
        val swapInProtocol = SwapInProtocol(userPrivateKey.publicKey(), serverPrivateKey.publicKey(), userRefundPrivateKey.publicKey(), refundDelay)

        val swapInTx = Transaction(
            version = 2,
            txIn = listOf(),
            txOut = listOf(TxOut(Satoshi(10000), swapInProtocol.pubkeyScript)),
            lockTime = 0
        )

        // The transaction can be spent if the user and the server produce a signature.
        run {
            val tx = Transaction(
                version = 2,
                txIn = listOf(TxIn(OutPoint(swapInTx, 0), sequence = TxIn.SEQUENCE_FINAL)),
                txOut = listOf(TxOut(Satoshi(10000), pay2wpkh(PrivateKey(randomBytes32()).publicKey()))),
                lockTime = 0
            )
            // The first step of a musig2 signing session is to exchange nonces.
            // If participants are disconnected before the end of the signing session, they must start again with fresh nonces.
            val userNonce = Musig2.generateNonce(randomBytes32(), Either.Left(userPrivateKey), listOf(userPrivateKey.publicKey(), serverPrivateKey.publicKey()), null, null)
            val serverNonce = Musig2.generateNonce(randomBytes32(), Either.Left(serverPrivateKey), listOf(serverPrivateKey.publicKey(), userPrivateKey.publicKey()), null, null)

            // Once they have each other's public nonce, they can produce partial signatures.
            val userSig = swapInProtocol.signSwapInputUser(tx, 0, swapInTx.txOut, userPrivateKey, userNonce.first, userNonce.second, serverNonce.second).right!!
            val serverSig = swapInProtocol.signSwapInputServer(tx, 0, swapInTx.txOut, serverPrivateKey, serverNonce.first, userNonce.second, serverNonce.second).right!!

            // Once they have each other's partial signature, they can aggregate them into a valid signature.
            val witness = swapInProtocol.witness(tx, 0, swapInTx.txOut, userNonce.second, serverNonce.second, userSig, serverSig).right
            assertNotNull(witness)
            val signedTx = tx.updateWitness(0, witness)
            Transaction.correctlySpends(signedTx, swapInTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }

        // Or it can be spent with only the user's signature, after a delay.
        run {
            val tx = Transaction(
                version = 2,
                txIn = listOf(TxIn(OutPoint(swapInTx, 0), sequence = refundDelay.toLong())),
                txOut = listOf(TxOut(Satoshi(10000), pay2wpkh(PrivateKey(randomBytes32()).publicKey()))),
                lockTime = 0
            )
            val sig = swapInProtocol.signSwapInputRefund(tx, 0, swapInTx.txOut, userRefundPrivateKey)
            val signedTx = tx.updateWitness(0, swapInProtocol.witnessRefund(sig))
            Transaction.correctlySpends(signedTx, swapInTx, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        }
    }

    @Test
    fun `swap-in input weight`() {
        val pubkey = randomKey().publicKey()
        // DER-encoded ECDSA signatures usually take up to 72 bytes.
        val sig = ByteVector64.fromValidHex("90b658d172a51f1b3f1a2becd30942397f5df97da8cd2c026854607e955ad815ccfd87d366e348acc32aaf15ff45263aebbb7ecc913a0e5999133f447aee828c")
        val tx = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32.Zeroes), 2), 0)), listOf(TxOut(50_000.sat, pay2wpkh(pubkey))), 0)
        val swapInProtocol = SwapInProtocolLegacy(pubkey, pubkey, 144)
        val witness = swapInProtocol.witness(sig, sig)
        val swapInput = TxIn(OutPoint(TxId(ByteVector32.Zeroes), 3), ByteVector.empty, 0, witness)
        val txWithAdditionalInput = tx.copy(txIn = tx.txIn + listOf(swapInput))
        val inputWeight = txWithAdditionalInput.weight() - tx.weight()
        assertEquals(inputWeight, swapInputWeightLegacy)
    }

    @Test
    fun `swap-in input weight -- musig2 version`() {
        val pubkey = randomKey().publicKey()
        val sig = ByteVector64.fromValidHex("90b658d172a51f1b3f1a2becd30942397f5df97da8cd2c026854607e955ad815ccfd87d366e348acc32aaf15ff45263aebbb7ecc913a0e5999133f447aee828c")
        val tx = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32.Zeroes), 2), 0)), listOf(TxOut(50_000.sat, pay2wpkh(pubkey))), 0)
        val witness = Script.witnessKeyPathPay2tr(sig)
        val swapInput = TxIn(OutPoint(TxId(ByteVector32.Zeroes), 3), ByteVector.empty, 0, witness)
        val txWithAdditionalInput = tx.copy(txIn = tx.txIn + listOf(swapInput))
        val inputWeight = txWithAdditionalInput.weight() - tx.weight()
        assertEquals(inputWeight, swapInputWeight)
    }

    @Test
    fun `adjust feerate when purchasing liquidity for on-the-fly payment`() {
        val fundingScript = write(Scripts.multiSig2of2(localFundingPriv.publicKey(), remoteFundingPriv.publicKey()))
        val fundingWitness = Scripts.witness2of2(randomBytes64(), randomBytes64(), localFundingPriv.publicKey(), remoteFundingPriv.publicKey())
        val changeOutput = TxOut(150_000.sat, pay2wpkh(randomKey().publicKey()))

        fun createSignedWalletInput(): TxIn {
            val witness = Script.witnessPay2wpkh(randomKey().publicKey(), Scripts.der(randomBytes64(), SigHash.SIGHASH_ALL))
            return TxIn(OutPoint(TxId(randomBytes32()), 3), ByteVector.empty, 0, witness)
        }

        fun computeRatio(emptyTx: Transaction, completeTx: Transaction): Double = completeTx.weight().toDouble() / (completeTx.weight() - emptyTx.weight())

        // When a wallet user purchases liquidity for an on-the-fly payment and they don't have any on-chain funds,
        // they won't be able to contribute to the mining fees for the channel creation or splice.
        // The LSP will be the only contributor to the mining fees, so we need to take into account the fact that
        // they must pay the wallet's mining fees (and will be refunded by collecting fees from the future HTLCs).
        // We make that work by multiplying the target feerate by the weight ratio between the weight that should
        // be paid by the wallet user and the weight that should be paid by the LSP.
        // We don't know beforehand how many inputs/outputs the LSP will add: the more they add, the smaller the
        // ratio is, but we must ensure that in the case where the LSP adds a single input and no change output
        // (which is the case where the ratio is the highest) we still apply a large enough multiplier to ensure
        // that the transaction meets the minimum relay fees (1 sat/byte on most nodes).
        //
        // The sections below highlight the ratios for the following cases, for channel creation and splicing:
        //  - the LSP adds a single input and no change output (highest ratio)
        //  - the LSP adds a single input and a change output
        //  - the LSP adds two inputs and a change output
        run {
            // When opening a new channel, the liquidity buyer is responsible for paying the fees of the shared output and common transaction fields.
            val txNoInput = Transaction(2, listOf(), listOf(TxOut(500_000.sat, fundingScript)), 0)
            // For low feerates, we will apply the largest ratio.
            val lowFeerate = FeeratePerKw(300.sat)
            val highRatio = run {
                val txOneInput = txNoInput.addInput(createSignedWalletInput())
                computeRatio(txNoInput, txOneInput)
            }
            assertTrue(highRatio in 2.3..2.4)
            assertEquals(lowFeerate * 2.5, AddLiquidityForIncomingPayment.adjustFeerate(lowFeerate, isChannelCreation = true))
            val mediumFeerate = FeeratePerKw(1000.sat)
            val mediumRatio = run {
                val txOneInputWithChange = txNoInput.addInput(createSignedWalletInput()).addOutput(changeOutput)
                computeRatio(txNoInput, txOneInputWithChange)
            }
            assertTrue(mediumRatio in 1.9..2.0)
            assertEquals(mediumFeerate * 2, AddLiquidityForIncomingPayment.adjustFeerate(mediumFeerate, isChannelCreation = true))
            val highFeerate = FeeratePerKw(2000.sat)
            val lowRatio = run {
                val txTwoInputsWithChange = txNoInput.addInput(createSignedWalletInput()).addInput(createSignedWalletInput()).addOutput(changeOutput)
                computeRatio(txNoInput, txTwoInputsWithChange)
            }
            assertTrue(lowRatio in 1.5..1.6)
            assertEquals(highFeerate * 1.6, AddLiquidityForIncomingPayment.adjustFeerate(highFeerate, isChannelCreation = true))
        }
        run {
            // When splicing an existing channel, the liquidity buyer is responsible for paying the fees of the shared input, the shared output and common transaction fields.
            val txNoInput = Transaction(2, listOf(TxIn(OutPoint(TxId(randomBytes32()), 3), ByteVector.empty, 0, fundingWitness)), listOf(TxOut(500_000.sat, fundingScript)), 0)
            // For low feerates, we will apply the largest ratio.
            val lowFeerate = FeeratePerKw(300.sat)
            val highRatio = run {
                val txOneInput = txNoInput.addInput(createSignedWalletInput())
                computeRatio(txNoInput, txOneInput)
            }
            assertTrue(highRatio in 3.7..3.8)
            assertEquals(lowFeerate * 3.8, AddLiquidityForIncomingPayment.adjustFeerate(lowFeerate, isChannelCreation = false))
            val mediumFeerate = FeeratePerKw(1000.sat)
            val mediumRatio = run {
                val txOneInputWithChange = txNoInput.addInput(createSignedWalletInput()).addOutput(changeOutput)
                computeRatio(txNoInput, txOneInputWithChange)
            }
            assertTrue(mediumRatio in 2.8..2.9)
            assertEquals(mediumFeerate * 2.9, AddLiquidityForIncomingPayment.adjustFeerate(mediumFeerate, isChannelCreation = false))
            val highFeerate = FeeratePerKw(2000.sat)
            val lowRatio = run {
                val txTwoInputsWithChange = txNoInput.addInput(createSignedWalletInput()).addInput(createSignedWalletInput()).addOutput(changeOutput)
                computeRatio(txNoInput, txTwoInputsWithChange)
            }
            assertTrue(lowRatio in 2.1..2.2)
            assertEquals(highFeerate * 2.2, AddLiquidityForIncomingPayment.adjustFeerate(highFeerate, isChannelCreation = false))
        }
    }

    @Test
    fun `sort the htlc outputs using BIP69 and cltv expiry`() {
        val localFundingPriv = PrivateKey.fromHex("a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1")
        val remoteFundingPriv = PrivateKey.fromHex("a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2")
        val localRevocationPriv = PrivateKey.fromHex("a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3")
        val localPaymentPriv = PrivateKey.fromHex("a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4")
        val localDelayedPaymentPriv = PrivateKey.fromHex("a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5")
        val remotePaymentPriv = PrivateKey.fromHex("a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6a6")
        val localHtlcPriv = PrivateKey.fromHex("a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7")
        val remoteHtlcPriv = PrivateKey.fromHex("a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8a8")
        val localKeys = LocalCommitmentKeys(
            ourDelayedPaymentKey = localDelayedPaymentPriv,
            theirPaymentPublicKey = remotePaymentPriv.publicKey(),
            ourPaymentBasePoint = localPaymentBasePoint,
            ourHtlcKey = localHtlcPriv,
            theirHtlcPublicKey = remoteHtlcPriv.publicKey(),
            revocationPublicKey = localRevocationPriv.publicKey(),
        )
        val commitInput =
            Transactions.makeFundingInputInfo(TxId("a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0"), 0, 1.btc, localFundingPriv.publicKey(), remoteFundingPriv.publicKey(), Transactions.CommitmentFormat.AnchorOutputs)
        // htlc1 and htlc2 are two regular incoming HTLCs with different amounts.
        // htlc2 and htlc3 have the same amounts and should be sorted according to their scriptPubKey
        // htlc4 is identical to htlc3 and htlc5 has same payment_hash/amount but different CLTV
        val paymentPreimage1 = ByteVector32.fromValidHex("1111111111111111111111111111111111111111111111111111111111111111")
        val paymentPreimage2 = ByteVector32.fromValidHex("2222222222222222222222222222222222222222222222222222222222222222")
        val paymentPreimage3 = ByteVector32.fromValidHex("3333333333333333333333333333333333333333333333333333333333333333")
        val htlc1 = UpdateAddHtlc(randomBytes32(), 1, 100.mbtc.toMilliSatoshi(), paymentPreimage1.sha256(), CltvExpiry(300), TestConstants.emptyOnionPacket)
        val htlc2 = UpdateAddHtlc(randomBytes32(), 2, 200.mbtc.toMilliSatoshi(), paymentPreimage2.sha256(), CltvExpiry(300), TestConstants.emptyOnionPacket)
        val htlc3 = UpdateAddHtlc(randomBytes32(), 3, 200.mbtc.toMilliSatoshi(), paymentPreimage3.sha256(), CltvExpiry(300), TestConstants.emptyOnionPacket)
        val htlc4 = UpdateAddHtlc(randomBytes32(), 4, 200.mbtc.toMilliSatoshi(), paymentPreimage3.sha256(), CltvExpiry(300), TestConstants.emptyOnionPacket)
        val htlc5 = UpdateAddHtlc(randomBytes32(), 5, 200.mbtc.toMilliSatoshi(), paymentPreimage3.sha256(), CltvExpiry(301), TestConstants.emptyOnionPacket)
        val spec = CommitmentSpec(
            htlcs = setOf(
                OutgoingHtlc(htlc1),
                OutgoingHtlc(htlc2),
                OutgoingHtlc(htlc3),
                OutgoingHtlc(htlc4),
                OutgoingHtlc(htlc5)
            ),
            feerate = feerate,
            toLocal = 400.mbtc.toMilliSatoshi(),
            toRemote = 300.mbtc.toMilliSatoshi()
        )
        val commitTxNumber = 0x404142434446L
        val (commitTx, outputs, htlcTxs) = run {
            val outputs = Transactions.makeCommitTxOutputs(
                localFundingPriv.publicKey(),
                remoteFundingPriv.publicKey(),
                localKeys.publicKeys,
                true,
                localDustLimit,
                toLocalDelay,
                Transactions.CommitmentFormat.AnchorOutputs,
                spec
            )
            val txInfo = Transactions.makeCommitTx(commitInput, commitTxNumber, localPaymentPriv.publicKey(), remotePaymentPriv.publicKey(), true, outputs)
            val localSig = txInfo.sign(localFundingPriv, remoteFundingPriv.publicKey())
            val remoteSig = txInfo.sign(remoteFundingPriv, localFundingPriv.publicKey())
            val commitTx = txInfo.aggregateSigs(localFundingPriv.publicKey(), remoteFundingPriv.publicKey(), localSig, remoteSig)
            val htlcTxs = Transactions.makeHtlcTxs(commitTx, outputs, Transactions.CommitmentFormat.AnchorOutputs)
            Triple(commitTx, outputs, htlcTxs)
        }
        // htlc1 comes before htlc2 because of the smaller amount (BIP69)
        // htlc3, htlc4 and htlc5 have the same pubKeyScript but htlc5 comes after because it has a higher CLTV
        // htlc2 and htlc3/4/5 have the same amount but htlc2 comes last because its pubKeyScript is lexicographically greater than htlc3/4/5
        val (htlcOut1, htlcOut2, htlcOut3, htlcOut4, htlcOut5) = commitTx.txOut.drop(2)
        assertEquals(10_000_000.sat, htlcOut1.amount)
        for (htlcOut in listOf(htlcOut2, htlcOut3, htlcOut4, htlcOut5)) {
            assertEquals(20_000_000.sat, htlcOut.amount)
        }
        // htlc3 and htlc4 are completely identical, their relative order can't be enforced.
        assertEquals(5, htlcTxs.size)
        htlcTxs.forEach { tx -> assertIs<Transactions.HtlcTimeoutTx>(tx) }
        val htlcIds = htlcTxs.map { it.htlcId }
        assertTrue(htlcIds == listOf(1L, 3L, 4L, 5L, 2L) || htlcIds == listOf(1L, 4L, 3L, 5L, 2L))
        assertTrue(htlcOut4.publicKeyScript.toHex() < htlcOut5.publicKeyScript.toHex())
        assertEquals(htlcOut1.publicKeyScript, outputs.find { it is OutHtlc && it.htlc.add == htlc1 }?.txOut?.publicKeyScript)
        assertEquals(htlcOut5.publicKeyScript, outputs.find { it is OutHtlc && it.htlc.add == htlc2 }?.txOut?.publicKeyScript)
        assertEquals(htlcOut3.publicKeyScript, outputs.find { it is OutHtlc && it.htlc.add == htlc3 }?.txOut?.publicKeyScript)
        assertEquals(htlcOut4.publicKeyScript, outputs.find { it is OutHtlc && it.htlc.add == htlc4 }?.txOut?.publicKeyScript)
        assertEquals(htlcOut2.publicKeyScript, outputs.find { it is OutHtlc && it.htlc.add == htlc5 }?.txOut?.publicKeyScript)
    }

    @Test
    fun `find our output in closing tx`() {
        val localPubKeyScript = write(pay2wpkh(PrivateKey(randomBytes32()).publicKey())).byteVector()
        val remotePubKeyScript = write(pay2wpkh(PrivateKey(randomBytes32()).publicKey())).byteVector()
        val commitInput = Transactions.makeFundingInputInfo(TxId(randomBytes32()), 0, 1.btc, localFundingPriv.publicKey(), remoteFundingPriv.publicKey(), Transactions.CommitmentFormat.AnchorOutputs)

        run {
            // Different amounts, both outputs untrimmed, local is closer:
            val spec = CommitmentSpec(setOf(), feerate, 150_000_000.msat, 250_000_000.msat)
            val closingTxs = Transactions.makeClosingTxs(commitInput, spec, Transactions.ClosingTxFee.PaidByUs(5_000.sat), 0, localPubKeyScript, remotePubKeyScript)
            assertNotNull(closingTxs.localAndRemote)
            assertNotNull(closingTxs.localOnly)
            assertNull(closingTxs.remoteOnly)
            val localAndRemote = closingTxs.localAndRemote.toLocalOutput!!
            assertEquals(localPubKeyScript, localAndRemote.publicKeyScript)
            assertEquals(145_000.sat, localAndRemote.amount)
            val localOnly = closingTxs.localOnly.toLocalOutput!!
            assertEquals(localPubKeyScript, localOnly.publicKeyScript)
            assertEquals(145_000.sat, localOnly.amount)
        }
        run {
            // Same amounts, both outputs untrimmed, remote is closer:
            val spec = CommitmentSpec(setOf(), feerate, 150_000_000.msat, 250_000_000.msat)
            val closingTxs = Transactions.makeClosingTxs(commitInput, spec, Transactions.ClosingTxFee.PaidByThem(5_000.sat), 0, localPubKeyScript, remotePubKeyScript)
            assertNotNull(closingTxs.localAndRemote)
            assertNotNull(closingTxs.localOnly)
            assertNull(closingTxs.remoteOnly)
            val localAndRemote = closingTxs.localAndRemote.toLocalOutput!!
            assertEquals(localPubKeyScript, localAndRemote.publicKeyScript)
            assertEquals(150_000.sat, localAndRemote.amount)
            val localOnly = closingTxs.localOnly.toLocalOutput!!
            assertEquals(localPubKeyScript, localOnly.publicKeyScript)
            assertEquals(150_000.sat, localOnly.amount)
        }
        run {
            // Their output is trimmed:
            val spec = CommitmentSpec(setOf(), feerate, 150_000_000.msat, 1_000_000.msat)
            val closingTxs = Transactions.makeClosingTxs(commitInput, spec, Transactions.ClosingTxFee.PaidByThem(800.sat), 0, localPubKeyScript, remotePubKeyScript)
            assertEquals(1, closingTxs.all.size)
            assertNotNull(closingTxs.localOnly)
            assertEquals(1, closingTxs.localOnly.tx.txOut.size)
            val toLocal = closingTxs.localOnly.toLocalOutput!!
            assertEquals(localPubKeyScript, toLocal.publicKeyScript)
            assertEquals(150_000.sat, toLocal.amount)
        }
        run {
            // Our output is trimmed:
            val spec = CommitmentSpec(setOf(), feerate, 1_000_000.msat, 150_000_000.msat)
            val closingTxs = Transactions.makeClosingTxs(commitInput, spec, Transactions.ClosingTxFee.PaidByUs(800.sat), 0, localPubKeyScript, remotePubKeyScript)
            assertEquals(1, closingTxs.all.size)
            assertNotNull(closingTxs.remoteOnly)
            assertNull(closingTxs.remoteOnly.toLocalOutput)
        }
        run {
            // Both outputs are trimmed:
            val spec = CommitmentSpec(setOf(), feerate, 50_000.msat, 10_000.msat)
            val closingTxs = Transactions.makeClosingTxs(commitInput, spec, Transactions.ClosingTxFee.PaidByUs(10.sat), 0, localPubKeyScript, remotePubKeyScript)
            assertNull(closingTxs.localAndRemote)
            assertNull(closingTxs.localOnly)
            assertNull(closingTxs.remoteOnly)
        }
    }

}
