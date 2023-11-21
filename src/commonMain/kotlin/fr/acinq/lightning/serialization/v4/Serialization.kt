package fr.acinq.lightning.serialization.v4

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Output
import fr.acinq.bitcoin.musig2.PublicNonce
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.channel.states.*
import fr.acinq.lightning.transactions.*
import fr.acinq.lightning.transactions.Transactions.TransactionWithInputInfo.*
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.wire.LightningCodecs
import fr.acinq.lightning.wire.LightningMessage
import fr.acinq.lightning.wire.LiquidityAds

/**
 * Serialization for [ChannelStateWithCommitments].
 *
 * This is a large file, but it's just repetition of the same pattern
 *
 * We define extension methods on the class [Output] to serialize various types
 * from primitives to structures:
 * ```
 *  private fun Output.write(o: MyType): Unit
 * ```
 *
 * Then we just iterate over all fields of the top level class we want to serialize, and call the
 * write method on all of them.
 *
 * We defer serialization of Bitcoin and Lightning protocol messages to the respective [BtcSerializer]
 * and [LightningCodecs].
 */
object Serialization {

    const val versionMagic = 4

    fun serialize(o: PersistedChannelState): ByteArray {
        val out = ByteArrayOutput()
        out.write(versionMagic)
        out.writePersistedChannelState(o)
        return out.toByteArray()
    }

    private fun Output.writePersistedChannelState(o: PersistedChannelState) = when (o) {
        is LegacyWaitForFundingConfirmed -> {
            write(0x08); writeLegacyWaitForFundingConfirmed(o)
        }
        is LegacyWaitForFundingLocked -> {
            write(0x09); writeLegacyWaitForFundingLocked(o)
        }
        is WaitForFundingConfirmed -> {
            write(0x00); writeWaitForFundingConfirmed(o)
        }
        is WaitForChannelReady -> {
            write(0x01); writeWaitForChannelReady(o)
        }
        is Normal -> {
            write(0x02); writeNormal(o)
        }
        is ShuttingDown -> {
            write(0x03); writeShuttingDown(o)
        }
        is Negotiating -> {
            write(0x04); writeNegotiating(o)
        }
        is Closing -> {
            write(0x05); writeClosing(o)
        }
        is WaitForRemotePublishFutureCommitment -> {
            write(0x06); writeWaitForRemotePublishFutureCommitment(o)
        }
        is Closed -> {
            write(0x07); writeClosed(o)
        }
        is WaitForFundingSigned -> {
            write(0x0a); writeWaitForFundingSigned(o)
        }
    }

    private fun Output.writeLegacyWaitForFundingConfirmed(o: LegacyWaitForFundingConfirmed) = o.run {
        writeCommitments(commitments)
        writeNullable(fundingTx) { writeBtcObject(it) }
        writeNumber(waitingSinceBlock)
        writeNullable(deferred) { writeLightningMessage(it) }
        writeEither(lastSent,
            writeLeft = { writeLightningMessage(it) },
            writeRight = { writeLightningMessage(it) }
        )
    }

    private fun Output.writeLegacyWaitForFundingLocked(o: LegacyWaitForFundingLocked) = o.run {
        writeCommitments(commitments)
        writeNumber(shortChannelId.toLong())
        writeLightningMessage(lastSent)
    }

    private fun Output.writeWaitForFundingSigned(o: WaitForFundingSigned) = o.run {
        writeChannelParams(channelParams)
        writeInteractiveTxSigningSession(signingSession)
        writeNumber(localPushAmount.toLong())
        writeNumber(remotePushAmount.toLong())
        writePublicKey(remoteSecondPerCommitmentPoint)
        writeNullable(channelOrigin) { writeChannelOrigin(it) }
    }

    private fun Output.writeWaitForFundingConfirmed(o: WaitForFundingConfirmed) = o.run {
        writeCommitments(commitments)
        writeNumber(localPushAmount.toLong())
        writeNumber(remotePushAmount.toLong())
        writeNumber(waitingSinceBlock)
        writeNullable(deferred) { writeLightningMessage(it) }
        when (rbfStatus) {
            is RbfStatus.WaitingForSigs -> {
                write(0x01)
                writeInteractiveTxSigningSession(rbfStatus.session)
            }
            else -> {
                write(0x00)
            }
        }
    }

    private fun Output.writeWaitForChannelReady(o: WaitForChannelReady) = o.run {
        writeCommitments(commitments)
        writeNumber(shortChannelId.toLong())
        writeLightningMessage(lastSent)
    }

    private fun Output.writeNormal(o: Normal) = o.run {
        writeCommitments(commitments)
        writeNumber(shortChannelId.toLong())
        writeLightningMessage(channelUpdate)
        writeNullable(remoteChannelUpdate) { writeLightningMessage(it) }
        writeNullable(localShutdown) { writeLightningMessage(it) }
        writeNullable(remoteShutdown) { writeLightningMessage(it) }
        writeNullable(closingFeerates) { writeClosingFeerates(it) }
        when (spliceStatus) {
            is SpliceStatus.WaitingForSigs -> {
                write(0x01)
                writeInteractiveTxSigningSession(spliceStatus.session)
                writeCollection(spliceStatus.origins) { writeChannelOrigin(it) }
            }
            else -> {
                write(0x00)
            }
        }
        write(0x01)
        writeCollection(liquidityLeases) { writeLiquidityLease(it) }
    }

    private fun Output.writeShuttingDown(o: ShuttingDown) = o.run {
        writeCommitments(commitments)
        writeLightningMessage(localShutdown)
        writeLightningMessage(remoteShutdown)
        writeNullable(closingFeerates) { writeClosingFeerates(it) }
    }

    private fun Output.writeNegotiating(o: Negotiating) = o.run {
        writeCommitments(commitments)
        writeLightningMessage(localShutdown)
        writeLightningMessage(remoteShutdown)
        writeCollection(closingTxProposed) {
            writeCollection(it) { closingTxProposed ->
                closingTxProposed.run {
                    writeTransactionWithInputInfo(unsignedTx)
                    writeLightningMessage(localClosingSigned)
                }
            }
        }
        writeNullable(bestUnpublishedClosingTx) { writeTransactionWithInputInfo(it) }
        writeNullable(closingFeerates) { writeClosingFeerates(it) }
    }

    private fun Output.writeClosing(o: Closing) = o.run {
        writeCommitments(commitments)
        writeNumber(waitingSinceBlock)
        writeCollection(mutualCloseProposed) { writeTransactionWithInputInfo(it) }
        writeCollection(mutualClosePublished) { writeTransactionWithInputInfo(it) }
        writeNullable(localCommitPublished) { writeLocalCommitPublished(it) }
        writeNullable(remoteCommitPublished) { writeRemoteCommitPublished(it) }
        writeNullable(nextRemoteCommitPublished) { writeRemoteCommitPublished(it) }
        writeNullable(futureRemoteCommitPublished) { writeRemoteCommitPublished(it) }
        writeCollection(revokedCommitPublished) { writeRevokedCommitPublished(it) }
    }

    private fun Output.writeLocalCommitPublished(o: LocalCommitPublished) = o.run {
        writeBtcObject(commitTx)
        writeNullable(claimMainDelayedOutputTx) { writeTransactionWithInputInfo(it) }
        writeCollection(htlcTxs.entries) {
            writeBtcObject(it.key)
            writeNullable(it.value) { htlcTx -> writeTransactionWithInputInfo(htlcTx) }
        }
        writeCollection(claimHtlcDelayedTxs) { writeTransactionWithInputInfo(it) }
        writeCollection(claimAnchorTxs) { writeTransactionWithInputInfo(it) }
        writeIrrevocablySpent(irrevocablySpent)
    }

    private fun Output.writeRemoteCommitPublished(o: RemoteCommitPublished) = o.run {
        writeBtcObject(commitTx)
        writeNullable(claimMainOutputTx) { writeTransactionWithInputInfo(it) }
        writeCollection(claimHtlcTxs.entries) {
            writeBtcObject(it.key)
            writeNullable(it.value) { claimHtlcTx -> writeTransactionWithInputInfo(claimHtlcTx) }
        }
        writeCollection(claimAnchorTxs) { writeTransactionWithInputInfo(it) }
        writeIrrevocablySpent(irrevocablySpent)
    }

    private fun Output.writeRevokedCommitPublished(o: RevokedCommitPublished) = o.run {
        writeBtcObject(commitTx)
        writeByteVector32(remotePerCommitmentSecret.value)
        writeNullable(claimMainOutputTx) { writeTransactionWithInputInfo(it) }
        writeNullable(mainPenaltyTx) { writeTransactionWithInputInfo(it) }
        writeCollection(htlcPenaltyTxs) { writeTransactionWithInputInfo(it) }
        writeCollection(claimHtlcDelayedPenaltyTxs) { writeTransactionWithInputInfo(it) }
        writeIrrevocablySpent(irrevocablySpent)
    }

    private fun Output.writeIrrevocablySpent(o: Map<OutPoint, Transaction>) = writeCollection(o.entries) {
        writeBtcObject(it.key)
        writeBtcObject(it.value)
    }

    private fun Output.writeWaitForRemotePublishFutureCommitment(o: WaitForRemotePublishFutureCommitment) = o.run {
        writeCommitments(commitments)
        writeLightningMessage(remoteChannelReestablish)
    }

    private fun Output.writeClosed(o: Closed) = o.run {
        writeClosing(state)
    }

    private fun Output.writeSharedFundingInput(i: SharedFundingInput) = when (i) {
        is SharedFundingInput.Multisig2of2 -> {
            write(0x01)
            writeInputInfo(i.info)
            writeNumber(i.fundingTxIndex)
            writePublicKey(i.remoteFundingPubkey)
        }
    }

    private fun Output.writeInteractiveTxParams(o: InteractiveTxParams) = o.run {
        writeByteVector32(channelId)
        writeBoolean(isInitiator)
        writeNumber(localContribution.toLong())
        writeNumber(remoteContribution.toLong())
        writeNullable(sharedInput) { writeSharedFundingInput(it) }
        writePublicKey(remoteFundingPubkey)
        writeCollection(localOutputs) { writeBtcObject(it) }
        writeNumber(lockTime)
        writeNumber(dustLimit.toLong())
        writeNumber(targetFeerate.toLong())
    }

    private fun Output.writeSharedInteractiveTxInput(i: InteractiveTxInput.Shared) = i.run {
        write(0x02)
        writeNumber(serialId)
        writeBtcObject(outPoint)
        writeBtcObject(txOut)
        writeNumber(sequence.toLong())
        writeNumber(localAmount.toLong())
        writeNumber(remoteAmount.toLong())
    }

    private fun Output.writeLocalInteractiveTxInput(i: InteractiveTxInput.Local) = when (i) {
        is InteractiveTxInput.LocalOnly -> i.run {
            write(0x01)
            writeNumber(serialId)
            writeBtcObject(previousTx)
            writeNumber(previousTxOutput)
            writeNumber(sequence.toLong())
        }
        is InteractiveTxInput.LocalSwapIn -> i.run {
            write(0x02)
            writeNumber(serialId)
            writeBtcObject(previousTx)
            writeNumber(previousTxOutput)
            writeNumber(sequence.toLong())
            swapInParams.write(this@writeLocalInteractiveTxInput)
        }
        is InteractiveTxInput.LocalMusig2SwapIn -> i.run {
            write(0x03)
            writeNumber(serialId)
            writeBtcObject(previousTx)
            writeNumber(previousTxOutput)
            writeNumber(sequence.toLong())
            swapInParams.write(this@writeLocalInteractiveTxInput)
        }
    }

    private fun Output.writeRemoteInteractiveTxInput(i: InteractiveTxInput.Remote) = when (i) {
        is InteractiveTxInput.RemoteOnly -> i.run {
            write(0x01)
            writeNumber(serialId)
            writeBtcObject(outPoint)
            writeBtcObject(txOut)
            writeNumber(sequence.toLong())
        }
        is InteractiveTxInput.RemoteSwapIn -> i.run {
            write(0x02)
            writeNumber(serialId)
            writeBtcObject(outPoint)
            writeBtcObject(txOut)
            writeNumber(sequence.toLong())
            swapInParams.write(this@writeRemoteInteractiveTxInput)
        }
        is InteractiveTxInput.RemoteSwapInMusig2 -> i.run {
            write(0x03)
            writeNumber(serialId)
            writeBtcObject(outPoint)
            writeBtcObject(txOut)
            writeNumber(sequence.toLong())
            swapInParams.write(this@writeRemoteInteractiveTxInput)
        }
    }

    private fun Output.writeSharedInteractiveTxOutput(o: InteractiveTxOutput.Shared) = o.run {
        write(0x02)
        writeNumber(serialId)
        writeDelimited(pubkeyScript.toByteArray())
        writeNumber(localAmount.toLong())
        writeNumber(remoteAmount.toLong())
        writeNumber(htlcAmount.toLong())
    }

    private fun Output.writeLocalInteractiveTxOutput(o: InteractiveTxOutput.Local) = when (o) {
        is InteractiveTxOutput.Local.Change -> o.run {
            write(0x01)
            writeNumber(serialId)
            writeNumber(amount.toLong())
            writeDelimited(pubkeyScript.toByteArray())
        }
        is InteractiveTxOutput.Local.NonChange -> o.run {
            write(0x02)
            writeNumber(serialId)
            writeNumber(amount.toLong())
            writeDelimited(pubkeyScript.toByteArray())
        }
    }

    private fun Output.writeRemoteInteractiveTxOutput(o: InteractiveTxOutput.Remote) = o.run {
        write(0x01)
        writeNumber(serialId)
        writeNumber(amount.toLong())
        writeDelimited(pubkeyScript.toByteArray())
    }

    private fun Output.writeSharedTransaction(tx: SharedTransaction) = tx.run {
        writeNullable(sharedInput) { writeSharedInteractiveTxInput(it) }
        writeSharedInteractiveTxOutput(sharedOutput)
        writeCollection(localInputs) { writeLocalInteractiveTxInput(it) }
        writeCollection(remoteInputs) { writeRemoteInteractiveTxInput(it) }
        writeCollection(localOutputs) { writeLocalInteractiveTxOutput(it) }
        writeCollection(remoteOutputs) { writeRemoteInteractiveTxOutput(it) }
        writeNumber(lockTime)
    }

    private fun Output.writeScriptWitness(w: ScriptWitness) = writeCollection(w.stack) { writeDelimited(it.toByteArray()) }

    private fun Output.writeSignedSharedTransaction(o: SignedSharedTransaction) = when (o) {
        is PartiallySignedSharedTransaction -> o.run {
            write(0x01)
            writeSharedTransaction(tx)
            writeLightningMessage(localSigs)
        }
        is FullySignedSharedTransaction -> o.run {
            write(0x02)
            writeSharedTransaction(tx)
            writeLightningMessage(localSigs)
            writeLightningMessage(remoteSigs)
            writeNullable(sharedSigs) { writeScriptWitness(it) }
        }
    }

    private fun Output.writeUnsignedLocalCommitWithHtlcs(localCommit: InteractiveTxSigningSession.Companion.UnsignedLocalCommit) {
        writeNumber(localCommit.index)
        writeCommitmentSpecWithHtlcs(localCommit.spec)
        writeTransactionWithInputInfo(localCommit.commitTx)
        writeCollection(localCommit.htlcTxs) { writeTransactionWithInputInfo(it) }
    }

    private fun Output.writeLocalCommitWithHtlcs(localCommit: LocalCommit) {
        writeNumber(localCommit.index)
        writeCommitmentSpecWithHtlcs(localCommit.spec)
        localCommit.publishableTxs.run {
            writeTransactionWithInputInfo(commitTx)
            writeCollection(htlcTxsAndSigs) { htlc ->
                writeTransactionWithInputInfo(htlc.txinfo)
                writeByteVector64(htlc.localSig)
                writeByteVector64(htlc.remoteSig)
            }
        }
    }

    private fun Output.writeLiquidityLease(lease: LiquidityAds.Lease) {
        writeNumber(lease.amount.toLong())
        writeNumber(lease.fees.miningFee.toLong())
        writeNumber(lease.fees.serviceFee.toLong())
        writeByteVector64(lease.sellerSig)
        writeNumber(lease.witness.fundingScript.size())
        write(lease.witness.fundingScript.toByteArray())
        writeNumber(lease.witness.leaseDuration)
        writeNumber(lease.witness.leaseEnd)
        writeNumber(lease.witness.maxRelayFeeProportional)
        writeNumber(lease.witness.maxRelayFeeBase.toLong())
    }

    private fun Output.writeInteractiveTxSigningSession(s: InteractiveTxSigningSession) = s.run {
        writeInteractiveTxParams(fundingParams)
        writeNumber(s.fundingTxIndex)
        writeSignedSharedTransaction(fundingTx)
        // The liquidity purchase field was added afterwards. For backwards-compatibility, we extend the discriminator
        // we previously used for the local commit to insert the liquidity purchase if available.
        // Note that we don't bother removing the duplication across HTLCs in the local commit: this is a short-lived
        // state during which the channel cannot be used for payments.
        when (liquidityLease) {
            // Before introducing the liquidity purchase field, we serialized the local commit as an Either, with
            // discriminators 0 and 1.
            null -> when (localCommit) {
                is Either.Left -> {
                    write(0)
                    writeUnsignedLocalCommitWithHtlcs(localCommit.value)
                }
                is Either.Right -> {
                    write(1)
                    writeLocalCommitWithHtlcs(localCommit.value)
                }
            }
            else -> when (localCommit) {
                is Either.Left -> {
                    write(2)
                    writeLiquidityLease(liquidityLease)
                    writeUnsignedLocalCommitWithHtlcs(localCommit.value)
                }
                is Either.Right -> {
                    write(3)
                    writeLiquidityLease(liquidityLease)
                    writeLocalCommitWithHtlcs(localCommit.value)
                }
            }
        }
        remoteCommit.run {
            writeNumber(index)
            writeCommitmentSpecWithHtlcs(spec)
            writeTxId(txid)
            writePublicKey(remotePerCommitmentPoint)
        }
    }

    private fun Output.writeChannelOrigin(o: Origin) = when (o) {
        is Origin.PayToOpenOrigin -> {
            write(0x01)
            writeByteVector32(o.paymentHash)
            writeNumber(o.serviceFee.toLong())
            writeNumber(o.miningFee.toLong())
            writeNumber(o.amount.toLong())
        }
        is Origin.PleaseOpenChannelOrigin -> {
            write(0x02)
            writeByteVector32(o.requestId)
            writeNumber(o.serviceFee.toLong())
            writeNumber(o.miningFee.toLong())
            writeNumber(o.amount.toLong())
        }
    }

    private fun Output.writeChannelParams(o: ChannelParams) = o.run {
        writeByteVector32(channelId)
        writeDelimited(channelConfig.toByteArray())
        writeDelimited(Features(channelFeatures.features.associateWith { FeatureSupport.Mandatory }).toByteArray())
        localParams.run {
            writePublicKey(nodeId)
            writeCollection(fundingKeyPath.path) { writeNumber(it) }
            writeNumber(dustLimit.toLong())
            writeNumber(maxHtlcValueInFlightMsat)
            writeNumber(htlcMinimum.toLong())
            writeNumber(toSelfDelay.toLong())
            writeNumber(maxAcceptedHtlcs)
            writeBoolean(isInitiator)
            writeDelimited(defaultFinalScriptPubKey.toByteArray())
            writeDelimited(features.toByteArray())
        }
        remoteParams.run {
            writePublicKey(nodeId)
            writeNumber(dustLimit.toLong())
            writeNumber(maxHtlcValueInFlightMsat)
            writeNumber(htlcMinimum.toLong())
            writeNumber(toSelfDelay.toLong())
            writeNumber(maxAcceptedHtlcs)
            writePublicKey(revocationBasepoint)
            writePublicKey(paymentBasepoint)
            writePublicKey(delayedPaymentBasepoint)
            writePublicKey(htlcBasepoint)
            writeDelimited(features.toByteArray())
        }
        writeNumber(channelFlags)
    }

    private fun Output.writeCommitmentChanges(o: CommitmentChanges) = o.run {
        localChanges.run {
            writeCollection(proposed) { writeLightningMessage(it) }
            writeCollection(signed) { writeLightningMessage(it) }
            writeCollection(acked) { writeLightningMessage(it) }
        }
        remoteChanges.run {
            writeCollection(proposed) { writeLightningMessage(it) }
            writeCollection(acked) { writeLightningMessage(it) }
            writeCollection(signed) { writeLightningMessage(it) }
        }
        writeNumber(localNextHtlcId)
        writeNumber(remoteNextHtlcId)
    }

    private fun Output.writeCommitment(o: Commitment) = o.run {
        writeNumber(fundingTxIndex)
        writePublicKey(remoteFundingPubkey)
        when (localFundingStatus) {
            is LocalFundingStatus.UnconfirmedFundingTx -> {
                write(0x00)
                writeSignedSharedTransaction(localFundingStatus.sharedTx)
                writeInteractiveTxParams(localFundingStatus.fundingParams)
                writeNumber(localFundingStatus.createdAt)
            }
            is LocalFundingStatus.ConfirmedFundingTx -> {
                write(0x02)
                writeBtcObject(localFundingStatus.signedTx)
                writeNumber(localFundingStatus.fee.toLong())
                writeLightningMessage(localFundingStatus.localSigs)
            }
        }
        when (remoteFundingStatus) {
            is RemoteFundingStatus.NotLocked -> write(0x00)
            is RemoteFundingStatus.Locked -> write(0x01)
        }
        localCommit.run {
            writeNumber(index)
            writeCommitmentSpecWithoutHtlcs(spec)
            publishableTxs.run {
                writeTransactionWithInputInfo(commitTx)
                writeCollection(htlcTxsAndSigs) { htlc ->
                    writeTransactionWithInputInfo(htlc.txinfo)
                    writeByteVector64(htlc.localSig)
                    writeByteVector64(htlc.remoteSig)
                }
            }
        }
        remoteCommit.run {
            writeNumber(index)
            writeCommitmentSpecWithoutHtlcs(spec)
            writeTxId(txid)
            writePublicKey(remotePerCommitmentPoint)
        }
        writeNullable(nextRemoteCommit) {
            writeLightningMessage(it.sig)
            writeNumber(it.commit.index)
            writeCommitmentSpecWithoutHtlcs(it.commit.spec)
            writeTxId(it.commit.txid)
            writePublicKey(it.commit.remotePerCommitmentPoint)
        }
    }

    private fun Output.writeCommitments(o: Commitments) = o.run {
        writeChannelParams(params)
        writeCommitmentChanges(changes)
        // When multiple commitments are active, htlcs are shared between all of these commitments, so we serialize them separately.
        // The direction we use is from our local point of view: we use sets, which deduplicates htlcs that are in both local and remote commitments.
        val htlcs = buildSet {
            // All active commitments have the same htlc set, so we only consider the first one
            addAll(active.first().localCommit.spec.htlcs)
            addAll(active.first().remoteCommit.spec.htlcs.map { htlc -> htlc.opposite() })
            active.first().nextRemoteCommit?.let { addAll(it.commit.spec.htlcs.map { htlc -> htlc.opposite() }) }
            // Each inactive commitment may have a distinct htlc set
            inactive.forEach { c ->
                addAll(c.localCommit.spec.htlcs)
                addAll(c.remoteCommit.spec.htlcs.map { htlc -> htlc.opposite() })
                c.nextRemoteCommit?.let { addAll(it.commit.spec.htlcs.map { htlc -> htlc.opposite() }) }
            }
        }
        writeCollection(htlcs) { writeDirectedHtlc(it) }
        writeCollection(active) { writeCommitment(it) }
        writeCollection(inactive) { writeCommitment(it) }
        writeCollection(payments.entries) { entry ->
            writeNumber(entry.key)
            writeString(entry.value.toString())
        }
        writeEither(remoteNextCommitInfo,
            writeLeft = { writeNumber(it.sentAfterLocalCommitIndex) },
            writeRight = { writePublicKey(it) }
        )
        remotePerCommitmentSecrets.run {
            writeCollection(knownHashes.entries) { entry ->
                writeCollection(entry.key) { writeBoolean(it) }
                writeByteVector32(entry.value)
            }
            writeNullable(lastIndex) { writeNumber(it) }
        }
        writeDelimited(remoteChannelData.data.toByteArray())
    }

    private fun Output.writeDirectedHtlc(htlc: DirectedHtlc) = htlc.run {
        when (htlc) {
            is IncomingHtlc -> write(0)
            is OutgoingHtlc -> write(1)
        }
        writeLightningMessage(add)
    }

    private fun Output.writeCommitmentSpecWithHtlcs(spec: CommitmentSpec) = spec.run {
        writeCollection(htlcs) { writeDirectedHtlc(it) }
        writeNumber(feerate.toLong())
        writeNumber(toLocal.toLong())
        writeNumber(toRemote.toLong())
    }

    private fun Output.writeCommitmentSpecWithoutHtlcs(spec: CommitmentSpec) = spec.run {
        writeCollection(htlcs) {
            when (it) {
                is IncomingHtlc -> write(0)
                is OutgoingHtlc -> write(1)
            }
            // To avoid duplication, HTLCs are serialized separately.
            writeNumber(it.add.id)
        }
        writeNumber(feerate.toLong())
        writeNumber(toLocal.toLong())
        writeNumber(toRemote.toLong())
    }

    private fun Output.writeInputInfo(o: Transactions.InputInfo): Unit = o.run {
        writeBtcObject(outPoint)
        writeBtcObject(txOut)
        writeDelimited(redeemScript.toByteArray())
    }

    private fun Output.writeTransactionWithInputInfo(o: Transactions.TransactionWithInputInfo) {
        when (o) {
            is CommitTx -> {
                write(0x00); writeInputInfo(o.input); writeBtcObject(o.tx)
            }
            is HtlcTx.HtlcSuccessTx -> {
                write(0x01); writeInputInfo(o.input); writeBtcObject(o.tx); writeByteVector32(o.paymentHash); writeNumber(o.htlcId)
            }
            is HtlcTx.HtlcTimeoutTx -> {
                write(0x02); writeInputInfo(o.input); writeBtcObject(o.tx); writeNumber(o.htlcId)
            }
            is ClaimHtlcTx.ClaimHtlcSuccessTx -> {
                write(0x03); writeInputInfo(o.input); writeBtcObject(o.tx); writeNumber(o.htlcId)
            }
            is ClaimHtlcTx.ClaimHtlcTimeoutTx -> {
                write(0x04); writeInputInfo(o.input); writeBtcObject(o.tx); writeNumber(o.htlcId)
            }
            is ClaimAnchorOutputTx.ClaimLocalAnchorOutputTx -> {
                write(0x05); writeInputInfo(o.input); writeBtcObject(o.tx)
            }
            is ClaimAnchorOutputTx.ClaimRemoteAnchorOutputTx -> {
                write(0x06); writeInputInfo(o.input); writeBtcObject(o.tx)
            }
            is ClaimLocalDelayedOutputTx -> {
                write(0x07); writeInputInfo(o.input); writeBtcObject(o.tx)
            }
            is ClaimRemoteCommitMainOutputTx.ClaimRemoteDelayedOutputTx -> {
                write(0x09); writeInputInfo(o.input); writeBtcObject(o.tx)
            }
            is MainPenaltyTx -> {
                write(0x0a); writeInputInfo(o.input); writeBtcObject(o.tx)
            }
            is HtlcPenaltyTx -> {
                write(0x0b); writeInputInfo(o.input); writeBtcObject(o.tx)
            }
            is ClaimHtlcDelayedOutputPenaltyTx -> {
                write(0x0c); writeInputInfo(o.input); writeBtcObject(o.tx)
            }
            is ClosingTx -> {
                write(0x0d); writeInputInfo(o.input); writeBtcObject(o.tx); writeNullable(o.toLocalIndex) { writeNumber(it) }
            }
            is SpliceTx -> {
                write(0x0e); writeInputInfo(o.input); writeBtcObject(o.tx)
            }
        }
    }

    private fun Output.writeClosingFeerates(o: ClosingFeerates): Unit = o.run {
        writeNumber(preferred.toLong())
        writeNumber(min.toLong())
        writeNumber(max.toLong())
    }

    private fun Output.writeNumber(o: Number): Unit = LightningCodecs.writeBigSize(o.toLong(), this)

    private fun Output.writeBoolean(o: Boolean): Unit = if (o) write(1) else write(0)

    private fun Output.writeString(o: String): Unit = writeDelimited(o.encodeToByteArray())

    private fun Output.writeByteVector32(o: ByteVector32) = write(o.toByteArray())

    private fun Output.writeByteVector64(o: ByteVector64) = write(o.toByteArray())

    private fun Output.writePublicKey(o: PublicKey) = write(o.value.toByteArray())

    private fun Output.writeTxId(o: TxId) = write(o.value.toByteArray())

    private fun Output.writePublicNonce(o: PublicNonce) = write(o.toByteArray())

    private fun Output.writeDelimited(o: ByteArray) {
        writeNumber(o.size)
        write(o)
    }

    private fun <T : BtcSerializable<T>> Output.writeBtcObject(o: T): Unit = writeDelimited(o.serializer().write(o))

    private fun Output.writeLightningMessage(o: LightningMessage) = writeDelimited(LightningMessage.encode(o))

    private fun <T> Output.writeCollection(o: Collection<T>, writeElem: (T) -> Unit) {
        writeNumber(o.size)
        o.forEach { writeElem(it) }
    }

    private fun <L, R> Output.writeEither(o: Either<L, R>, writeLeft: (L) -> Unit, writeRight: (R) -> Unit) = when (o) {
        is Either.Left -> {
            write(0); writeLeft(o.value)
        }
        is Either.Right -> {
            write(1); writeRight(o.value)
        }
    }

    private fun <T : Any> Output.writeNullable(o: T?, writeNotNull: (T) -> Unit) = when (o) {
        is T -> {
            write(1); writeNotNull(o)
        }
        else -> write(0)
    }
}