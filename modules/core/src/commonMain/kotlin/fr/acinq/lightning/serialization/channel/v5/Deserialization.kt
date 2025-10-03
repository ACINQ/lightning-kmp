package fr.acinq.lightning.serialization.channel.v5

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.Input
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
import fr.acinq.lightning.serialization.InputExtensions.readIndividualNonce
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
        0x00 -> readWaitForFundingSigned()
        0x10 -> readWaitForFundingConfirmed()
        0x20 -> readWaitForChannelReady()
        0x30 -> readNormal()
        0x40 -> readShuttingDown()
        0x50 -> readNegotiating()
        0x60 -> readClosing()
        0x70 -> readWaitForRemotePublishFutureCommitment()
        0x80 -> readClosed()
        else -> error("unknown discriminator $discriminator for class ${PersistedChannelState::class}")
    }

    private fun Input.readWaitForFundingSigned() = WaitForFundingSigned(
        channelParams = readChannelParams(),
        signingSession = readInteractiveTxSigningSession(emptySet()),
        remoteSecondPerCommitmentPoint = readPublicKey(),
        liquidityPurchase = readNullable { readLiquidityPurchase() },
        channelOrigin = readNullable { readChannelOrigin() },
        remoteCommitNonces = mapOf()
    )

    private fun Input.readWaitForFundingConfirmed() = WaitForFundingConfirmed(
        commitments = readCommitments(),
        waitingSinceBlock = readNumber(),
        deferred = readNullable { readLightningMessage() as ChannelReady },
        rbfStatus = when (val discriminator = read()) {
            0x00 -> RbfStatus.None
            0x01 -> RbfStatus.WaitingForSigs(readInteractiveTxSigningSession(emptySet()))
            else -> error("unknown discriminator $discriminator for class ${RbfStatus::class}")
        },
        remoteCommitNonces = mapOf()
    )

    private fun Input.readWaitForChannelReady() = WaitForChannelReady(
        commitments = readCommitments(),
        shortChannelId = ShortChannelId(readNumber()),
        lastSent = readLightningMessage() as ChannelReady,
        remoteCommitNonces = mapOf()
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
            remoteCommitNonces = mapOf(), localCloseeNonce = null, localCloserNonces = null, remoteCloseeNonce = null
        )
    }

    private fun Input.readShuttingDown(): ShuttingDown = ShuttingDown(
        commitments = readCommitments(),
        localShutdown = readLightningMessage() as Shutdown,
        remoteShutdown = readLightningMessage() as Shutdown,
        closeCommand = readNullable { readCloseCommand() },
        remoteCommitNonces = mapOf(),
        localCloseeNonce = null
    )

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
        remoteCommitNonces = mapOf(), localCloserNonces = null, remoteCloseeNonce = null, localCloseeNonce = null
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

    private fun Input.readClosingTx(): Transactions.ClosingTx = when (val discriminator = read()) {
        0x01 -> Transactions.ClosingTx(
            input = readInputInfo(),
            tx = readTransaction(),
            toLocalOutputIndex = readNullable { readNumber().toInt() },
        )
        else -> error("unknown discriminator $discriminator for class ${Transactions.ClosingTx::class}")
    }

    private fun Input.readLocalCommitPublished(): LocalCommitPublished = LocalCommitPublished(
        commitTx = readTransaction(),
        localOutput = readNullable { readOutPoint() },
        anchorOutput = readNullable { readOutPoint() },
        incomingHtlcs = readCollection { readOutPoint() to readNumber() }.toMap(),
        outgoingHtlcs = readCollection { readOutPoint() to readNumber() }.toMap(),
        htlcDelayedOutputs = readCollection { readOutPoint() }.toSet(),
        irrevocablySpent = readIrrevocablySpent()
    )

    private fun Input.readRemoteCommitPublished(): RemoteCommitPublished = RemoteCommitPublished(
        commitTx = readTransaction(),
        localOutput = readNullable { readOutPoint() },
        anchorOutput = readNullable { readOutPoint() },
        incomingHtlcs = readCollection { readOutPoint() to readNumber() }.toMap(),
        outgoingHtlcs = readCollection { readOutPoint() to readNumber() }.toMap(),
        irrevocablySpent = readIrrevocablySpent()
    )

    private fun Input.readRevokedCommitPublished(): RevokedCommitPublished = RevokedCommitPublished(
        commitTx = readTransaction(),
        remotePerCommitmentSecret = PrivateKey(readByteVector32()),
        localOutput = readNullable { readOutPoint() },
        remoteOutput = readNullable { readOutPoint() },
        htlcOutputs = readCollection { readOutPoint() }.toSet(),
        htlcDelayedOutputs = readCollection { readOutPoint() }.toSet(),
        irrevocablySpent = readIrrevocablySpent()
    )

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
            commitmentFormat = readCommitmentFormat(),
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
        commitmentFormat = readCommitmentFormat(),
        lockTime = readNumber(),
        dustLimit = readNumber().sat,
        targetFeerate = FeeratePerKw(readNumber().sat)
    )

    private fun Input.readSharedInteractiveTxInput() = when (val discriminator = read()) {
        0x01 -> InteractiveTxInput.Shared(
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

    private fun Input.readUnsignedLocalCommitWithoutHtlcs(htlcs: Set<DirectedHtlc>): InteractiveTxSigningSession.Companion.UnsignedLocalCommit = InteractiveTxSigningSession.Companion.UnsignedLocalCommit(
        index = readNumber(),
        spec = readCommitmentSpecWithoutHtlcs(htlcs),
        txId = readTxId(),
    )

    private fun Input.readLocalCommitWithoutHtlcs(htlcs: Set<DirectedHtlc>): LocalCommit = LocalCommit(
        index = readNumber(),
        spec = readCommitmentSpecWithoutHtlcs(htlcs),
        txId = readTxId(),
        remoteSig = readChannelSpendSignature(),
        htlcRemoteSigs = readCollection { readByteVector64() }.toList(),
    )

    private fun Input.readRemoteCommitWithoutHtlcs(htlcs: Set<DirectedHtlc>): RemoteCommit = RemoteCommit(
        index = readNumber(),
        spec = readCommitmentSpecWithoutHtlcs(htlcs.map { it.opposite() }.toSet()),
        txid = readTxId(),
        remotePerCommitmentPoint = readPublicKey()
    )

    private fun Input.readInteractiveTxSigningSession(htlcs: Set<DirectedHtlc>): InteractiveTxSigningSession = InteractiveTxSigningSession(
        fundingParams = readInteractiveTxParams(),
        fundingTx = readSignedSharedTransaction() as PartiallySignedSharedTransaction,
        localCommitParams = readCommitParams(),
        localCommit = when (val discriminator = read()) {
            0x01 -> Either.Left(readUnsignedLocalCommitWithoutHtlcs(htlcs))
            0x02 -> Either.Right(readLocalCommitWithoutHtlcs(htlcs))
            else -> error("unknown discriminator $discriminator for class ${InteractiveTxSigningSession::class}")
        },
        remoteCommitParams = readCommitParams(),
        remoteCommit = readRemoteCommitWithoutHtlcs(htlcs),
        nextRemoteCommitNonce = null
    )

    private fun Input.readChannelOrigin(): Origin = when (val discriminator = read()) {
        0x01 -> Origin.OffChainPayment(
            paymentPreimage = readByteVector32(),
            amountBeforeFees = readNumber().msat,
            fees = ChannelManagementFees(miningFee = readNumber().sat, serviceFee = readNumber().sat),
        )
        0x02 -> Origin.OnChainWallet(
            inputs = readCollection { readOutPoint() }.toSet(),
            amountBeforeFees = readNumber().msat,
            fees = ChannelManagementFees(miningFee = readNumber().sat, serviceFee = readNumber().sat),
        )
        else -> error("unknown discriminator $discriminator for class ${Origin::class}")
    }

    private fun Input.readLocalChannelParams(): LocalChannelParams {
        val nodeId = readPublicKey()
        val fundingKeyPath = KeyPath(readCollection { readNumber() }.toList())
        val flags = readNumber().toInt()
        val isChannelOpener = flags.and(1) != 0
        val payCommitTxFees = flags.and(2) != 0
        val defaultFinalScriptPubKey = readDelimitedByteArray().toByteVector()
        val features = Features(readDelimitedByteArray().toByteVector())
        return LocalChannelParams(nodeId, fundingKeyPath, isChannelOpener, payCommitTxFees, defaultFinalScriptPubKey, features)
    }

    private fun Input.readRemoteChannelParams(): RemoteChannelParams = RemoteChannelParams(
        nodeId = readPublicKey(),
        revocationBasepoint = readPublicKey(),
        paymentBasepoint = readPublicKey(),
        delayedPaymentBasepoint = readPublicKey(),
        htlcBasepoint = readPublicKey(),
        features = Features(readDelimitedByteArray().toByteVector())
    )

    private fun Input.readCommitParams(): CommitParams = CommitParams(
        dustLimit = readNumber().sat,
        maxHtlcValueInFlightMsat = readNumber(),
        htlcMinimum = readNumber().msat,
        toSelfDelay = CltvExpiryDelta(readNumber().toInt()),
        maxAcceptedHtlcs = readNumber().toInt(),
    )

    private fun Input.readChannelFlags(): ChannelFlags {
        val flags = readNumber().toInt()
        return ChannelFlags(announceChannel = flags.and(1) != 0, nonInitiatorPaysCommitFees = flags.and(2) != 0)
    }

    private fun Input.readChannelParams(): ChannelParams = ChannelParams(
        channelId = readByteVector32(),
        channelConfig = ChannelConfig(readDelimitedByteArray()),
        channelFeatures = ChannelFeatures(Features(readDelimitedByteArray()).activated.keys),
        localParams = readLocalChannelParams(),
        remoteParams = readRemoteChannelParams(),
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
        fundingInput = readOutPoint(),
        fundingAmount = readNumber().sat,
        remoteFundingPubkey = readPublicKey(),
        localFundingStatus = when (val discriminator = read()) {
            0x00 -> LocalFundingStatus.UnconfirmedFundingTx(
                sharedTx = readSignedSharedTransaction(),
                fundingParams = readInteractiveTxParams(),
                createdAt = readNumber()
            )
            0x01 -> LocalFundingStatus.ConfirmedFundingTx(
                spentInputs = readCollection { readOutPoint() }.toList(),
                txOut = TxOut.read(readDelimitedByteArray()),
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
        commitmentFormat = readCommitmentFormat(),
        localCommitParams = readCommitParams(),
        localCommit = readLocalCommitWithoutHtlcs(htlcs),
        remoteCommitParams = readCommitParams(),
        remoteCommit = readRemoteCommitWithoutHtlcs(htlcs),
        nextRemoteCommit = readNullable { readRemoteCommitWithoutHtlcs(htlcs) }
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
        return Commitments(params, changes, active, inactive, payments, remoteNextCommitInfo, remotePerCommitmentSecrets)
    }

    private fun Input.readDirectedHtlc(): DirectedHtlc = when (val discriminator = read()) {
        0 -> IncomingHtlc(readLightningMessage() as UpdateAddHtlc)
        1 -> OutgoingHtlc(readLightningMessage() as UpdateAddHtlc)
        else -> error("invalid discriminator $discriminator for class ${DirectedHtlc::class}")
    }

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
    )

    private fun Input.readOutPoint(): OutPoint = OutPoint.read(readDelimitedByteArray())

    private fun Input.readTransaction(): Transaction = Transaction.read(readDelimitedByteArray())

    private fun Input.readCommitmentFormat(): Transactions.CommitmentFormat = when (val discriminator = read()) {
        0x00 -> Transactions.CommitmentFormat.AnchorOutputs
        0x01 -> Transactions.CommitmentFormat.SimpleTaprootChannels
        else -> error("invalid discriminator $discriminator for class ${Transactions.CommitmentFormat::class}")
    }

    private fun Input.readChannelSpendSignature(): ChannelSpendSignature = when (val discriminator = read()) {
        0x00 -> ChannelSpendSignature.IndividualSignature(readByteVector64())
        0x01 -> ChannelSpendSignature.PartialSignatureWithNonce(readByteVector32(), readIndividualNonce())
        else -> error("invalid discriminator $discriminator for class ${ChannelSpendSignature::class}")
    }

    private fun Input.readCloseCommand(): ChannelCommand.Close.MutualClose = ChannelCommand.Close.MutualClose(
        replyTo = CompletableDeferred(),
        scriptPubKey = readNullable { readDelimitedByteArray().toByteVector() },
        feerate = FeeratePerKw(readNumber().sat),
    )

}