package fr.acinq.lightning.serialization.channel.v4

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.readNBytes
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Features
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.channel.states.*
import fr.acinq.lightning.crypto.ShaChain
import fr.acinq.lightning.serialization.InputExtensions.readBoolean
import fr.acinq.lightning.serialization.InputExtensions.readByteVector32
import fr.acinq.lightning.serialization.InputExtensions.readByteVector64
import fr.acinq.lightning.serialization.InputExtensions.readCollection
import fr.acinq.lightning.serialization.InputExtensions.readDelimitedByteArray
import fr.acinq.lightning.serialization.InputExtensions.readEither
import fr.acinq.lightning.serialization.InputExtensions.readLightningMessage
import fr.acinq.lightning.serialization.InputExtensions.readNullable
import fr.acinq.lightning.serialization.InputExtensions.readNumber
import fr.acinq.lightning.serialization.InputExtensions.readPublicKey
import fr.acinq.lightning.serialization.InputExtensions.readString
import fr.acinq.lightning.serialization.InputExtensions.readTxId
import fr.acinq.lightning.serialization.channel.allHtlcs
import fr.acinq.lightning.serialization.common.liquidityads.Deserialization.readLiquidityPurchase
import fr.acinq.lightning.transactions.*
import fr.acinq.lightning.transactions.Transactions.TransactionWithInputInfo.*
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.*
import kotlinx.coroutines.CompletableDeferred

object Deserialization {

    fun deserialize(bin: ByteArray): PersistedChannelState {
        val input = ByteArrayInput(bin)
        val version = input.read()
        require(version == Serialization.VERSION_MAGIC) { "incorrect version $version, expected ${Serialization.VERSION_MAGIC}" }
        return input.readPersistedChannelState()
    }

    fun deserializePeerStorage(bin: ByteArray): List<PersistedChannelState> {
        val input = ByteArrayInput(bin)
        return input.readCollection { input.readPersistedChannelState() }.toList()
    }

    private fun Input.readPersistedChannelState(): PersistedChannelState = when (val discriminator = read()) {
        0x08 -> readLegacyWaitForFundingConfirmed()
        0x09 -> readLegacyWaitForFundingLocked()
        0x00 -> readWaitForFundingConfirmedWithPushAmount()
        0x01 -> readWaitForChannelReady()
        0x02 -> readNormalLegacy()
        0x03 -> readShuttingDownBeforeSimpleClose()
        0x04 -> readNegotiatingBeforeSimpleClose()
        0x05 -> readClosing()
        0x06 -> readWaitForRemotePublishFutureCommitment()
        0x07 -> readClosed()
        0x0a -> readWaitForFundingSignedLegacy()
        0x0b -> readNormalBeforeSimpleClose()
        0x0c -> readWaitForFundingSignedWithPushAmount()
        0x0d -> readWaitForFundingSigned()
        0x0e -> readWaitForFundingConfirmed()
        0x0f -> readNormal()
        0x10 -> readShuttingDown()
        0x11 -> readNegotiating()
        else -> error("unknown discriminator $discriminator for class ${PersistedChannelState::class}")
    }

    private fun Input.readLegacyWaitForFundingConfirmed() = LegacyWaitForFundingConfirmed(
        commitments = readCommitments(),
        fundingTx = readNullable { readTransaction() },
        waitingSinceBlock = readNumber(),
        deferred = readNullable { readLightningMessage() as ChannelReady },
        lastSent = readEither(
            readLeft = { readLightningMessage() as FundingCreated },
            readRight = { readLightningMessage() as FundingSigned }
        )
    )

    private fun Input.readLegacyWaitForFundingLocked() = LegacyWaitForFundingLocked(
        commitments = readCommitments(),
        shortChannelId = ShortChannelId(readNumber()),
        lastSent = readLightningMessage() as ChannelReady
    )

    private fun Input.readWaitForFundingSigned() = WaitForFundingSigned(
        channelParams = readChannelParams(),
        signingSession = readInteractiveTxSigningSession(emptySet()),
        remoteSecondPerCommitmentPoint = readPublicKey(),
        liquidityPurchase = readNullable { readLiquidityPurchase() },
        channelOrigin = readNullable { readChannelOrigin() }
    )

    private fun Input.readWaitForFundingSignedWithPushAmount(): WaitForFundingSigned {
        val channelParams = readChannelParams()
        val signingSession = readInteractiveTxSigningSession(emptySet())
        // We previously included a local_push_amount and a remote_push_amount.
        readNumber()
        readNumber()
        val remoteSecondPerCommitmentPoint = readPublicKey()
        val liquidityPurchase = readNullable { readLiquidityPurchase() }
        val channelOrigin = readNullable { readChannelOrigin() }
        return WaitForFundingSigned(channelParams, signingSession, remoteSecondPerCommitmentPoint, liquidityPurchase, channelOrigin)
    }

    private fun Input.readWaitForFundingSignedLegacy(): WaitForFundingSigned {
        val channelParams = readChannelParams()
        val signingSession = readInteractiveTxSigningSession(emptySet())
        // We previously included a local_push_amount and a remote_push_amount.
        readNumber()
        readNumber()
        val remoteSecondPerCommitmentPoint = readPublicKey()
        val channelOrigin = readNullable { readChannelOrigin() }
        return WaitForFundingSigned(channelParams, signingSession, remoteSecondPerCommitmentPoint, liquidityPurchase = null, channelOrigin)
    }

    private fun Input.readWaitForFundingConfirmedWithPushAmount(): WaitForFundingConfirmed {
        val commitments = readCommitments()
        // We previously included a local_push_amount and a remote_push_amount.
        readNumber()
        readNumber()
        val waitingSinceBlock = readNumber()
        val deferred = readNullable { readLightningMessage() as ChannelReady }
        val rbfStatus = when (val discriminator = read()) {
            0x00 -> RbfStatus.None
            0x01 -> RbfStatus.WaitingForSigs(readInteractiveTxSigningSession(emptySet()))
            else -> error("unknown discriminator $discriminator for class ${RbfStatus::class}")
        }
        return WaitForFundingConfirmed(commitments, waitingSinceBlock, deferred, rbfStatus)
    }

    private fun Input.readWaitForFundingConfirmed() = WaitForFundingConfirmed(
        commitments = readCommitments(),
        waitingSinceBlock = readNumber(),
        deferred = readNullable { readLightningMessage() as ChannelReady },
        rbfStatus = when (val discriminator = read()) {
            0x00 -> RbfStatus.None
            0x01 -> RbfStatus.WaitingForSigs(readInteractiveTxSigningSession(emptySet()))
            else -> error("unknown discriminator $discriminator for class ${RbfStatus::class}")
        }
    )

    private fun Input.readWaitForChannelReady() = WaitForChannelReady(
        commitments = readCommitments(),
        shortChannelId = ShortChannelId(readNumber()),
        lastSent = readLightningMessage() as ChannelReady
    )

    private fun Input.readNormal(): Normal {
        val commitments = readCommitments()
        return Normal(
            commitments = commitments,
            shortChannelId = ShortChannelId(readNumber()),
            channelUpdate = readLightningMessage() as ChannelUpdate,
            remoteChannelUpdate = readNullable { readLightningMessage() as ChannelUpdate },
            spliceStatus = when (val discriminator = read()) {
                0x00 -> SpliceStatus.None
                0x01 -> SpliceStatus.WaitingForSigs(readInteractiveTxSigningSession(commitments.allHtlcs), readNullable { readLiquidityPurchase() }, readCollection { readChannelOrigin() }.toList())
                else -> error("unknown discriminator $discriminator for class ${SpliceStatus::class}")
            },
            localShutdown = readNullable { readLightningMessage() as Shutdown },
            remoteShutdown = readNullable { readLightningMessage() as Shutdown },
            closeCommand = readNullable { readCloseCommand() },
        )
    }

    private fun Input.readNormalBeforeSimpleClose(): Normal {
        val commitments = readCommitments()
        val shortChannelId = ShortChannelId(readNumber())
        val channelUpdate = readLightningMessage() as ChannelUpdate
        val remoteChannelUpdate = readNullable { readLightningMessage() as ChannelUpdate }
        val localShutdown = readNullable { readLightningMessage() as Shutdown }
        val remoteShutdown = readNullable { readLightningMessage() as Shutdown }
        val closeCommand = readNullable {
            // We used to store three closing feerates for fee range negotiation.
            val preferred = FeeratePerKw(readNumber().sat)
            readNumber()
            readNumber()
            ChannelCommand.Close.MutualClose(CompletableDeferred(), localShutdown?.scriptPubKey, preferred)
        }
        val spliceStatus = when (val discriminator = read()) {
            0x00 -> SpliceStatus.None
            0x01 -> SpliceStatus.WaitingForSigs(readInteractiveTxSigningSession(commitments.allHtlcs), readNullable { readLiquidityPurchase() }, readCollection { readChannelOrigin() }.toList())
            else -> error("unknown discriminator $discriminator for class ${SpliceStatus::class}")
        }
        return Normal(commitments, shortChannelId, channelUpdate, remoteChannelUpdate, spliceStatus, localShutdown, remoteShutdown, closeCommand)
    }

    private fun Input.readNormalLegacy(): Normal {
        val commitments = readCommitments()
        val shortChannelId = ShortChannelId(readNumber())
        val channelUpdate = readLightningMessage() as ChannelUpdate
        val remoteChannelUpdate = readNullable { readLightningMessage() as ChannelUpdate }
        val localShutdown = readNullable { readLightningMessage() as Shutdown }
        val remoteShutdown = readNullable { readLightningMessage() as Shutdown }
        val closeCommand = readNullable {
            // We used to store three closing feerates for fee range negotiation.
            val preferred = FeeratePerKw(readNumber().sat)
            readNumber()
            readNumber()
            ChannelCommand.Close.MutualClose(CompletableDeferred(), localShutdown?.scriptPubKey, preferred)
        }
        val spliceStatus = when (val discriminator = read()) {
            0x00 -> SpliceStatus.None
            0x01 -> SpliceStatus.WaitingForSigs(readInteractiveTxSigningSession(commitments.allHtlcs), null, readCollection { readChannelOrigin() }.toList())
            else -> error("unknown discriminator $discriminator for class ${SpliceStatus::class}")
        }
        return Normal(commitments, shortChannelId, channelUpdate, remoteChannelUpdate, spliceStatus, localShutdown, remoteShutdown, closeCommand)
    }

    private fun Input.readShuttingDownBeforeSimpleClose(): ShuttingDown {
        val commitments = readCommitments()
        val localShutdown = readLightningMessage() as Shutdown
        val remoteShutdown = readLightningMessage() as Shutdown
        val closeCommand = readNullable {
            // We used to store three closing feerates for fee range negotiation.
            val preferred = FeeratePerKw(readNumber().sat)
            readNumber()
            readNumber()
            ChannelCommand.Close.MutualClose(CompletableDeferred(), localShutdown.scriptPubKey, preferred)
        }
        return ShuttingDown(commitments, localShutdown, remoteShutdown, closeCommand)
    }

    private fun Input.readShuttingDown(): ShuttingDown = ShuttingDown(
        commitments = readCommitments(),
        localShutdown = readLightningMessage() as Shutdown,
        remoteShutdown = readLightningMessage() as Shutdown,
        closeCommand = readNullable { readCloseCommand() },
    )

    private fun Input.readNegotiatingBeforeSimpleClose(): Negotiating {
        val commitments = readCommitments()
        val localShutdown = readLightningMessage() as Shutdown
        val remoteShutdown = readLightningMessage() as Shutdown
        // We cannot convert the closing transactions created with the old closing protocol to the new one.
        // We simply ignore them, which will lead to a force-close if one of the proposed transactions is published.
        readCollection {
            readCollection {
                readTransactionWithInputInfo() // unsigned closing tx
                readDelimitedByteArray() // closing_signed message
            }.toList()
        }.toList()
        val bestUnpublishedClosingTx = readNullable { readTransactionWithInputInfo() as ClosingTx }
        val closeCommand = readNullable {
            // We used to store three closing feerates for fee range negotiation.
            val preferred = FeeratePerKw(readNumber().sat)
            readNumber()
            readNumber()
            ChannelCommand.Close.MutualClose(CompletableDeferred(), localShutdown.scriptPubKey, preferred)
        }
        return Negotiating(commitments, localShutdown.scriptPubKey, remoteShutdown.scriptPubKey, listOf(), listOfNotNull(bestUnpublishedClosingTx), waitingSinceBlock = 0, closeCommand)
    }

    private fun Input.readNegotiating(): Negotiating = Negotiating(
        commitments = readCommitments(),
        localScript = readDelimitedByteArray().byteVector(),
        remoteScript = readDelimitedByteArray().byteVector(),
        proposedClosingTxs = readCollection {
            Transactions.ClosingTxs(
                readNullable { readTransactionWithInputInfo() as ClosingTx },
                readNullable { readTransactionWithInputInfo() as ClosingTx },
                readNullable { readTransactionWithInputInfo() as ClosingTx },
            )
        }.toList(),
        publishedClosingTxs = readCollection { readTransactionWithInputInfo() as ClosingTx }.toList(),
        waitingSinceBlock = readNumber(),
        closeCommand = readNullable { readCloseCommand() },
    )

    private fun Input.readClosing(): Closing = Closing(
        commitments = readCommitments(),
        waitingSinceBlock = readNumber(),
        mutualCloseProposed = readCollection { readTransactionWithInputInfo() as ClosingTx }.toList(),
        mutualClosePublished = readCollection { readTransactionWithInputInfo() as ClosingTx }.toList(),
        localCommitPublished = readNullable { readLocalCommitPublished() },
        remoteCommitPublished = readNullable { readRemoteCommitPublished() },
        nextRemoteCommitPublished = readNullable { readRemoteCommitPublished() },
        futureRemoteCommitPublished = readNullable { readRemoteCommitPublished() },
        revokedCommitPublished = readCollection { readRevokedCommitPublished() }.toList()
    )

    private fun Input.readLocalCommitPublished(): LocalCommitPublished = LocalCommitPublished(
        commitTx = readTransaction(),
        claimMainDelayedOutputTx = readNullable { readTransactionWithInputInfo() as ClaimLocalDelayedOutputTx },
        htlcTxs = readCollection { readOutPoint() to readNullable { readTransactionWithInputInfo() as HtlcTx } }.toMap(),
        claimHtlcDelayedTxs = readCollection { readTransactionWithInputInfo() as ClaimLocalDelayedOutputTx }.toList(),
        claimAnchorTxs = readCollection { readTransactionWithInputInfo() as ClaimAnchorOutputTx }.toList(),
        irrevocablySpent = readIrrevocablySpent()
    )

    private fun Input.readRemoteCommitPublished(): RemoteCommitPublished = RemoteCommitPublished(
        commitTx = readTransaction(),
        claimMainOutputTx = readNullable { readTransactionWithInputInfo() as ClaimRemoteCommitMainOutputTx.ClaimRemoteDelayedOutputTx },
        claimHtlcTxs = readCollection { readOutPoint() to readNullable { readTransactionWithInputInfo() as ClaimHtlcTx } }.toMap(),
        claimAnchorTxs = readCollection { readTransactionWithInputInfo() as ClaimAnchorOutputTx }.toList(),
        irrevocablySpent = readIrrevocablySpent()
    )

    private fun Input.readRevokedCommitPublished(): RevokedCommitPublished = RevokedCommitPublished(
        commitTx = readTransaction(),
        remotePerCommitmentSecret = PrivateKey(readByteVector32()),
        claimMainOutputTx = readNullable { readTransactionWithInputInfo() as ClaimRemoteCommitMainOutputTx.ClaimRemoteDelayedOutputTx },
        mainPenaltyTx = readNullable { readTransactionWithInputInfo() as MainPenaltyTx },
        htlcPenaltyTxs = readCollection { readTransactionWithInputInfo() as HtlcPenaltyTx }.toList(),
        claimHtlcDelayedPenaltyTxs = readCollection { readTransactionWithInputInfo() as ClaimHtlcDelayedOutputPenaltyTx }.toList(),
        irrevocablySpent = readIrrevocablySpent()
    )

    private fun Input.readIrrevocablySpent(): Map<OutPoint, Transaction> = readCollection {
        readOutPoint() to readTransaction()
    }.toMap()

    private fun Input.readWaitForRemotePublishFutureCommitment(): WaitForRemotePublishFutureCommitment = WaitForRemotePublishFutureCommitment(
        commitments = readCommitments(),
        remoteChannelReestablish = readLightningMessage() as ChannelReestablish
    )

    private fun Input.readClosed(): Closed = Closed(
        state = readClosing()
    )

    private fun Input.readSharedFundingInput(): SharedFundingInput = when (val discriminator = read()) {
        0x01 -> SharedFundingInput.Multisig2of2(
            info = readInputInfo(),
            fundingTxIndex = readNumber(),
            remoteFundingPubkey = readPublicKey()
        )
        else -> error("unknown discriminator $discriminator for class ${SharedFundingInput::class}")
    }

    private fun Input.readInteractiveTxParams() = InteractiveTxParams(
        channelId = readByteVector32(),
        isInitiator = readBoolean(),
        localContribution = readNumber().sat,
        remoteContribution = readNumber().sat,
        sharedInput = readNullable { readSharedFundingInput() },
        remoteFundingPubkey = readPublicKey(),
        localOutputs = readCollection { TxOut.read(readDelimitedByteArray()) }.toList(),
        lockTime = readNumber(),
        dustLimit = readNumber().sat,
        targetFeerate = FeeratePerKw(readNumber().sat)
    )

    private fun Input.readSharedInteractiveTxInput() = when (val discriminator = read()) {
        0x01 -> InteractiveTxInput.Shared(
            serialId = readNumber(),
            outPoint = readOutPoint(),
            publicKeyScript = ByteVector.empty,
            sequence = readNumber().toUInt(),
            localAmount = readNumber().msat,
            remoteAmount = readNumber().msat,
            htlcAmount = 0.msat
        )
        0x02 -> InteractiveTxInput.Shared(
            serialId = readNumber(),
            outPoint = readOutPoint(),
            publicKeyScript = readDelimitedByteArray().byteVector(),
            sequence = readNumber().toUInt(),
            localAmount = readNumber().msat,
            remoteAmount = readNumber().msat,
            htlcAmount = 0.msat
        )
        0x03 -> InteractiveTxInput.Shared(
            serialId = readNumber(),
            outPoint = readOutPoint(),
            publicKeyScript = readDelimitedByteArray().byteVector(),
            sequence = readNumber().toUInt(),
            localAmount = readNumber().msat,
            remoteAmount = readNumber().msat,
            htlcAmount = readNumber().msat
        )
        else -> error("unknown discriminator $discriminator for class ${InteractiveTxInput.Shared::class}")
    }

    private fun Input.readLocalInteractiveTxInput() = when (val discriminator = read()) {
        0x01 -> InteractiveTxInput.LocalOnly(
            serialId = readNumber(),
            previousTx = readTransaction(),
            previousTxOutput = readNumber(),
            sequence = readNumber().toUInt(),
        )
        0x02 -> InteractiveTxInput.LocalLegacySwapIn(
            serialId = readNumber(),
            previousTx = readTransaction(),
            previousTxOutput = readNumber(),
            sequence = readNumber().toUInt(),
            userKey = readPublicKey(),
            serverKey = readPublicKey(),
            refundDelay = readNumber().toInt(),
        )
        0x03 -> InteractiveTxInput.LocalSwapIn(
            serialId = readNumber(),
            previousTx = readTransaction(),
            previousTxOutput = readNumber(),
            sequence = readNumber().toUInt(),
            addressIndex = readNumber().toInt(),
            userKey = readPublicKey(),
            serverKey = readPublicKey(),
            userRefundKey = readPublicKey(),
            refundDelay = readNumber().toInt(),
        )
        else -> error("unknown discriminator $discriminator for class ${InteractiveTxInput.Local::class}")
    }

    private fun Input.readRemoteInteractiveTxInput() = when (val discriminator = read()) {
        0x01 -> InteractiveTxInput.RemoteOnly(
            serialId = readNumber(),
            outPoint = readOutPoint(),
            txOut = TxOut.read(readDelimitedByteArray()),
            sequence = readNumber().toUInt(),
        )
        0x02 -> InteractiveTxInput.RemoteLegacySwapIn(
            serialId = readNumber(),
            outPoint = readOutPoint(),
            txOut = TxOut.read(readDelimitedByteArray()),
            sequence = readNumber().toUInt(),
            userKey = readPublicKey(),
            serverKey = readPublicKey(),
            refundDelay = readNumber().toInt()
        )
        0x03 -> InteractiveTxInput.RemoteSwapIn(
            serialId = readNumber(),
            outPoint = readOutPoint(),
            txOut = TxOut.read(readDelimitedByteArray()),
            sequence = readNumber().toUInt(),
            userKey = readPublicKey(),
            serverKey = readPublicKey(),
            userRefundKey = readPublicKey(),
            refundDelay = readNumber().toInt()
        )
        else -> error("unknown discriminator $discriminator for class ${InteractiveTxInput.Remote::class}")
    }

    private fun Input.readSharedInteractiveTxOutput() = when (val discriminator = read()) {
        0x01 -> InteractiveTxOutput.Shared(
            serialId = readNumber(),
            pubkeyScript = readDelimitedByteArray().toByteVector(),
            localAmount = readNumber().msat,
            remoteAmount = readNumber().msat,
            htlcAmount = 0.msat
        )
        0x02 -> InteractiveTxOutput.Shared(
            serialId = readNumber(),
            pubkeyScript = readDelimitedByteArray().toByteVector(),
            localAmount = readNumber().msat,
            remoteAmount = readNumber().msat,
            htlcAmount = readNumber().msat
        )
        else -> error("unknown discriminator $discriminator for class ${InteractiveTxOutput.Shared::class}")
    }

    private fun Input.readLocalInteractiveTxOutput() = when (val discriminator = read()) {
        0x01 -> InteractiveTxOutput.Local.Change(
            serialId = readNumber(),
            amount = readNumber().sat,
            pubkeyScript = readDelimitedByteArray().toByteVector(),
        )
        0x02 -> InteractiveTxOutput.Local.NonChange(
            serialId = readNumber(),
            amount = readNumber().sat,
            pubkeyScript = readDelimitedByteArray().toByteVector(),
        )
        else -> error("unknown discriminator $discriminator for class ${InteractiveTxOutput.Local::class}")
    }

    private fun Input.readRemoteInteractiveTxOutput() = when (val discriminator = read()) {
        0x01 -> InteractiveTxOutput.Remote(
            serialId = readNumber(),
            amount = readNumber().sat,
            pubkeyScript = readDelimitedByteArray().toByteVector(),
        )
        else -> error("unknown discriminator $discriminator for class ${InteractiveTxOutput.Remote::class}")
    }

    private fun Input.readSharedTransaction() = SharedTransaction(
        sharedInput = readNullable { readSharedInteractiveTxInput() },
        sharedOutput = readSharedInteractiveTxOutput(),
        localInputs = readCollection { readLocalInteractiveTxInput() }.toList(),
        remoteInputs = readCollection { readRemoteInteractiveTxInput() }.toList(),
        localOutputs = readCollection { readLocalInteractiveTxOutput() }.toList(),
        remoteOutputs = readCollection { readRemoteInteractiveTxOutput() }.toList(),
        lockTime = readNumber(),
    )

    private fun Input.readScriptWitness() = ScriptWitness(readCollection { readDelimitedByteArray().toByteVector() }.toList())

    private fun Input.readSignedSharedTransaction() = when (val discriminator = read()) {
        0x01 -> PartiallySignedSharedTransaction(
            tx = readSharedTransaction(),
            localSigs = readLightningMessage() as TxSignatures
        )
        0x02 -> FullySignedSharedTransaction(
            tx = readSharedTransaction(),
            localSigs = readLightningMessage() as TxSignatures,
            remoteSigs = readLightningMessage() as TxSignatures,
            sharedSigs = readNullable { readScriptWitness() },
        )
        else -> error("unknown discriminator $discriminator for class ${SignedSharedTransaction::class}")
    }

    private fun Input.readUnsignedLocalCommitWithHtlcs(): InteractiveTxSigningSession.Companion.UnsignedLocalCommit = InteractiveTxSigningSession.Companion.UnsignedLocalCommit(
        index = readNumber(),
        spec = readCommitmentSpecWithHtlcs(),
        commitTx = readTransactionWithInputInfo() as CommitTx,
        htlcTxs = readCollection { readTransactionWithInputInfo() as HtlcTx }.toList(),
    )

    private fun Input.readUnsignedLocalCommitWithoutHtlcs(htlcs: Set<DirectedHtlc>): InteractiveTxSigningSession.Companion.UnsignedLocalCommit = InteractiveTxSigningSession.Companion.UnsignedLocalCommit(
        index = readNumber(),
        spec = readCommitmentSpecWithoutHtlcs(htlcs),
        commitTx = readTransactionWithInputInfo() as CommitTx,
        htlcTxs = readCollection { readTransactionWithInputInfo() as HtlcTx }.toList(),
    )

    private fun Input.readLocalCommitWithHtlcs(): LocalCommit = LocalCommit(
        index = readNumber(),
        spec = readCommitmentSpecWithHtlcs(),
        publishableTxs = PublishableTxs(
            commitTx = readTransactionWithInputInfo() as CommitTx,
            htlcTxsAndSigs = readCollection {
                HtlcTxAndSigs(
                    txinfo = readTransactionWithInputInfo() as HtlcTx,
                    localSig = readByteVector64(),
                    remoteSig = readByteVector64()
                )
            }.toList()
        )
    )

    private fun Input.readLocalCommitWithoutHtlcs(htlcs: Set<DirectedHtlc>): LocalCommit = LocalCommit(
        index = readNumber(),
        spec = readCommitmentSpecWithoutHtlcs(htlcs),
        publishableTxs = PublishableTxs(
            commitTx = readTransactionWithInputInfo() as CommitTx,
            htlcTxsAndSigs = readCollection {
                HtlcTxAndSigs(
                    txinfo = readTransactionWithInputInfo() as HtlcTx,
                    localSig = readByteVector64(),
                    remoteSig = readByteVector64()
                )
            }.toList()
        )
    )

    private fun Input.readRemoteCommitWithHtlcs(): RemoteCommit = RemoteCommit(
        index = readNumber(),
        spec = readCommitmentSpecWithHtlcs(),
        txid = readTxId(),
        remotePerCommitmentPoint = readPublicKey()
    )

    private fun Input.readRemoteCommitWithoutHtlcs(htlcs: Set<DirectedHtlc>): RemoteCommit = RemoteCommit(
        index = readNumber(),
        spec = readCommitmentSpecWithoutHtlcs(htlcs.map { it.opposite() }.toSet()),
        txid = readTxId(),
        remotePerCommitmentPoint = readPublicKey()
    )

    private fun Input.skipLegacyLiquidityLease() {
        readNumber() // amount
        readNumber() // mining fee
        readNumber() // service fee
        readByteVector64() // seller signature
        readNBytes(readNumber().toInt()) // funding script
        readNumber() // lease duration
        readNumber() // lease end
        readNumber() // maximum proportional relay fee
        readNumber() // maximum base relay fee
    }

    private fun Input.readInteractiveTxSigningSession(htlcs: Set<DirectedHtlc>): InteractiveTxSigningSession {
        val fundingParams = readInteractiveTxParams()
        val fundingTxIndex = readNumber()
        val fundingTx = readSignedSharedTransaction() as PartiallySignedSharedTransaction
        val (localCommit, remoteCommit) = when (val discriminator = read()) {
            0 -> Pair(Either.Left(readUnsignedLocalCommitWithHtlcs()), readRemoteCommitWithHtlcs())
            1 -> Pair(Either.Right(readLocalCommitWithHtlcs()), readRemoteCommitWithHtlcs())
            2 -> {
                skipLegacyLiquidityLease()
                Pair(Either.Left(readUnsignedLocalCommitWithHtlcs()), readRemoteCommitWithHtlcs())
            }
            3 -> {
                skipLegacyLiquidityLease()
                Pair(Either.Right(readLocalCommitWithHtlcs()), readRemoteCommitWithHtlcs())
            }
            4 -> Pair(Either.Left(readUnsignedLocalCommitWithoutHtlcs(htlcs)), readRemoteCommitWithoutHtlcs(htlcs))
            5 -> Pair(Either.Right(readLocalCommitWithoutHtlcs(htlcs)), readRemoteCommitWithoutHtlcs(htlcs))
            else -> error("unknown discriminator $discriminator for class ${InteractiveTxSigningSession::class}")
        }
        return InteractiveTxSigningSession(fundingParams, fundingTxIndex, fundingTx, localCommit, remoteCommit)
    }

    private fun Input.readChannelOrigin(): Origin = when (val discriminator = read()) {
        0x01 -> {
            // Note that we've replaced this field by the payment preimage: old entries will be incorrect, but it's not critical.
            val paymentHash = readByteVector32()
            val serviceFee = readNumber().msat
            val miningFee = readNumber().sat
            val amount = readNumber().msat
            Origin.OffChainPayment(paymentHash, amount, ChannelManagementFees(miningFee, serviceFee.truncateToSatoshi()))
        }
        0x02 -> {
            readByteVector32() // unused requestId
            val serviceFee = readNumber().msat
            val miningFee = readNumber().sat
            val amount = readNumber().msat
            Origin.OnChainWallet(setOf(), amount, ChannelManagementFees(miningFee, serviceFee.truncateToSatoshi()))
        }
        0x03 -> Origin.OffChainPayment(
            paymentPreimage = readByteVector32(),
            amountBeforeFees = readNumber().msat,
            fees = ChannelManagementFees(miningFee = readNumber().sat, serviceFee = readNumber().sat),
        )
        0x04 -> Origin.OnChainWallet(
            inputs = readCollection { readOutPoint() }.toSet(),
            amountBeforeFees = readNumber().msat,
            fees = ChannelManagementFees(miningFee = readNumber().sat, serviceFee = readNumber().sat),
        )
        else -> error("unknown discriminator $discriminator for class ${Origin::class}")
    }

    private fun Input.readLocalParams(): LocalParams {
        val nodeId = readPublicKey()
        val fundingKeyPath = KeyPath(readCollection { readNumber() }.toList())
        val dustLimit = readNumber().sat
        val maxHtlcValueInFlightMsat = readNumber()
        val htlcMinimum = readNumber().msat
        val toSelfDelay = CltvExpiryDelta(readNumber().toInt())
        val maxAcceptedHtlcs = readNumber().toInt()
        val flags = readNumber().toInt()
        val isChannelOpener = flags.and(1) != 0
        val payCommitTxFees = flags.and(2) != 0
        val defaultFinalScriptPubKey = readDelimitedByteArray().toByteVector()
        val features = Features(readDelimitedByteArray().toByteVector())
        return LocalParams(nodeId, fundingKeyPath, dustLimit, maxHtlcValueInFlightMsat, htlcMinimum, toSelfDelay, maxAcceptedHtlcs, isChannelOpener, payCommitTxFees, defaultFinalScriptPubKey, features)
    }

    private fun Input.readRemoteParams(): RemoteParams = RemoteParams(
        nodeId = readPublicKey(),
        dustLimit = readNumber().sat,
        maxHtlcValueInFlightMsat = readNumber(),
        htlcMinimum = readNumber().msat,
        toSelfDelay = CltvExpiryDelta(readNumber().toInt()),
        maxAcceptedHtlcs = readNumber().toInt(),
        revocationBasepoint = readPublicKey(),
        paymentBasepoint = readPublicKey(),
        delayedPaymentBasepoint = readPublicKey(),
        htlcBasepoint = readPublicKey(),
        features = Features(readDelimitedByteArray().toByteVector())
    )

    private fun Input.readChannelFlags(): ChannelFlags {
        val flags = readNumber().toInt()
        return ChannelFlags(announceChannel = flags.and(1) != 0, nonInitiatorPaysCommitFees = flags.and(2) != 0)
    }

    private fun Input.readChannelParams(): ChannelParams = ChannelParams(
        channelId = readByteVector32(),
        channelConfig = ChannelConfig(readDelimitedByteArray()),
        channelFeatures = ChannelFeatures(Features(readDelimitedByteArray()).activated.keys),
        localParams = readLocalParams(),
        remoteParams = readRemoteParams(),
        channelFlags = readChannelFlags(),
    )

    private fun Input.readCommitmentChanges(): CommitmentChanges = CommitmentChanges(
        localChanges = LocalChanges(
            proposed = readCollection { readLightningMessage() as UpdateMessage }.toList(),
            signed = readCollection { readLightningMessage() as UpdateMessage }.toList(),
            acked = readCollection { readLightningMessage() as UpdateMessage }.toList(),
        ),
        remoteChanges = RemoteChanges(
            proposed = readCollection { readLightningMessage() as UpdateMessage }.toList(),
            acked = readCollection { readLightningMessage() as UpdateMessage }.toList(),
            signed = readCollection { readLightningMessage() as UpdateMessage }.toList(),
        ),
        localNextHtlcId = readNumber(),
        remoteNextHtlcId = readNumber(),
    )

    private fun Input.readCommitment(htlcs: Set<DirectedHtlc>): Commitment = Commitment(
        fundingTxIndex = readNumber(),
        remoteFundingPubkey = readPublicKey(),
        localFundingStatus = when (val discriminator = read()) {
            0x00 -> LocalFundingStatus.UnconfirmedFundingTx(
                sharedTx = readSignedSharedTransaction(),
                fundingParams = readInteractiveTxParams(),
                createdAt = readNumber()
            )
            0x01 -> LocalFundingStatus.ConfirmedFundingTx(
                signedTx = readTransaction(),
                fee = readNumber().sat,
                // We previously didn't store the tx_signatures after the transaction was confirmed.
                // It is only used to be retransmitted on reconnection if our peer had not received it.
                // This happens very rarely in practice, so putting dummy values here shouldn't be an issue.
                localSigs = TxSignatures(ByteVector32.Zeroes, TxId(ByteVector32.Zeroes), listOf()),
                // We previously didn't store the short_channel_id in the commitment object.
                // We will fetch the funding transaction on restart to set it to the correct value.
                shortChannelId = ShortChannelId(0),
            )
            0x02 -> LocalFundingStatus.ConfirmedFundingTx(
                signedTx = readTransaction(),
                fee = readNumber().sat,
                localSigs = readLightningMessage() as TxSignatures,
                // We previously didn't store the short_channel_id in the commitment object.
                // We will fetch the funding transaction on restart to set it to the correct value.
                shortChannelId = ShortChannelId(0),
            )
            0x03 -> LocalFundingStatus.ConfirmedFundingTx(
                signedTx = readTransaction(),
                fee = readNumber().sat,
                localSigs = readLightningMessage() as TxSignatures,
                shortChannelId = ShortChannelId(readNumber())
            )
            else -> error("unknown discriminator $discriminator for class ${LocalFundingStatus::class}")
        },
        remoteFundingStatus = when (val discriminator = read()) {
            0x00 -> RemoteFundingStatus.NotLocked
            0x01 -> RemoteFundingStatus.Locked
            else -> error("unknown discriminator $discriminator for class ${RemoteFundingStatus::class}")
        },
        localCommit = readLocalCommitWithoutHtlcs(htlcs),
        remoteCommit = readRemoteCommitWithoutHtlcs(htlcs),
        nextRemoteCommit = readNullable {
            NextRemoteCommit(
                sig = readLightningMessage() as CommitSig,
                commit = readRemoteCommitWithoutHtlcs(htlcs)
            )
        }
    )

    private fun Input.readCommitments(): Commitments {
        val params = readChannelParams()
        val changes = readCommitmentChanges()
        // When multiple commitments are active, htlcs are shared between all of these commitments, so we serialize them separately.
        // The direction we use is from our local point of view: we use sets, which deduplicates htlcs that are in both local and remote commitments.
        val htlcs = readCollection { readDirectedHtlc() }.toSet()
        val active = readCollection { readCommitment(htlcs) }.toList()
        val inactive = readCollection { readCommitment(htlcs) }.toList()
        val payments = readCollection {
            readNumber() to UUID.fromString(readString())
        }.toMap()
        val remoteNextCommitInfo = readEither(
            readLeft = { WaitingForRevocation(sentAfterLocalCommitIndex = readNumber()) },
            readRight = { readPublicKey() },
        )
        val remotePerCommitmentSecrets = ShaChain(
            knownHashes = readCollection {
                readCollection { readBoolean() }.toList() to readByteVector32()
            }.toMap(),
            lastIndex = readNullable { readNumber() }
        )
        readDelimitedByteArray() // ignored legacy remoteChannelData
        return Commitments(params, changes, active, inactive, payments, remoteNextCommitInfo, remotePerCommitmentSecrets)
    }

    private fun Input.readDirectedHtlc(): DirectedHtlc = when (val discriminator = read()) {
        0 -> IncomingHtlc(readLightningMessage() as UpdateAddHtlc)
        1 -> OutgoingHtlc(readLightningMessage() as UpdateAddHtlc)
        else -> error("invalid discriminator $discriminator for class ${DirectedHtlc::class}")
    }

    private fun Input.readCommitmentSpecWithHtlcs(): CommitmentSpec = CommitmentSpec(
        htlcs = readCollection { readDirectedHtlc() }.toSet(),
        feerate = FeeratePerKw(readNumber().sat),
        toLocal = readNumber().msat,
        toRemote = readNumber().msat
    )

    private fun Input.readCommitmentSpecWithoutHtlcs(htlcs: Set<DirectedHtlc>): CommitmentSpec = CommitmentSpec(
        htlcs = readCollection {
            when (val discriminator = read()) {
                0 -> {
                    val htlcId = readNumber()
                    htlcs.first { it is IncomingHtlc && it.add.id == htlcId }
                }
                1 -> {
                    val htlcId = readNumber()
                    htlcs.first { it is OutgoingHtlc && it.add.id == htlcId }
                }
                else -> error("invalid discriminator $discriminator for class ${DirectedHtlc::class}")
            }
        }.toSet(),
        feerate = FeeratePerKw(readNumber().sat),
        toLocal = readNumber().msat,
        toRemote = readNumber().msat
    )

    private fun Input.readInputInfo(): Transactions.InputInfo = Transactions.InputInfo(
        outPoint = readOutPoint(),
        txOut = TxOut.read(readDelimitedByteArray()),
        redeemScript = readDelimitedByteArray().toByteVector()
    )

    private fun Input.readOutPoint(): OutPoint = OutPoint.read(readDelimitedByteArray())

    private fun Input.readTransaction(): Transaction = Transaction.read(readDelimitedByteArray())

    private fun Input.readTransactionWithInputInfo(): Transactions.TransactionWithInputInfo = when (val discriminator = read()) {
        0x00 -> CommitTx(input = readInputInfo(), tx = readTransaction())
        0x01 -> HtlcTx.HtlcSuccessTx(input = readInputInfo(), tx = readTransaction(), paymentHash = readByteVector32(), htlcId = readNumber())
        0x02 -> HtlcTx.HtlcTimeoutTx(input = readInputInfo(), tx = readTransaction(), htlcId = readNumber())
        0x03 -> ClaimHtlcTx.ClaimHtlcSuccessTx(input = readInputInfo(), tx = readTransaction(), htlcId = readNumber())
        0x04 -> ClaimHtlcTx.ClaimHtlcTimeoutTx(input = readInputInfo(), tx = readTransaction(), htlcId = readNumber())
        0x05 -> ClaimAnchorOutputTx.ClaimLocalAnchorOutputTx(input = readInputInfo(), tx = readTransaction())
        0x06 -> ClaimAnchorOutputTx.ClaimRemoteAnchorOutputTx(input = readInputInfo(), tx = readTransaction())
        0x07 -> ClaimLocalDelayedOutputTx(input = readInputInfo(), tx = readTransaction())
        0x09 -> ClaimRemoteCommitMainOutputTx.ClaimRemoteDelayedOutputTx(input = readInputInfo(), tx = readTransaction())
        0x10 -> ClaimLocalDelayedOutputTx(input = readInputInfo(), tx = readTransaction())
        0x0a -> MainPenaltyTx(input = readInputInfo(), tx = readTransaction())
        0x0b -> HtlcPenaltyTx(input = readInputInfo(), tx = readTransaction())
        0x0c -> ClaimHtlcDelayedOutputPenaltyTx(input = readInputInfo(), tx = readTransaction())
        0x0d -> ClosingTx(input = readInputInfo(), tx = readTransaction(), toLocalIndex = readNullable { readNumber().toInt() })
        0x0e -> SpliceTx(input = readInputInfo(), tx = readTransaction())
        else -> error("unknown discriminator $discriminator for class ${Transactions.TransactionWithInputInfo::class}")
    }

    private fun Input.readCloseCommand(): ChannelCommand.Close.MutualClose = ChannelCommand.Close.MutualClose(
        replyTo = CompletableDeferred(),
        scriptPubKey = readNullable { readDelimitedByteArray().toByteVector() },
        feerate = FeeratePerKw(readNumber().sat),
    )
}