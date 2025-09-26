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
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.*
import kotlinx.coroutines.CompletableDeferred

object Deserialization {

    const val VERSION_MAGIC = 4

    fun deserialize(bin: ByteArray): PersistedChannelState {
        val input = ByteArrayInput(bin)
        val version = input.read()
        require(version == VERSION_MAGIC) { "incorrect version $version, expected $VERSION_MAGIC" }
        return input.readPersistedChannelState()
    }

    fun deserializePeerStorage(bin: ByteArray): List<PersistedChannelState> {
        val input = ByteArrayInput(bin)
        return input.readCollection { input.readPersistedChannelState() }.toList()
    }

    private fun Input.readPersistedChannelState(): PersistedChannelState = when (val discriminator = read()) {
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

    private fun Input.readWaitForFundingSigned(): WaitForFundingSigned {
        val (channelParams, localCommitParams, remoteCommitParams) = readChannelParams()
        val signingSession = readInteractiveTxSigningSession(emptySet(), localCommitParams, remoteCommitParams)
        val remoteSecondPerCommitmentPoint = readPublicKey()
        val liquidityPurchase = readNullable { readLiquidityPurchase() }
        val channelOrigin = readNullable { readChannelOrigin() }
        return WaitForFundingSigned(channelParams, signingSession, remoteSecondPerCommitmentPoint, liquidityPurchase, channelOrigin, mapOf())
    }

    private fun Input.readWaitForFundingSignedWithPushAmount(): WaitForFundingSigned {
        val (channelParams, localCommitParams, remoteCommitParams) = readChannelParams()
        val signingSession = readInteractiveTxSigningSession(emptySet(), localCommitParams, remoteCommitParams)
        // We previously included a local_push_amount and a remote_push_amount.
        readNumber()
        readNumber()
        val remoteSecondPerCommitmentPoint = readPublicKey()
        val liquidityPurchase = readNullable { readLiquidityPurchase() }
        val channelOrigin = readNullable { readChannelOrigin() }
        return WaitForFundingSigned(channelParams, signingSession, remoteSecondPerCommitmentPoint, liquidityPurchase, channelOrigin, mapOf())
    }

    private fun Input.readWaitForFundingSignedLegacy(): WaitForFundingSigned {
        val (channelParams, localCommitParams, remoteCommitParams) = readChannelParams()
        val signingSession = readInteractiveTxSigningSession(emptySet(), localCommitParams, remoteCommitParams)
        // We previously included a local_push_amount and a remote_push_amount.
        readNumber()
        readNumber()
        val remoteSecondPerCommitmentPoint = readPublicKey()
        val channelOrigin = readNullable { readChannelOrigin() }
        return WaitForFundingSigned(channelParams, signingSession, remoteSecondPerCommitmentPoint, liquidityPurchase = null, channelOrigin, mapOf())
    }

    private fun Input.readWaitForFundingConfirmedWithPushAmount(): WaitForFundingConfirmed {
        val commitments = readCommitments()
        val localCommitParams = commitments.latest.localCommitParams
        val remoteCommitParams = commitments.latest.remoteCommitParams
        // We previously included a local_push_amount and a remote_push_amount.
        readNumber()
        readNumber()
        val waitingSinceBlock = readNumber()
        val deferred = readNullable { readLightningMessage() as ChannelReady }
        val rbfStatus = when (val discriminator = read()) {
            0x00 -> RbfStatus.None
            0x01 -> RbfStatus.WaitingForSigs(readInteractiveTxSigningSession(emptySet(), localCommitParams, remoteCommitParams))
            else -> error("unknown discriminator $discriminator for class ${RbfStatus::class}")
        }
        return WaitForFundingConfirmed(commitments, waitingSinceBlock, deferred, rbfStatus, remoteCommitNonces = mapOf())
    }

    private fun Input.readWaitForFundingConfirmed(): WaitForFundingConfirmed {
        val commitments = readCommitments()
        val localCommitParams = commitments.latest.localCommitParams
        val remoteCommitParams = commitments.latest.remoteCommitParams
        val waitingSinceBlock = readNumber()
        val deferred = readNullable { readLightningMessage() as ChannelReady }
        val rbfStatus = when (val discriminator = read()) {
            0x00 -> RbfStatus.None
            0x01 -> RbfStatus.WaitingForSigs(readInteractiveTxSigningSession(emptySet(), localCommitParams, remoteCommitParams))
            else -> error("unknown discriminator $discriminator for class ${RbfStatus::class}")
        }
        return WaitForFundingConfirmed(commitments, waitingSinceBlock, deferred, rbfStatus, remoteCommitNonces = mapOf())
    }

    private fun Input.readWaitForChannelReady() = WaitForChannelReady(
        commitments = readCommitments(),
        shortChannelId = ShortChannelId(readNumber()),
        lastSent = readLightningMessage() as ChannelReady,
        remoteCommitNonces = mapOf()
    )

    private fun Input.readNormal(): Normal {
        val commitments = readCommitments()
        val localCommitParams = commitments.latest.localCommitParams
        val remoteCommitParams = commitments.latest.remoteCommitParams
        return Normal(
            commitments = commitments,
            shortChannelId = ShortChannelId(readNumber()),
            channelUpdate = readLightningMessage() as ChannelUpdate,
            remoteChannelUpdate = readNullable { readLightningMessage() as ChannelUpdate },
            spliceStatus = when (val discriminator = read()) {
                0x00 -> SpliceStatus.None
                0x01 -> SpliceStatus.WaitingForSigs(readInteractiveTxSigningSession(commitments.allHtlcs, localCommitParams, remoteCommitParams), readNullable { readLiquidityPurchase() }, readCollection { readChannelOrigin() }.toList())
                else -> error("unknown discriminator $discriminator for class ${SpliceStatus::class}")
            },
            localShutdown = readNullable { readLightningMessage() as Shutdown },
            remoteShutdown = readNullable { readLightningMessage() as Shutdown },
            closeCommand = readNullable { readCloseCommand() },
            remoteCommitNonces = mapOf(), localCloseeNonce = null, remoteCloseeNonce = null, localCloserNonces = null
        )
    }

    private fun Input.readNormalBeforeSimpleClose(): Normal {
        val commitments = readCommitments()
        val localCommitParams = commitments.latest.localCommitParams
        val remoteCommitParams = commitments.latest.remoteCommitParams
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
            0x01 -> SpliceStatus.WaitingForSigs(readInteractiveTxSigningSession(commitments.allHtlcs, localCommitParams, remoteCommitParams), readNullable { readLiquidityPurchase() }, readCollection { readChannelOrigin() }.toList())
            else -> error("unknown discriminator $discriminator for class ${SpliceStatus::class}")
        }
        return Normal(
            commitments,
            shortChannelId,
            channelUpdate,
            remoteChannelUpdate,
            spliceStatus,
            localShutdown,
            remoteShutdown,
            closeCommand,
            remoteCommitNonces = mapOf(),
            localCloseeNonce = null,
            remoteCloseeNonce = null,
            localCloserNonces = null
        )
    }

    private fun Input.readNormalLegacy(): Normal {
        val commitments = readCommitments()
        val localCommitParams = commitments.latest.localCommitParams
        val remoteCommitParams = commitments.latest.remoteCommitParams
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
            0x01 -> SpliceStatus.WaitingForSigs(readInteractiveTxSigningSession(commitments.allHtlcs, localCommitParams, remoteCommitParams), null, readCollection { readChannelOrigin() }.toList())
            else -> error("unknown discriminator $discriminator for class ${SpliceStatus::class}")
        }
        return Normal(
            commitments,
            shortChannelId,
            channelUpdate,
            remoteChannelUpdate,
            spliceStatus,
            localShutdown,
            remoteShutdown,
            closeCommand,
            remoteCommitNonces = mapOf(),
            localCloseeNonce = null,
            remoteCloseeNonce = null,
            localCloserNonces = null
        )
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
        return ShuttingDown(commitments, localShutdown, remoteShutdown, closeCommand, remoteCommitNonces = mapOf(), localCloseeNonce = null)
    }

    private fun Input.readShuttingDown(): ShuttingDown = ShuttingDown(
        commitments = readCommitments(),
        localShutdown = readLightningMessage() as Shutdown,
        remoteShutdown = readLightningMessage() as Shutdown,
        closeCommand = readNullable { readCloseCommand() },
        remoteCommitNonces = mapOf(),
        localCloseeNonce = null
    )

    private fun Input.readNegotiatingBeforeSimpleClose(): Negotiating {
        val commitments = readCommitments()
        val localShutdown = readLightningMessage() as Shutdown
        val remoteShutdown = readLightningMessage() as Shutdown
        // We cannot convert the closing transactions created with the old closing protocol to the new one.
        // We simply ignore them, which will lead to a force-close if one of the proposed transactions is published.
        readCollection {
            readCollection {
                readClosingTx() // unsigned closing tx
                readDelimitedByteArray() // closing_signed message
            }.toList()
        }.toList()
        val bestUnpublishedClosingTx = readNullable { readClosingTx() }
        val closeCommand = readNullable {
            // We used to store three closing feerates for fee range negotiation.
            val preferred = FeeratePerKw(readNumber().sat)
            readNumber()
            readNumber()
            ChannelCommand.Close.MutualClose(CompletableDeferred(), localShutdown.scriptPubKey, preferred)
        }
        return Negotiating(
            commitments,
            remoteCommitNonces = mapOf(),
            localShutdown.scriptPubKey,
            remoteShutdown.scriptPubKey,
            listOf(),
            listOfNotNull(bestUnpublishedClosingTx),
            waitingSinceBlock = 0,
            closeCommand,
            localCloseeNonce = null,
            remoteCloseeNonce = null,
            localCloserNonces = null
        )
    }

    private fun Input.readNegotiating(): Negotiating = Negotiating(
        commitments = readCommitments(),
        localScript = readDelimitedByteArray().byteVector(),
        remoteScript = readDelimitedByteArray().byteVector(),
        proposedClosingTxs = readCollection {
            Transactions.ClosingTxs(
                readNullable { readClosingTx() },
                readNullable { readClosingTx() },
                readNullable { readClosingTx() },
            )
        }.toList(),
        publishedClosingTxs = readCollection { readClosingTx() }.toList(),
        waitingSinceBlock = readNumber(),
        closeCommand = readNullable { readCloseCommand() },
        remoteCommitNonces = mapOf(), localCloseeNonce = null, remoteCloseeNonce = null, localCloserNonces = null
    )

    private fun Input.readClosing(): Closing = Closing(
        commitments = readCommitments(),
        waitingSinceBlock = readNumber(),
        mutualCloseProposed = readCollection { readClosingTx() }.toList(),
        mutualClosePublished = readCollection { readClosingTx() }.toList(),
        localCommitPublished = readNullable { readLocalCommitPublished() },
        remoteCommitPublished = readNullable { readRemoteCommitPublished() },
        nextRemoteCommitPublished = readNullable { readRemoteCommitPublished() },
        futureRemoteCommitPublished = readNullable { readRemoteCommitPublished() },
        revokedCommitPublished = readCollection { readRevokedCommitPublished() }.toList()
    )

    private fun Input.readLocalCommitPublished(): LocalCommitPublished {
        val commitTx = readTransaction()
        val localOutput = readNullable { readForceCloseTransactionInputInfo().outPoint }
        val (incomingHtlcs, outgoingHtlcs) = readLocalHtlcTransactions()
        val htlcDelayedOutputs = readCollection { readForceCloseTransactionInputInfo().outPoint }.toSet()
        val anchorOutput = readCollection { readForceCloseTransactionInputInfo().outPoint }.firstOrNull()
        val irrevocablySpent = readIrrevocablySpent()
        return LocalCommitPublished(
            commitTx = commitTx,
            localOutput = localOutput,
            anchorOutput = anchorOutput,
            incomingHtlcs = incomingHtlcs,
            outgoingHtlcs = outgoingHtlcs,
            htlcDelayedOutputs = htlcDelayedOutputs,
            irrevocablySpent = irrevocablySpent
        )
    }

    private fun Input.readRemoteCommitPublished(): RemoteCommitPublished {
        val commitTx = readTransaction()
        val localOutput = readNullable { readForceCloseTransactionInputInfo().outPoint }
        val (incomingHtlcs, outgoingHtlcs) = readRemoteHtlcTransactions()
        val anchorOutput = readCollection { readForceCloseTransactionInputInfo().outPoint }.firstOrNull()
        val irrevocablySpent = readIrrevocablySpent()
        return RemoteCommitPublished(
            commitTx = commitTx,
            localOutput = localOutput,
            anchorOutput = anchorOutput,
            incomingHtlcs = incomingHtlcs,
            outgoingHtlcs = outgoingHtlcs,
            irrevocablySpent = irrevocablySpent
        )
    }

    private fun Input.readRevokedCommitPublished(): RevokedCommitPublished {
        val commitTx = readTransaction()
        val remotePerCommitmentSecret = PrivateKey(readByteVector32())
        val localOutput = readNullable { readForceCloseTransactionInputInfo().outPoint }
        val remoteOutput = readNullable { readForceCloseTransactionInputInfo().outPoint }
        val htlcOutputs = readCollection { readForceCloseTransactionInputInfo().outPoint }.toSet()
        val htlcDelayedOutputs = readCollection { readForceCloseTransactionInputInfo().outPoint }.toSet()
        val irrevocablySpent = readIrrevocablySpent()
        return RevokedCommitPublished(
            commitTx = commitTx,
            remotePerCommitmentSecret = remotePerCommitmentSecret,
            localOutput = localOutput,
            remoteOutput = remoteOutput,
            htlcOutputs = htlcOutputs,
            htlcDelayedOutputs = htlcDelayedOutputs,
            irrevocablySpent = irrevocablySpent
        )
    }

    private fun Input.readIrrevocablySpent(): Map<OutPoint, Transaction> = readCollection {
        readOutPoint() to readTransaction()
    }.toMap()

    private fun Input.readWaitForRemotePublishFutureCommitment(): WaitForRemotePublishFutureCommitment = WaitForRemotePublishFutureCommitment(
        commitments = readCommitments(),
        remoteChannelReestablish = readLightningMessage() as ChannelReestablish,
        remoteCommitNonces = mapOf()
    )

    private fun Input.readClosed(): Closed = Closed(
        state = readClosing()
    )

    private fun Input.readSharedFundingInput(): SharedFundingInput = when (val discriminator = read()) {
        0x01 -> SharedFundingInput(
            info = readInputInfo(),
            fundingTxIndex = readNumber(),
            remoteFundingPubkey = readPublicKey(),
            commitmentFormat = Transactions.CommitmentFormat.AnchorOutputs,
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
        commitmentFormat = Transactions.CommitmentFormat.AnchorOutputs,
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

    private fun Input.readUnsignedLocalCommitWithHtlcs(): InteractiveTxSigningSession.Companion.UnsignedLocalCommit {
        val index = readNumber()
        val spec = readCommitmentSpecWithHtlcs()
        val commitTxId = readCommitTxId()
        readCollection { skipHtlcTx() } // htlc transactions
        return InteractiveTxSigningSession.Companion.UnsignedLocalCommit(index, spec, commitTxId)
    }

    private fun Input.readUnsignedLocalCommitWithoutHtlcs(htlcs: Set<DirectedHtlc>): InteractiveTxSigningSession.Companion.UnsignedLocalCommit {
        val index = readNumber()
        val spec = readCommitmentSpecWithoutHtlcs(htlcs)
        val commitTxId = readCommitTxId()
        readCollection { skipHtlcTx() } // htlc transactions
        return InteractiveTxSigningSession.Companion.UnsignedLocalCommit(index, spec, commitTxId)
    }

    private fun Input.readCommitTxId(): TxId {
        require(read() == 0x00) // legacy discriminator for commit tx
        readInputInfoWithRedeemScript()
        val commitTx = readTransaction()
        return commitTx.txid
    }

    private fun Input.readLocalCommitWithHtlcs(remoteFundingPubKey: PublicKey): LocalCommit {
        val index = readNumber()
        val spec = readCommitmentSpecWithHtlcs()
        val (_, commitTxId, remoteSig) = readRemoteCommitSig(remoteFundingPubKey)
        val htlcSigs = readHtlcRemoteSigs()
        return LocalCommit(
            index = index,
            spec = spec,
            txId = commitTxId,
            remoteSig = ChannelSpendSignature.IndividualSignature(remoteSig),
            htlcRemoteSigs = htlcSigs,
        )
    }

    private fun Input.readLocalCommitWithoutHtlcs(htlcs: Set<DirectedHtlc>, remoteFundingPubKey: PublicKey): Pair<Transactions.InputInfo, LocalCommit> {
        val index = readNumber()
        val spec = readCommitmentSpecWithoutHtlcs(htlcs)
        val (commitInput, commitTxId, remoteSig) = readRemoteCommitSig(remoteFundingPubKey)
        val htlcSigs = readHtlcRemoteSigs()
        val localCommit = LocalCommit(
            index = index,
            spec = spec,
            txId = commitTxId,
            remoteSig = ChannelSpendSignature.IndividualSignature(remoteSig),
            htlcRemoteSigs = htlcSigs,
        )
        return Pair(commitInput, localCommit)
    }

    private fun Input.readRemoteCommitSig(remoteFundingPubKey: PublicKey): Triple<Transactions.InputInfo, TxId, ByteVector64> {
        require(read() == 0x00) // legacy discriminator for commit tx
        val (commitInput, redeemScript) = readInputInfoWithRedeemScript()
        val commitTx = readTransaction()
        val remoteSig = extractRemoteCommitSig(commitTx, redeemScript, remoteFundingPubKey)
        return Triple(commitInput, commitTx.txid, remoteSig)
    }

    private fun Input.readHtlcRemoteSigs(): List<ByteVector64> {
        return readCollection {
            skipHtlcTx() // we previously stored the fully signed HTLC transaction, which we now ignore
            readByteVector64() // local_sig
            readByteVector64() // remote_sig
        }.toList()
    }

    // We previously stored the signed commit tx: we need to extract the remote sig from its witness.
    private fun extractRemoteCommitSig(commitTx: Transaction, redeemScript: ByteVector, remoteFundingPubKey: PublicKey): ByteVector64 {
        val script = Script.parse(redeemScript)
        val pubkey1 = PublicKey((script[1] as OP_PUSHDATA).data)
        val witness = commitTx.txIn.first().witness
        return when {
            remoteFundingPubKey == pubkey1 -> Crypto.der2compact(witness.stack[1].toByteArray())
            else -> Crypto.der2compact(witness.stack[2].toByteArray())
        }
    }

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

    private fun Input.readInteractiveTxSigningSession(htlcs: Set<DirectedHtlc>, localCommitParams: CommitParams, remoteCommitParams: CommitParams): InteractiveTxSigningSession {
        val fundingParams = readInteractiveTxParams()
        val fundingTxIndex = readNumber()
        val fundingTx = readSignedSharedTransaction() as PartiallySignedSharedTransaction
        val (localCommit, remoteCommit) = when (val discriminator = read()) {
            0 -> Pair(Either.Left(readUnsignedLocalCommitWithHtlcs()), readRemoteCommitWithHtlcs())
            1 -> Pair(Either.Right(readLocalCommitWithHtlcs(fundingParams.remoteFundingPubkey)), readRemoteCommitWithHtlcs())
            2 -> {
                skipLegacyLiquidityLease()
                Pair(Either.Left(readUnsignedLocalCommitWithHtlcs()), readRemoteCommitWithHtlcs())
            }
            3 -> {
                skipLegacyLiquidityLease()
                Pair(Either.Right(readLocalCommitWithHtlcs(fundingParams.remoteFundingPubkey)), readRemoteCommitWithHtlcs())
            }
            4 -> Pair(Either.Left(readUnsignedLocalCommitWithoutHtlcs(htlcs)), readRemoteCommitWithoutHtlcs(htlcs))
            5 -> Pair(Either.Right(readLocalCommitWithoutHtlcs(htlcs, fundingParams.remoteFundingPubkey).second), readRemoteCommitWithoutHtlcs(htlcs))
            else -> error("unknown discriminator $discriminator for class ${InteractiveTxSigningSession::class}")
        }
        return InteractiveTxSigningSession(fundingParams, fundingTxIndex, fundingTx, localCommitParams, localCommit, remoteCommitParams, remoteCommit, null)
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

    private fun Input.readLocalParams(): Pair<LocalChannelParams, CommitParams> {
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
        val channelParams = LocalChannelParams(nodeId, fundingKeyPath, isChannelOpener, payCommitTxFees, defaultFinalScriptPubKey, features)
        val commitParams = CommitParams(dustLimit, maxHtlcValueInFlightMsat, htlcMinimum, toSelfDelay, maxAcceptedHtlcs)
        return Pair(channelParams, commitParams)
    }

    private fun Input.readRemoteParams(): Pair<RemoteChannelParams, CommitParams> {
        val nodeId = readPublicKey()
        val dustLimit = readNumber().sat
        val maxHtlcValueInFlightMsat = readNumber()
        val htlcMinimum = readNumber().msat
        val toSelfDelay = CltvExpiryDelta(readNumber().toInt())
        val maxAcceptedHtlcs = readNumber().toInt()
        val revocationBasepoint = readPublicKey()
        val paymentBasepoint = readPublicKey()
        val delayedPaymentBasepoint = readPublicKey()
        val htlcBasepoint = readPublicKey()
        val features = Features(readDelimitedByteArray().toByteVector())
        val channelParams = RemoteChannelParams(nodeId, revocationBasepoint, paymentBasepoint, delayedPaymentBasepoint, htlcBasepoint, features)
        val commitParams = CommitParams(dustLimit, maxHtlcValueInFlightMsat, htlcMinimum, toSelfDelay, maxAcceptedHtlcs)
        return Pair(channelParams, commitParams)
    }

    private fun Input.readChannelFlags(): ChannelFlags {
        val flags = readNumber().toInt()
        return ChannelFlags(announceChannel = flags.and(1) != 0, nonInitiatorPaysCommitFees = flags.and(2) != 0)
    }

    private fun Input.readChannelParams(): Triple<ChannelParams, CommitParams, CommitParams> {
        val channelId = readByteVector32()
        val channelConfig = ChannelConfig(readDelimitedByteArray())
        val channelFeatures = ChannelFeatures(Features(readDelimitedByteArray()).activated.keys)
        val (localChannelParams, localCommitParams) = readLocalParams()
        val (remoteChannelParams, remoteCommitParams) = readRemoteParams()
        val channelFlags = readChannelFlags()
        val channelParams = ChannelParams(channelId, channelConfig, channelFeatures, localChannelParams, remoteChannelParams, channelFlags)
        // We need to use the remote to_self_delay for our commitment, and vice-versa.
        val localToRemoteDelay = localCommitParams.toSelfDelay
        val remoteToRemoteDelay = remoteCommitParams.toSelfDelay
        val localCommitParams1 = localCommitParams.copy(toSelfDelay = remoteToRemoteDelay)
        val remoteCommitParams1 = remoteCommitParams.copy(toSelfDelay = localToRemoteDelay)
        return Triple(channelParams, localCommitParams1, remoteCommitParams1)
    }

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

    private fun Input.readCommitment(htlcs: Set<DirectedHtlc>, localCommitParams: CommitParams, remoteCommitParams: CommitParams): Commitment {
        val fundingTxIndex = readNumber()
        val remoteFundingPubkey = readPublicKey()
        val localFundingStatus = when (val discriminator = read()) {
            0x00 -> LocalFundingStatus.UnconfirmedFundingTx(
                sharedTx = readSignedSharedTransaction(),
                fundingParams = readInteractiveTxParams(),
                createdAt = readNumber()
            )
            0x01 -> {
                val signedTx = readTransaction()
                val fee = readNumber().sat
                // We previously didn't store the tx_signatures after the transaction was confirmed.
                // It is only used to be retransmitted on reconnection if our peer had not received it.
                // This happens very rarely in practice, so putting dummy values here shouldn't be an issue.
                val localSigs = TxSignatures(ByteVector32.Zeroes, TxId(ByteVector32.Zeroes), listOf())
                // We previously didn't store the short_channel_id in the commitment object.
                // We will fetch the funding transaction on restart to set it to the correct value.
                val shortChannelId = ShortChannelId(0)
                // We don't know yet which output of the funding transaction is the channel output: this will be fixed below.
                LocalFundingStatus.ConfirmedFundingTx(signedTx.txIn.map { it.outPoint }, signedTx.txOut.first(), fee, localSigs, shortChannelId)
            }
            0x02 -> {
                val signedTx = readTransaction()
                val fee = readNumber().sat
                val localSigs = readLightningMessage() as TxSignatures
                // We previously didn't store the short_channel_id in the commitment object.
                // We will fetch the funding transaction on restart to set it to the correct value.
                val shortChannelId = ShortChannelId(0)
                // We don't know yet which output of the funding transaction is the channel output: this will be fixed below.
                LocalFundingStatus.ConfirmedFundingTx(signedTx.txIn.map { it.outPoint }, signedTx.txOut.first(), fee, localSigs, shortChannelId)
            }
            0x03 -> {
                val signedTx = readTransaction()
                val fee = readNumber().sat
                val localSigs = readLightningMessage() as TxSignatures
                val shortChannelId = ShortChannelId(readNumber())
                // We don't know yet which output of the funding transaction is the channel output: this will be fixed below.
                LocalFundingStatus.ConfirmedFundingTx(signedTx.txIn.map { it.outPoint }, signedTx.txOut.first(), fee, localSigs, shortChannelId)
            }
            else -> error("unknown discriminator $discriminator for class ${LocalFundingStatus::class}")
        }
        val remoteFundingStatus = when (val discriminator = read()) {
            0x00 -> RemoteFundingStatus.NotLocked
            0x01 -> RemoteFundingStatus.Locked
            else -> error("unknown discriminator $discriminator for class ${RemoteFundingStatus::class}")
        }
        val (commitInput, localCommit) = readLocalCommitWithoutHtlcs(htlcs, remoteFundingPubkey)
        val remoteCommit = readRemoteCommitWithoutHtlcs(htlcs)
        val nextRemoteCommit = readNullable {
            readLightningMessage() as CommitSig // we included our previously sent commit_sig, which we now recompute
            readRemoteCommitWithoutHtlcs(htlcs)
        }
        // Now that we have extracted the funding output from the local commit, we make sure our localFundingStatus uses the right output.
        val localFundingStatus1 = when (localFundingStatus) {
            is LocalFundingStatus.ConfirmedFundingTx -> localFundingStatus.copy(txOut = commitInput.txOut)
            is LocalFundingStatus.UnconfirmedFundingTx -> localFundingStatus
        }
        return Commitment(
            fundingTxIndex = fundingTxIndex,
            fundingInput = commitInput.outPoint,
            fundingAmount = commitInput.txOut.amount,
            remoteFundingPubkey = remoteFundingPubkey,
            localFundingStatus = localFundingStatus1,
            remoteFundingStatus = remoteFundingStatus,
            commitmentFormat = Transactions.CommitmentFormat.AnchorOutputs,
            localCommitParams = localCommitParams,
            localCommit = localCommit,
            remoteCommitParams = remoteCommitParams,
            remoteCommit = remoteCommit,
            nextRemoteCommit = nextRemoteCommit
        )
    }

    private fun Input.readCommitments(): Commitments {
        val (channelParams, localCommitParams, remoteCommitParams) = readChannelParams()
        val changes = readCommitmentChanges()
        // When multiple commitments are active, htlcs are shared between all of these commitments, so we serialize them separately.
        // The direction we use is from our local point of view: we use sets, which deduplicates htlcs that are in both local and remote commitments.
        val htlcs = readCollection { readDirectedHtlc() }.toSet()
        val active = readCollection { readCommitment(htlcs, localCommitParams, remoteCommitParams) }.toList()
        val inactive = readCollection { readCommitment(htlcs, localCommitParams, remoteCommitParams) }.toList()
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
        return Commitments(channelParams, changes, active, inactive, payments, remoteNextCommitInfo, remotePerCommitmentSecrets)
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

    private fun Input.readInputInfo(): Transactions.InputInfo = readInputInfoWithRedeemScript().first

    private fun Input.readInputInfoWithRedeemScript(): Pair<Transactions.InputInfo, ByteVector> {
        val outPoint = readOutPoint()
        val txOut = TxOut.read(readDelimitedByteArray())
        val redeemScript = readDelimitedByteArray().toByteVector()
        return Pair(Transactions.InputInfo(outPoint, txOut), redeemScript)
    }

    private fun Input.readOutPoint(): OutPoint = OutPoint.read(readDelimitedByteArray())

    private fun Input.readTransaction(): Transaction = Transaction.read(readDelimitedByteArray())

    private fun Input.readLocalHtlcTransactions(): Pair<Map<OutPoint, Long>, Map<OutPoint, Long>> {
        val incomingHtlcs = mutableMapOf<OutPoint, Long>()
        val outgoingHtlcs = mutableMapOf<OutPoint, Long>()
        readCollection {
            val outpoint = readOutPoint()
            readNullable {
                when (val discriminator = read()) {
                    0x01 -> {
                        readInputInfo()
                        readTransaction() // htlc-success transaction
                        readByteVector32() // payment_hash
                        val htlcId = readNumber()
                        incomingHtlcs[outpoint] = htlcId
                    }
                    0x02 -> {
                        readInputInfo()
                        readTransaction() // htlc-timeout
                        val htlcId = readNumber()
                        outgoingHtlcs[outpoint] = htlcId
                    }
                    else -> error("unknown discriminator $discriminator for legacy HTLC transactions")
                }
            }
        }
        return Pair(incomingHtlcs, outgoingHtlcs)
    }

    private fun Input.readRemoteHtlcTransactions(): Pair<Map<OutPoint, Long>, Map<OutPoint, Long>> {
        val incomingHtlcs = mutableMapOf<OutPoint, Long>()
        val outgoingHtlcs = mutableMapOf<OutPoint, Long>()
        readCollection {
            val outpoint = readOutPoint()
            readNullable {
                when (val discriminator = read()) {
                    0x03 -> {
                        readInputInfo()
                        readTransaction() // claim-htlc-success transaction
                        val htlcId = readNumber()
                        incomingHtlcs[outpoint] = htlcId
                    }
                    0x04 -> {
                        readInputInfo()
                        readTransaction() // claim-htlc-timeout transaction
                        val htlcId = readNumber()
                        outgoingHtlcs[outpoint] = htlcId
                    }
                    else -> error("unknown discriminator $discriminator for legacy Claim-HTLC transactions")
                }
            }
        }
        return Pair(incomingHtlcs, outgoingHtlcs)
    }

    private fun Input.skipHtlcTx() {
        when (val discriminator = read()) {
            0x01 -> {
                readInputInfo() // input
                readTransaction() // htlc-success tx
                readByteVector32() // payment_hash
                readNumber() // htlc_id
            }
            0x02 -> {
                readInputInfo() // input
                readTransaction() // htlc-timeout tx
                readNumber() // htlc_id
            }
            else -> error("unknown discriminator $discriminator for ignored HTLC transactions")
        }
    }

    private fun Input.readForceCloseTransactionInputInfo(): Transactions.InputInfo = when (val discriminator = read()) {
        0x00, 0x05, 0x06, 0x07, 0x09, 0x10, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e -> {
            val input = readInputInfo()
            readTransaction() // we ignore the serialized transaction
            input
        }
        else -> error("unknown discriminator $discriminator for legacy force-close transactions")
    }

    private fun Input.readClosingTx(): Transactions.ClosingTx = when (val discriminator = read()) {
        0x0d -> Transactions.ClosingTx(input = readInputInfo(), tx = readTransaction(), toLocalOutputIndex = readNullable { readNumber().toInt() })
        else -> error("unknown discriminator $discriminator for class ${Transactions.ClosingTx::class}")
    }

    private fun Input.readCloseCommand(): ChannelCommand.Close.MutualClose = ChannelCommand.Close.MutualClose(
        replyTo = CompletableDeferred(),
        scriptPubKey = readNullable { readDelimitedByteArray().toByteVector() },
        feerate = FeeratePerKw(readNumber().sat),
    )
}