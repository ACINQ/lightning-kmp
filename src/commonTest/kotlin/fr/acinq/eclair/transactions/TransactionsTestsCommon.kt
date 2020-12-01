package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Crypto.ripemd160
import fr.acinq.bitcoin.Crypto.sha256
import fr.acinq.bitcoin.Script.pay2wpkh
import fr.acinq.bitcoin.Script.pay2wsh
import fr.acinq.bitcoin.Script.write
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.eclair.CltvExpiry
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.Eclair.randomBytes32
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Commitments
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.transactions.CommitmentOutput.OutHtlc
import fr.acinq.eclair.transactions.Scripts.htlcOffered
import fr.acinq.eclair.transactions.Scripts.htlcReceived
import fr.acinq.eclair.transactions.Scripts.toLocalDelayed
import fr.acinq.eclair.transactions.Transactions.PlaceHolderSig
import fr.acinq.eclair.transactions.Transactions.TxGenerationSkipped.AmountBelowDustLimit
import fr.acinq.eclair.transactions.Transactions.TxGenerationSkipped.OutputNotFound
import fr.acinq.eclair.transactions.Transactions.TxResult.Skipped
import fr.acinq.eclair.transactions.Transactions.TxResult.Success
import fr.acinq.eclair.transactions.Transactions.addSigs
import fr.acinq.eclair.transactions.Transactions.checkSig
import fr.acinq.eclair.transactions.Transactions.checkSpendable
import fr.acinq.eclair.transactions.Transactions.claimHtlcDelayedWeight
import fr.acinq.eclair.transactions.Transactions.claimHtlcSuccessWeight
import fr.acinq.eclair.transactions.Transactions.claimHtlcTimeoutWeight
import fr.acinq.eclair.transactions.Transactions.claimP2WPKHOutputWeight
import fr.acinq.eclair.transactions.Transactions.commitTxFee
import fr.acinq.eclair.transactions.Transactions.decodeTxNumber
import fr.acinq.eclair.transactions.Transactions.encodeTxNumber
import fr.acinq.eclair.transactions.Transactions.getCommitTxNumber
import fr.acinq.eclair.transactions.Transactions.htlcPenaltyWeight
import fr.acinq.eclair.transactions.Transactions.mainPenaltyWeight
import fr.acinq.eclair.transactions.Transactions.makeClaimHtlcSuccessTx
import fr.acinq.eclair.transactions.Transactions.makeClaimHtlcTimeoutTx
import fr.acinq.eclair.transactions.Transactions.makeClaimLocalDelayedOutputTx
import fr.acinq.eclair.transactions.Transactions.makeClaimP2WPKHOutputTx
import fr.acinq.eclair.transactions.Transactions.makeClaimRemoteDelayedOutputTx
import fr.acinq.eclair.transactions.Transactions.makeCommitTx
import fr.acinq.eclair.transactions.Transactions.makeCommitTxOutputs
import fr.acinq.eclair.transactions.Transactions.makeHtlcPenaltyTx
import fr.acinq.eclair.transactions.Transactions.makeHtlcTxs
import fr.acinq.eclair.transactions.Transactions.makeMainPenaltyTx
import fr.acinq.eclair.transactions.Transactions.sign
import fr.acinq.eclair.transactions.Transactions.weight2fee
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.UpdateAddHtlc
import kotlinx.serialization.InternalSerializationApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@InternalSerializationApi
class TransactionsTestsCommon : EclairTestSuite() {

    private val localFundingPriv = PrivateKey(randomBytes32())
    private val remoteFundingPriv = PrivateKey(randomBytes32())
    private val localRevocationPriv = PrivateKey(randomBytes32())
    private val localPaymentPriv = PrivateKey(randomBytes32())
    private val localDelayedPaymentPriv = PrivateKey(randomBytes32())
    private val remotePaymentPriv = PrivateKey(randomBytes32())
    private val localHtlcPriv = PrivateKey(randomBytes32())
    private val remoteHtlcPriv = PrivateKey(randomBytes32())
    private val finalPubKeyScript = write(pay2wpkh(PrivateKey(randomBytes32()).publicKey()))
    private val commitInput = Funding.makeFundingInputInfo(randomBytes32(), 0, 1.btc, localFundingPriv.publicKey(), remoteFundingPriv.publicKey())
    private val toLocalDelay = CltvExpiryDelta(144)
    private val localDustLimit = 546.sat
    private val feerate = FeeratePerKw(22_000.sat)

    @Test
    fun `encode and decode sequence and locktime (one example)`() {
        val txnumber = 0x11F71FB268DL

        val (sequence, locktime) = encodeTxNumber(txnumber)
        assertEquals(0x80011F71L, sequence)
        assertEquals(0x20FB268DL, locktime)

        val txnumber1 = decodeTxNumber(sequence, locktime)
        assertEquals(txnumber, txnumber1)
    }

    @Test
    fun `reconstruct txnumber from sequence and locktime`() {
        repeat(1_000) {
            val txnumber = Random.nextLong() and 0xffffffffffffL
            val (sequence, locktime) = encodeTxNumber(txnumber)
            val txnumber1 = decodeTxNumber(sequence, locktime)
            assertEquals(txnumber, txnumber1)
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
        val fee = commitTxFee(546.sat, spec)
        assertEquals(8000.sat, fee)
    }

    @Test
    fun `check pre-computed transaction weights`() {
        val finalPubKeyScript = write(pay2wpkh(PrivateKey(randomBytes32()).publicKey()))
        val localDustLimit = 546.sat
        val toLocalDelay = CltvExpiryDelta(144)
        val feeratePerKw = FeeratePerKw.MinimumFeeratePerKw
        val blockHeight = 400_000

        run {
            // ClaimP2WPKHOutputTx
            // first we create a fake commitTx tx, containing only the output that will be spent by the ClaimP2WPKHOutputTx
            val pubKeyScript = write(pay2wpkh(localPaymentPriv.publicKey()))
            val commitTx = Transaction(version = 0, txIn = emptyList(), txOut = listOf(TxOut(20000.sat, pubKeyScript)), lockTime = 0)
            val claimP2WPKHOutputTx = makeClaimP2WPKHOutputTx(commitTx, localDustLimit, localPaymentPriv.publicKey(), finalPubKeyScript, feeratePerKw)
            assertTrue(claimP2WPKHOutputTx is Success, "is $claimP2WPKHOutputTx")
            // we use dummy signatures to compute the weight
            val weight = Transaction.weight(addSigs(claimP2WPKHOutputTx.result, localPaymentPriv.publicKey(), PlaceHolderSig).tx)
            assertEquals(claimP2WPKHOutputWeight, weight)
            assertTrue(claimP2WPKHOutputTx.result.fee >= claimP2WPKHOutputTx.result.minRelayFee)
        }

        run {
            // ClaimHtlcDelayedTx
            // first we create a fake htlcSuccessOrTimeoutTx tx, containing only the output that will be spent by the ClaimDelayedOutputTx
            val pubKeyScript = write(pay2wsh(toLocalDelayed(localRevocationPriv.publicKey(), toLocalDelay, localPaymentPriv.publicKey())))
            val htlcSuccessOrTimeoutTx = Transaction(version = 0, txIn = emptyList(), txOut = listOf(TxOut(20000.sat, pubKeyScript)), lockTime = 0)
            val claimHtlcDelayedTx = makeClaimLocalDelayedOutputTx(htlcSuccessOrTimeoutTx, localDustLimit, localRevocationPriv.publicKey(), toLocalDelay, localPaymentPriv.publicKey(), finalPubKeyScript, feeratePerKw)
            assertTrue(claimHtlcDelayedTx is Success, "is $claimHtlcDelayedTx")
            // we use dummy signatures to compute the weight
            val weight = Transaction.weight(addSigs(claimHtlcDelayedTx.result, PlaceHolderSig).tx)
            assertEquals(claimHtlcDelayedWeight, weight)
            assertTrue(claimHtlcDelayedTx.result.fee >= claimHtlcDelayedTx.result.minRelayFee)
        }

        run {
            // MainPenaltyTx
            // first we create a fake commitTx tx, containing only the output that will be spent by the MainPenaltyTx
            val pubKeyScript = write(pay2wsh(toLocalDelayed(localRevocationPriv.publicKey(), toLocalDelay, localPaymentPriv.publicKey())))
            val commitTx = Transaction(version = 0, txIn = emptyList(), txOut = listOf(TxOut(20000.sat, pubKeyScript)), lockTime = 0)
            val mainPenaltyTx = makeMainPenaltyTx(commitTx, localDustLimit, localRevocationPriv.publicKey(), finalPubKeyScript, toLocalDelay, localPaymentPriv.publicKey(), feeratePerKw)
            assertTrue(mainPenaltyTx is Success, "is $mainPenaltyTx")
            // we use dummy signatures to compute the weight
            val weight = Transaction.weight(addSigs(mainPenaltyTx.result, PlaceHolderSig).tx)
            assertEquals(mainPenaltyWeight, weight)
            assertTrue(mainPenaltyTx.result.fee >= mainPenaltyTx.result.minRelayFee)
        }

        run {
            // HtlcPenaltyTx
            // first we create a fake commitTx tx, containing only the output that will be spent by the ClaimHtlcSuccessTx
            val paymentPreimage = randomBytes32()
            val htlc = UpdateAddHtlc(ByteVector32.Zeroes, 0, (20000 * 1000).msat, ByteVector32(sha256(paymentPreimage)), CltvExpiryDelta(144).toCltvExpiry(blockHeight.toLong()), TestConstants.emptyOnionPacket)
            val redeemScript = htlcReceived(localHtlcPriv.publicKey(), remoteHtlcPriv.publicKey(), localRevocationPriv.publicKey(), ripemd160(htlc.paymentHash), htlc.cltvExpiry)
            val pubKeyScript = write(pay2wsh(redeemScript))
            val commitTx = Transaction(version = 0, txIn = emptyList(), txOut = listOf(TxOut(htlc.amountMsat.truncateToSatoshi(), pubKeyScript)), lockTime = 0)
            val htlcPenaltyTx = makeHtlcPenaltyTx(commitTx, 0, write(redeemScript), localDustLimit, finalPubKeyScript, feeratePerKw)
            assertTrue(htlcPenaltyTx is Success, "is $htlcPenaltyTx")
            // we use dummy signatures to compute the weight
            val weight = Transaction.weight(addSigs(htlcPenaltyTx.result, PlaceHolderSig, localRevocationPriv.publicKey()).tx)
            assertEquals(htlcPenaltyWeight, weight)
            assertTrue(htlcPenaltyTx.result.fee >= htlcPenaltyTx.result.minRelayFee)
        }

        run {
            // ClaimHtlcSuccessTx
            // first we create a fake commitTx tx, containing only the output that will be spent by the ClaimHtlcSuccessTx
            val paymentPreimage = randomBytes32()
            val htlc = UpdateAddHtlc(ByteVector32.Zeroes, 0, (20000 * 1000).msat, ByteVector32(sha256(paymentPreimage)), CltvExpiryDelta(144).toCltvExpiry(blockHeight.toLong()), TestConstants.emptyOnionPacket)
            val spec = CommitmentSpec(setOf(OutgoingHtlc(htlc)), feeratePerKw, toLocal = 0.msat, toRemote = 0.msat)
            val outputs =
                makeCommitTxOutputs(
                    localFundingPriv.publicKey(),
                    remoteFundingPriv.publicKey(),
                    true,
                    localDustLimit,
                    localRevocationPriv.publicKey(),
                    toLocalDelay,
                    localDelayedPaymentPriv.publicKey(),
                    remotePaymentPriv.publicKey(),
                    localHtlcPriv.publicKey(),
                    remoteHtlcPriv.publicKey(),
                    spec
                )
            val commitTx = Transaction(version = 0, txIn = emptyList(), txOut = outputs.map { it.output }, lockTime = 0)
            val claimHtlcSuccessTx =
                makeClaimHtlcSuccessTx(commitTx, outputs, localDustLimit, remoteHtlcPriv.publicKey(), localHtlcPriv.publicKey(), localRevocationPriv.publicKey(), finalPubKeyScript, htlc, feeratePerKw)
            assertTrue(claimHtlcSuccessTx is Success, "is $claimHtlcSuccessTx")
            // we use dummy signatures to compute the weight
            val weight = Transaction.weight(addSigs(claimHtlcSuccessTx.result, PlaceHolderSig, paymentPreimage).tx)
            assertEquals(claimHtlcSuccessWeight, weight)
            assertTrue(claimHtlcSuccessTx.result.fee >= claimHtlcSuccessTx.result.minRelayFee)
        }

        run {
            // ClaimHtlcTimeoutTx
            // first we create a fake commitTx tx, containing only the output that will be spent by the ClaimHtlcTimeoutTx
            val paymentPreimage = randomBytes32()
            val htlc = UpdateAddHtlc(ByteVector32.Zeroes, 0, (20000 * 1000).msat, ByteVector32(sha256(paymentPreimage)), toLocalDelay.toCltvExpiry(blockHeight.toLong()), TestConstants.emptyOnionPacket)
            val spec = CommitmentSpec(setOf(IncomingHtlc(htlc)), feeratePerKw, toLocal = 0.msat, toRemote = 0.msat)
            val outputs =
                makeCommitTxOutputs(
                    localFundingPriv.publicKey(),
                    remoteFundingPriv.publicKey(),
                    true,
                    localDustLimit,
                    localRevocationPriv.publicKey(),
                    toLocalDelay,
                    localDelayedPaymentPriv.publicKey(),
                    remotePaymentPriv.publicKey(),
                    localHtlcPriv.publicKey(),
                    remoteHtlcPriv.publicKey(),
                    spec
                )
            val commitTx = Transaction(version = 0, txIn = emptyList(), txOut = outputs.map { it.output }, lockTime = 0)
            val claimHtlcTimeoutTx =
                makeClaimHtlcTimeoutTx(commitTx, outputs, localDustLimit, remoteHtlcPriv.publicKey(), localHtlcPriv.publicKey(), localRevocationPriv.publicKey(), finalPubKeyScript, htlc, feeratePerKw)
            assertTrue(claimHtlcTimeoutTx is Success, "is $claimHtlcTimeoutTx")
            // we use dummy signatures to compute the weight
            val weight = Transaction.weight(addSigs(claimHtlcTimeoutTx.result, PlaceHolderSig).tx)
            assertEquals(claimHtlcTimeoutWeight, weight)
            assertTrue(claimHtlcTimeoutTx.result.fee >= claimHtlcTimeoutTx.result.minRelayFee)
        }
    }

    @Test
    fun `generate valid commitment and htlc transactions`() {
        val finalPubKeyScript = write(pay2wpkh(PrivateKey(ByteVector32("01".repeat(32))).publicKey()))
        val commitInput = Funding.makeFundingInputInfo(ByteVector32("02".repeat(32)), 0, 1.btc, localFundingPriv.publicKey(), remoteFundingPriv.publicKey())

        // htlc1 and htlc2 are regular IN/OUT htlcs
        val paymentPreimage1 = ByteVector32("03".repeat(32))
        val htlc1 = UpdateAddHtlc(ByteVector32.Zeroes, 0, 100.mbtc.toMilliSatoshi(), ByteVector32(sha256(paymentPreimage1)), CltvExpiry(300), TestConstants.emptyOnionPacket)
        val paymentPreimage2 = ByteVector32("04".repeat(32))
        val htlc2 = UpdateAddHtlc(ByteVector32.Zeroes, 1, 200.mbtc.toMilliSatoshi(), ByteVector32(sha256(paymentPreimage2)), CltvExpiry(300), TestConstants.emptyOnionPacket)
        // htlc3 and htlc4 are dust htlcs IN/OUT htlcs, with an amount large enough to be included in the commit tx, but too small to be claimed at 2nd stage
        val paymentPreimage3 = ByteVector32("05".repeat(32))
        val htlc3 = UpdateAddHtlc(
            ByteVector32.Zeroes,
            2,
            (localDustLimit + weight2fee(feerate, Commitments.HTLC_TIMEOUT_WEIGHT)).toMilliSatoshi(),
            ByteVector32(sha256(paymentPreimage3)),
            CltvExpiry(300),
            TestConstants.emptyOnionPacket
        )
        val paymentPreimage4 = ByteVector32("06".repeat(32))
        val htlc4 = UpdateAddHtlc(
            ByteVector32.Zeroes,
            3,
            (localDustLimit + weight2fee(feerate, Commitments.HTLC_SUCCESS_WEIGHT)).toMilliSatoshi(),
            ByteVector32(sha256(paymentPreimage4)),
            CltvExpiry(300),
            TestConstants.emptyOnionPacket
        )
        val spec = CommitmentSpec(
            htlcs = setOf(
                OutgoingHtlc(htlc1),
                IncomingHtlc(htlc2),
                OutgoingHtlc(htlc3),
                IncomingHtlc(htlc4)
            ),
            feerate = feerate,
            toLocal = 400.mbtc.toMilliSatoshi(),
            toRemote = 300.mbtc.toMilliSatoshi()
        )

        val outputs = makeCommitTxOutputs(
            localFundingPriv.publicKey(),
            remoteFundingPriv.publicKey(),
            true,
            localDustLimit,
            localRevocationPriv.publicKey(),
            toLocalDelay,
            localDelayedPaymentPriv.publicKey(),
            remotePaymentPriv.publicKey(),
            localHtlcPriv.publicKey(),
            remoteHtlcPriv.publicKey(),
            spec
        )

        val commitTxNumber = 0x404142434445L
        val commitTx = run {
            val txinfo = makeCommitTx(commitInput, commitTxNumber, localPaymentPriv.publicKey(), remotePaymentPriv.publicKey(), true, outputs)
            val localSig = sign(txinfo, localPaymentPriv)
            val remoteSig = sign(txinfo, remotePaymentPriv)
            addSigs(txinfo, localFundingPriv.publicKey(), remoteFundingPriv.publicKey(), localSig, remoteSig)
        }

        run {
            assertEquals(commitTxNumber, getCommitTxNumber(commitTx.tx, true, localPaymentPriv.publicKey(), remotePaymentPriv.publicKey()))
            val hash = sha256(localPaymentPriv.publicKey().value + remotePaymentPriv.publicKey().value)
            val num = Pack.int64BE(hash.takeLast(8).toByteArray()) and 0xffffffffffffL
            val check = ((commitTx.tx.txIn.first().sequence and 0xffffffL) shl 24) or (commitTx.tx.lockTime and 0xffffffL)
            assertEquals(commitTxNumber, check xor num)
        }
        val (htlcTimeoutTxs, htlcSuccessTxs) = makeHtlcTxs(commitTx.tx, localDustLimit, localRevocationPriv.publicKey(), toLocalDelay, localDelayedPaymentPriv.publicKey(), spec.feerate, outputs)

        assertEquals(2, htlcTimeoutTxs.size) // htlc1 and htlc3
        assertEquals(2, htlcSuccessTxs.size) // htlc2 and htlc4

        run {
            // either party spends local->remote htlc output with htlc timeout tx
            for (htlcTimeoutTx in htlcTimeoutTxs) {
                val localSig = sign(htlcTimeoutTx, localHtlcPriv)
                val remoteSig = sign(htlcTimeoutTx, remoteHtlcPriv, SigHash.SIGHASH_SINGLE or SigHash.SIGHASH_ANYONECANPAY)
                val signed = addSigs(htlcTimeoutTx, localSig, remoteSig)
                val csResult = checkSpendable(signed)
                assertTrue(csResult.isSuccess, "is $csResult")
            }
        }

        run {
            // local spends delayed output of htlc1 timeout tx
            val claimHtlcDelayed = makeClaimLocalDelayedOutputTx(htlcTimeoutTxs[1].tx, localDustLimit, localRevocationPriv.publicKey(), toLocalDelay, localDelayedPaymentPriv.publicKey(), finalPubKeyScript, feerate)
            assertTrue(claimHtlcDelayed is Success, "is $claimHtlcDelayed")
            val localSig = sign(claimHtlcDelayed.result, localDelayedPaymentPriv)
            val signedTx = addSigs(claimHtlcDelayed.result, localSig)
            assertTrue(checkSpendable(signedTx).isSuccess)
            // local can't claim delayed output of htlc3 timeout tx because it is below the dust limit
            val claimHtlcDelayed1 = makeClaimLocalDelayedOutputTx(htlcTimeoutTxs[0].tx, localDustLimit, localRevocationPriv.publicKey(), toLocalDelay, localPaymentPriv.publicKey(), finalPubKeyScript, feerate)
            assertEquals(Skipped(OutputNotFound), claimHtlcDelayed1)
        }

        run {
            // remote spends local->remote htlc1/htlc3 output directly in case of success
            for ((htlc, paymentPreimage) in listOf(htlc1 to paymentPreimage1, htlc3 to paymentPreimage3)) {
                val claimHtlcSuccessTx =
                    makeClaimHtlcSuccessTx(commitTx.tx, outputs, localDustLimit, remoteHtlcPriv.publicKey(), localHtlcPriv.publicKey(), localRevocationPriv.publicKey(), finalPubKeyScript, htlc, feerate)
                assertTrue(claimHtlcSuccessTx is Success, "is $claimHtlcSuccessTx")
                val localSig = sign(claimHtlcSuccessTx.result, remoteHtlcPriv)
                val signed = addSigs(claimHtlcSuccessTx.result, localSig, paymentPreimage)
                val csResult = checkSpendable(signed)
                assertTrue(csResult.isSuccess, "is $csResult")
            }
        }

        run {
            // local spends remote->local htlc2/htlc4 output with htlc success tx using payment preimage
            for ((htlcSuccessTx, paymentPreimage) in listOf(htlcSuccessTxs[1] to paymentPreimage2, htlcSuccessTxs[0] to paymentPreimage4)) {
                val localSig = sign(htlcSuccessTx, localHtlcPriv)
                val remoteSig = sign(htlcSuccessTx, remoteHtlcPriv, SigHash.SIGHASH_SINGLE or SigHash.SIGHASH_ANYONECANPAY)
                val signedTx = addSigs(htlcSuccessTx, localSig, remoteSig, paymentPreimage)
                val csResult = checkSpendable(signedTx)
                assertTrue(csResult.isSuccess, "is $csResult")
                // check remote sig
                assertTrue(checkSig(htlcSuccessTx, remoteSig, remoteHtlcPriv.publicKey(), SigHash.SIGHASH_SINGLE or SigHash.SIGHASH_ANYONECANPAY))
            }
        }

        run {
            // local spends delayed output of htlc2 success tx
            val claimHtlcDelayed = makeClaimLocalDelayedOutputTx(htlcSuccessTxs[1].tx, localDustLimit, localRevocationPriv.publicKey(), toLocalDelay, localDelayedPaymentPriv.publicKey(), finalPubKeyScript, feerate)
            assertTrue(claimHtlcDelayed is Success, "is $claimHtlcDelayed")
            val localSig = sign(claimHtlcDelayed.result, localDelayedPaymentPriv)
            val signedTx = addSigs(claimHtlcDelayed.result, localSig)
            val csResult = checkSpendable(signedTx)
            assertTrue(csResult.isSuccess, "is $csResult")
            // local can't claim delayed output of htlc4 timeout tx because it is below the dust limit
            val claimHtlcDelayed1 = makeClaimLocalDelayedOutputTx(htlcSuccessTxs[0].tx, localDustLimit, localRevocationPriv.publicKey(), toLocalDelay, localDelayedPaymentPriv.publicKey(), finalPubKeyScript, feerate)
            assertEquals(Skipped(AmountBelowDustLimit), claimHtlcDelayed1)
        }

        run {
            // remote spends main output
            val claimP2WPKHOutputTx = makeClaimRemoteDelayedOutputTx(commitTx.tx, localDustLimit, remotePaymentPriv.publicKey(), finalPubKeyScript.toByteVector(), feerate)
            assertTrue(claimP2WPKHOutputTx is Success, "is $claimP2WPKHOutputTx")
            val localSig = sign(claimP2WPKHOutputTx.result, remotePaymentPriv)
            val signedTx = addSigs(claimP2WPKHOutputTx.result, localSig)
            val csResult = checkSpendable(signedTx)
            assertTrue(csResult.isSuccess, "is $csResult")
        }

        run {
            // remote spends remote->local htlc output directly in case of timeout
            val claimHtlcTimeoutTx =
                makeClaimHtlcTimeoutTx(commitTx.tx, outputs, localDustLimit, remoteHtlcPriv.publicKey(), localHtlcPriv.publicKey(), localRevocationPriv.publicKey(), finalPubKeyScript, htlc2, feerate)
            assertTrue(claimHtlcTimeoutTx is Success, "is $claimHtlcTimeoutTx")
            val remoteSig = sign(claimHtlcTimeoutTx.result, remoteHtlcPriv)
            val signed = addSigs(claimHtlcTimeoutTx.result, remoteSig)
            val csResult = checkSpendable(signed)
            assertTrue(csResult.isSuccess, "is $csResult")
        }

        run {
            // remote spends offered HTLC output with revocation key
            val script = write(htlcOffered(localHtlcPriv.publicKey(), remoteHtlcPriv.publicKey(), localRevocationPriv.publicKey(), ripemd160(htlc1.paymentHash)))
            val htlcOutputIndex = outputs.indexOfFirst {
                val outHtlc = (it.commitmentOutput as? OutHtlc)?.outgoingHtlc?.add
                outHtlc != null && outHtlc.id == htlc1.id
            }
            val htlcPenaltyTx = makeHtlcPenaltyTx(commitTx.tx, htlcOutputIndex, script, localDustLimit, finalPubKeyScript, feerate)
            assertTrue(htlcPenaltyTx is Success, "is $htlcPenaltyTx")
            val sig = sign(htlcPenaltyTx.result, localRevocationPriv)
            val signed = addSigs(htlcPenaltyTx.result, sig, localRevocationPriv.publicKey())
            val csResult = checkSpendable(signed)
            assertTrue(csResult.isSuccess, "is $csResult")
        }

        run {
            // remote spends received HTLC output with revocation key
            val script = write(htlcReceived(localHtlcPriv.publicKey(), remoteHtlcPriv.publicKey(), localRevocationPriv.publicKey(), ripemd160(htlc2.paymentHash), htlc2.cltvExpiry))
            val htlcOutputIndex = outputs.indexOfFirst {
                val inHtlc = (it.commitmentOutput as? CommitmentOutput.InHtlc)?.incomingHtlc?.add
                inHtlc != null && inHtlc.id == htlc2.id
            }
            val htlcPenaltyTx = makeHtlcPenaltyTx(commitTx.tx, htlcOutputIndex, script, localDustLimit, finalPubKeyScript, feerate)
            assertTrue(htlcPenaltyTx is Success, "is $htlcPenaltyTx")
            val sig = sign(htlcPenaltyTx.result, localRevocationPriv)
            val signed = addSigs(htlcPenaltyTx.result, sig, localRevocationPriv.publicKey())
            val csResult = checkSpendable(signed)
            assertTrue(csResult.isSuccess, "is $csResult")
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
        val commitInput = Funding.makeFundingInputInfo(ByteVector32.fromValidHex("a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0"), 0, 1.btc, localFundingPriv.publicKey(), remoteFundingPriv.publicKey())

        // htlc1 and htlc2 are two regular incoming HTLCs with different amounts.
        // htlc2 and htlc3 have the same amounts and should be sorted according to their scriptPubKey
        // htlc4 is identical to htlc3 and htlc5 has same payment_hash/amount but different CLTV
        val paymentPreimage1 = ByteVector32.fromValidHex("1111111111111111111111111111111111111111111111111111111111111111")
        val paymentPreimage2 = ByteVector32.fromValidHex("2222222222222222222222222222222222222222222222222222222222222222")
        val paymentPreimage3 = ByteVector32.fromValidHex("3333333333333333333333333333333333333333333333333333333333333333")
        val htlc1 = UpdateAddHtlc(randomBytes32(), 1, 100.mbtc.toMilliSatoshi(), ByteVector32(sha256(paymentPreimage1)), CltvExpiry(300), TestConstants.emptyOnionPacket)
        val htlc2 = UpdateAddHtlc(randomBytes32(), 2, 200.mbtc.toMilliSatoshi(), ByteVector32(sha256(paymentPreimage2)), CltvExpiry(300), TestConstants.emptyOnionPacket)
        val htlc3 = UpdateAddHtlc(randomBytes32(), 3, 200.mbtc.toMilliSatoshi(), ByteVector32(sha256(paymentPreimage3)), CltvExpiry(300), TestConstants.emptyOnionPacket)
        val htlc4 = UpdateAddHtlc(randomBytes32(), 4, 200.mbtc.toMilliSatoshi(), ByteVector32(sha256(paymentPreimage3)), CltvExpiry(300), TestConstants.emptyOnionPacket)
        val htlc5 = UpdateAddHtlc(randomBytes32(), 5, 200.mbtc.toMilliSatoshi(), ByteVector32(sha256(paymentPreimage3)), CltvExpiry(301), TestConstants.emptyOnionPacket)

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
        val (commitTx, outputs) = run {
            val outputs =
                makeCommitTxOutputs(
                    localFundingPriv.publicKey(),
                    remoteFundingPriv.publicKey(),
                    true,
                    localDustLimit,
                    localRevocationPriv.publicKey(),
                    toLocalDelay,
                    localDelayedPaymentPriv.publicKey(),
                    remotePaymentPriv.publicKey(),
                    localHtlcPriv.publicKey(),
                    remoteHtlcPriv.publicKey(),
                    spec
                )
            val txinfo = makeCommitTx(commitInput, commitTxNumber, localPaymentPriv.publicKey(), remotePaymentPriv.publicKey(), true, outputs)
            val localSig = sign(txinfo, localPaymentPriv)
            val remoteSig = sign(txinfo, remotePaymentPriv)
            Pair(addSigs(txinfo, localFundingPriv.publicKey(), remoteFundingPriv.publicKey(), localSig, remoteSig), outputs)
        }

        // htlc1 comes before htlc2 because of the smaller amount (BIP69)
        // htlc2 and htlc3 have the same amount but htlc2 comes first because its pubKeyScript is lexicographically smaller than htlc3's
        // htlc5 comes after htlc3 and htlc4 because of the higher CLTV
        val (htlcOut1, htlcOut2, htlcOut3, htlcOut4, htlcOut5) = commitTx.tx.txOut.drop(2)
        assertEquals(10_000_000.sat, htlcOut1.amount)
        for (htlcOut in listOf(htlcOut2, htlcOut3, htlcOut4, htlcOut5)) {
            assertEquals(20_000_000.sat, htlcOut.amount)
        }

        assertTrue(htlcOut4.publicKeyScript.toHex() < htlcOut5.publicKeyScript.toHex())
        assertEquals(htlcOut1.publicKeyScript, outputs.find { it.commitmentOutput == OutHtlc(OutgoingHtlc(htlc1)) }?.output?.publicKeyScript)
        assertEquals(htlcOut5.publicKeyScript, outputs.find { it.commitmentOutput == OutHtlc(OutgoingHtlc(htlc2)) }?.output?.publicKeyScript)
        assertEquals(htlcOut3.publicKeyScript, outputs.find { it.commitmentOutput == OutHtlc(OutgoingHtlc(htlc3)) }?.output?.publicKeyScript)
        assertEquals(htlcOut4.publicKeyScript, outputs.find { it.commitmentOutput == OutHtlc(OutgoingHtlc(htlc4)) }?.output?.publicKeyScript)
        assertEquals(htlcOut2.publicKeyScript, outputs.find { it.commitmentOutput == OutHtlc(OutgoingHtlc(htlc5)) }?.output?.publicKeyScript)
    }
}
