package fr.acinq.eklair.transactions

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Crypto.ripemd160
import fr.acinq.bitcoin.Crypto.sha256
import fr.acinq.bitcoin.Script.pay2wpkh
import fr.acinq.bitcoin.Script.pay2wsh
import fr.acinq.bitcoin.Script.write
import fr.acinq.eklair.CltvExpiry
import fr.acinq.eklair.CltvExpiryDelta
import fr.acinq.eklair.Eclair.MinimumFeeratePerKw
import fr.acinq.eklair.Eclair.randomBytes32
import fr.acinq.eklair.TestConstants
import fr.acinq.eklair.assertIs
import fr.acinq.eklair.channel.Helpers.Funding
import fr.acinq.eklair.transactions.Scripts.htlcOffered
import fr.acinq.eklair.transactions.Scripts.htlcReceived
import fr.acinq.eklair.transactions.Scripts.toLocalDelayed
import fr.acinq.eklair.transactions.Transactions.PlaceHolderSig
import fr.acinq.eklair.transactions.Transactions.TxGenerationSkipped.AmountBelowDustLimit
import fr.acinq.eklair.transactions.Transactions.TxGenerationSkipped.OutputNotFound
import fr.acinq.eklair.transactions.Transactions.TxResult.Skipped
import fr.acinq.eklair.transactions.Transactions.TxResult.Success
import fr.acinq.eklair.transactions.Transactions.addSigs
import fr.acinq.eklair.transactions.Transactions.checkSig
import fr.acinq.eklair.transactions.Transactions.checkSpendable
import fr.acinq.eklair.transactions.Transactions.claimHtlcDelayedWeight
import fr.acinq.eklair.transactions.Transactions.claimHtlcSuccessWeight
import fr.acinq.eklair.transactions.Transactions.claimHtlcTimeoutWeight
import fr.acinq.eklair.transactions.Transactions.claimP2WPKHOutputWeight
import fr.acinq.eklair.transactions.Transactions.commitTxFee
import fr.acinq.eklair.transactions.Transactions.decodeTxNumber
import fr.acinq.eklair.transactions.Transactions.encodeTxNumber
import fr.acinq.eklair.transactions.Transactions.getCommitTxNumber
import fr.acinq.eklair.transactions.Transactions.htlcPenaltyWeight
import fr.acinq.eklair.transactions.Transactions.htlcSuccessWeight
import fr.acinq.eklair.transactions.Transactions.htlcTimeoutWeight
import fr.acinq.eklair.transactions.Transactions.mainPenaltyWeight
import fr.acinq.eklair.transactions.Transactions.makeClaimDelayedOutputTx
import fr.acinq.eklair.transactions.Transactions.makeClaimHtlcSuccessTx
import fr.acinq.eklair.transactions.Transactions.makeClaimHtlcTimeoutTx
import fr.acinq.eklair.transactions.Transactions.makeClaimP2WPKHOutputTx
import fr.acinq.eklair.transactions.Transactions.makeCommitTx
import fr.acinq.eklair.transactions.Transactions.makeCommitTxOutputs
import fr.acinq.eklair.transactions.Transactions.makeHtlcPenaltyTx
import fr.acinq.eklair.transactions.Transactions.makeHtlcTxs
import fr.acinq.eklair.transactions.Transactions.makeMainPenaltyTx
import fr.acinq.eklair.transactions.Transactions.sign
import fr.acinq.eklair.transactions.Transactions.weight2fee
import fr.acinq.eklair.asserts.*
import fr.acinq.eklair.wire.UpdateAddHtlc
import kotlinx.serialization.InternalSerializationApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@InternalSerializationApi
class TransactionsTestsCommon {

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
    private val feeratePerKw = 22_000L

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
        repeat (1_000) {
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
        val spec = CommitmentSpec(htlcs, feeratePerKw = 5000, toLocal = 0.msat, toRemote = 0.msat)
        val fee = commitTxFee(546.sat, spec)
        assertEquals(5340.sat, fee)
    }

    @Test
    fun `check pre-computed transaction weights`() {
        val finalPubKeyScript = write(pay2wpkh(PrivateKey(randomBytes32()).publicKey()))
        val localDustLimit = 546.sat
        val toLocalDelay = CltvExpiryDelta(144)
        val feeratePerKw = MinimumFeeratePerKw.toLong()
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
            val claimHtlcDelayedTx = makeClaimDelayedOutputTx(htlcSuccessOrTimeoutTx, localDustLimit, localRevocationPriv.publicKey(), toLocalDelay, localPaymentPriv.publicKey(), finalPubKeyScript, feeratePerKw)
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
            val outputs = makeCommitTxOutputs(true, localDustLimit, localRevocationPriv.publicKey(), toLocalDelay, localDelayedPaymentPriv.publicKey(), remotePaymentPriv.publicKey(), localHtlcPriv.publicKey(), remoteHtlcPriv.publicKey(), spec)
            val pubKeyScript = write(pay2wsh(htlcOffered(localHtlcPriv.publicKey(), remoteHtlcPriv.publicKey(), localRevocationPriv.publicKey(), ripemd160(htlc.paymentHash))))
            val commitTx = Transaction(version = 0, txIn = emptyList(), txOut = listOf(TxOut(htlc.amountMsat.truncateToSatoshi(), pubKeyScript)), lockTime = 0)
            val claimHtlcSuccessTx = makeClaimHtlcSuccessTx(commitTx, outputs, localDustLimit, remoteHtlcPriv.publicKey(), localHtlcPriv.publicKey(), localRevocationPriv.publicKey(), finalPubKeyScript, htlc, feeratePerKw)
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
            val outputs = makeCommitTxOutputs(true, localDustLimit, localRevocationPriv.publicKey(), toLocalDelay, localDelayedPaymentPriv.publicKey(), remotePaymentPriv.publicKey(), localHtlcPriv.publicKey(), remoteHtlcPriv.publicKey(), spec)
            val pubKeyScript = write(pay2wsh(htlcReceived(localHtlcPriv.publicKey(), remoteHtlcPriv.publicKey(), localRevocationPriv.publicKey(), ripemd160(htlc.paymentHash), htlc.cltvExpiry)))
            val commitTx = Transaction(version = 0, txIn = emptyList(), txOut = listOf(TxOut(htlc.amountMsat.truncateToSatoshi(), pubKeyScript)), lockTime = 0)
            val claimClaimHtlcTimeoutTx = makeClaimHtlcTimeoutTx(commitTx, outputs, localDustLimit, remoteHtlcPriv.publicKey(), localHtlcPriv.publicKey(), localRevocationPriv.publicKey(), finalPubKeyScript, htlc, feeratePerKw)
            assertTrue(claimClaimHtlcTimeoutTx is Success, "is $claimClaimHtlcTimeoutTx")
            // we use dummy signatures to compute the weight
            val weight = Transaction.weight(addSigs(claimClaimHtlcTimeoutTx.result, PlaceHolderSig).tx)
            assertEquals(claimHtlcTimeoutWeight, weight)
            assertTrue(claimClaimHtlcTimeoutTx.result.fee >= claimClaimHtlcTimeoutTx.result.minRelayFee)
        }
    }

    @Test
    fun `generate valid commitment and htlc transactions`() {
        val finalPubKeyScript = write(pay2wpkh(PrivateKey(randomBytes32()).publicKey()))
        val commitInput = Funding.makeFundingInputInfo(randomBytes32(), 0, 1.btc, localFundingPriv.publicKey(), remoteFundingPriv.publicKey())

        // htlc1 and htlc2 are regular IN/OUT htlcs
        val paymentPreimage1 = randomBytes32()
        val htlc1 = UpdateAddHtlc(ByteVector32.Zeroes, 0, 100.mbtc.toMilliSatoshi(), ByteVector32(sha256(paymentPreimage1)), CltvExpiry(300), TestConstants.emptyOnionPacket)
        val paymentPreimage2 = randomBytes32()
        val htlc2 = UpdateAddHtlc(ByteVector32.Zeroes, 1, 200.mbtc.toMilliSatoshi(), ByteVector32(sha256(paymentPreimage2)), CltvExpiry(300), TestConstants.emptyOnionPacket)
        // htlc3 and htlc4 are dust htlcs IN/OUT htlcs, with an amount large enough to be included in the commit tx, but too small to be claimed at 2nd stage
        val paymentPreimage3 = randomBytes32()
        val htlc3 = UpdateAddHtlc(ByteVector32.Zeroes, 2, (localDustLimit + weight2fee(feeratePerKw, htlcTimeoutWeight)).toMilliSatoshi(), ByteVector32(sha256(paymentPreimage3)), CltvExpiry(300), TestConstants.emptyOnionPacket)
        val paymentPreimage4 = randomBytes32()
        val htlc4 = UpdateAddHtlc(ByteVector32.Zeroes, 3, (localDustLimit + weight2fee(feeratePerKw, htlcSuccessWeight)).toMilliSatoshi(), ByteVector32(sha256(paymentPreimage4)), CltvExpiry(300), TestConstants.emptyOnionPacket)
        val spec = CommitmentSpec(
            htlcs = setOf(
                OutgoingHtlc(htlc1),
                IncomingHtlc(htlc2),
                OutgoingHtlc(htlc3),
                IncomingHtlc(htlc4)
            ),
            feeratePerKw = feeratePerKw,
            toLocal = 400.mbtc.toMilliSatoshi(),
            toRemote =300.mbtc.toMilliSatoshi()
        )

        val outputs = makeCommitTxOutputs(true, localDustLimit, localRevocationPriv.publicKey(), toLocalDelay, localDelayedPaymentPriv.publicKey(), remotePaymentPriv.publicKey(), localHtlcPriv.publicKey(), remoteHtlcPriv.publicKey(), spec)

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
            val num = BtcSerializer.uint64BE(hash.takeLast(8).toByteArray()) and 0xffffffffffffL
            val check = ((commitTx.tx.txIn.first().sequence and 0xffffffL) shl 24) or (commitTx.tx.lockTime and 0xffffffL)
            assertEquals(commitTxNumber, check xor num)
        }
        val (htlcTimeoutTxs, htlcSuccessTxs) = makeHtlcTxs(commitTx.tx, localDustLimit, localRevocationPriv.publicKey(), toLocalDelay, localDelayedPaymentPriv.publicKey(), spec.feeratePerKw, outputs)

        assertEquals(2, htlcTimeoutTxs.size) // htlc1 and htlc3
        assertEquals(2, htlcSuccessTxs.size) // htlc2 and htlc4

        run {
            // either party spends local->remote htlc output with htlc timeout tx
            for (htlcTimeoutTx in htlcTimeoutTxs) {
                val localSig = sign(htlcTimeoutTx, localHtlcPriv)
                val remoteSig = sign(htlcTimeoutTx, remoteHtlcPriv)
                val signed = addSigs(htlcTimeoutTx, localSig, remoteSig)
                val csResult = checkSpendable(signed)
                assertTrue(csResult.isSuccess, "is $csResult")
            }
        }

        run {
            // local spends delayed output of htlc1 timeout tx
            val claimHtlcDelayed = makeClaimDelayedOutputTx(htlcTimeoutTxs[1].tx, localDustLimit, localRevocationPriv.publicKey(), toLocalDelay, localDelayedPaymentPriv.publicKey(), finalPubKeyScript, feeratePerKw)
            assertTrue(claimHtlcDelayed is Success, "is $claimHtlcDelayed")
            val localSig = sign(claimHtlcDelayed.result, localDelayedPaymentPriv)
            val signedTx = addSigs(claimHtlcDelayed.result, localSig)
            assertTrue(checkSpendable(signedTx).isSuccess)
            // local can't claim delayed output of htlc3 timeout tx because it is below the dust limit
            val claimHtlcDelayed1 = makeClaimDelayedOutputTx(htlcTimeoutTxs[0].tx, localDustLimit, localRevocationPriv.publicKey(), toLocalDelay, localPaymentPriv.publicKey(), finalPubKeyScript, feeratePerKw)
            assertEquals(Skipped(OutputNotFound), claimHtlcDelayed1)
        }

        run {
            // remote spends local->remote htlc1/htlc3 output directly in case of success
            for ((htlc, paymentPreimage) in listOf(htlc1 to paymentPreimage1, htlc3 to paymentPreimage3)) {
                val claimHtlcSuccessTx = makeClaimHtlcSuccessTx(commitTx.tx, outputs, localDustLimit, remoteHtlcPriv.publicKey(), localHtlcPriv.publicKey(), localRevocationPriv.publicKey(), finalPubKeyScript, htlc, feeratePerKw)
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
                val remoteSig = sign(htlcSuccessTx, remoteHtlcPriv)
                val signedTx = addSigs(htlcSuccessTx, localSig, remoteSig, paymentPreimage)
                val csResult = checkSpendable(signedTx)
                assertTrue(csResult.isSuccess, "is $csResult")
                // check remote sig
                assertTrue(checkSig(htlcSuccessTx, remoteSig, remoteHtlcPriv.publicKey()))
            }
        }

        run {
            // local spends delayed output of htlc2 success tx
            val claimHtlcDelayed = makeClaimDelayedOutputTx(htlcSuccessTxs[1].tx, localDustLimit, localRevocationPriv.publicKey(), toLocalDelay, localDelayedPaymentPriv.publicKey(), finalPubKeyScript, feeratePerKw)
            assertTrue(claimHtlcDelayed is Success, "is $claimHtlcDelayed")
            val localSig = sign(claimHtlcDelayed.result, localDelayedPaymentPriv)
            val signedTx = addSigs(claimHtlcDelayed.result, localSig)
            val csResult = checkSpendable(signedTx)
            assertTrue(csResult.isSuccess, "is $csResult")
            // local can't claim delayed output of htlc4 timeout tx because it is below the dust limit
            val claimHtlcDelayed1 = makeClaimDelayedOutputTx(htlcSuccessTxs[0].tx, localDustLimit, localRevocationPriv.publicKey(), toLocalDelay, localDelayedPaymentPriv.publicKey(), finalPubKeyScript, feeratePerKw)
            assertEquals(Skipped(AmountBelowDustLimit), claimHtlcDelayed1)
        }

        run {
            // remote spends main output
            val claimP2WPKHOutputTx = makeClaimP2WPKHOutputTx(commitTx.tx, localDustLimit, remotePaymentPriv.publicKey(), finalPubKeyScript, feeratePerKw)
            assertTrue(claimP2WPKHOutputTx is Success, "is $claimP2WPKHOutputTx")
            val localSig = sign(claimP2WPKHOutputTx.result, remotePaymentPriv)
            val signedTx = addSigs(claimP2WPKHOutputTx.result, remotePaymentPriv.publicKey(), localSig)
            val csResult = checkSpendable(signedTx)
            assertTrue(csResult.isSuccess, "is $csResult")
        }

        run {
            // remote spends remote->local htlc output directly in case of timeout
            val claimHtlcTimeoutTx = makeClaimHtlcTimeoutTx(commitTx.tx, outputs, localDustLimit, remoteHtlcPriv.publicKey(), localHtlcPriv.publicKey(), localRevocationPriv.publicKey(), finalPubKeyScript, htlc2, feeratePerKw)
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
                val outHtlc = (it.commitmentOutput as? CommitmentOutput.OutHtlc)?.outgoingHtlc?.add
                outHtlc != null && outHtlc.id == htlc1.id
            }
            val htlcPenaltyTx = makeHtlcPenaltyTx(commitTx.tx, htlcOutputIndex, script, localDustLimit, finalPubKeyScript, feeratePerKw)
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
            val htlcPenaltyTx = makeHtlcPenaltyTx(commitTx.tx, htlcOutputIndex, script, localDustLimit, finalPubKeyScript, feeratePerKw)
            assertTrue(htlcPenaltyTx is Success, "is $htlcPenaltyTx")
            val sig = sign(htlcPenaltyTx.result, localRevocationPriv)
            val signed = addSigs(htlcPenaltyTx.result, sig, localRevocationPriv.publicKey())
            val csResult = checkSpendable(signed)
            assertTrue(csResult.isSuccess, "is $csResult")
        }

    }
}
