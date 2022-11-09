package fr.acinq.lightning.serialization.v4

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.Input
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Features
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.crypto.ShaChain
import fr.acinq.lightning.transactions.*
import fr.acinq.lightning.transactions.Transactions.TransactionWithInputInfo.*
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.*

object Deserialization {

    fun ByteArray.fromBinV4(): PersistedChannelState {
        val input = ByteArrayInput(this)
        val version = input.read()
        require(version == Serialization.versionMagic) { "incorrect version $version, expected ${Serialization.versionMagic}" }
        return input.readPersistedChannelState()
    }

    private fun Input.readPersistedChannelState(): PersistedChannelState = when (val discriminator = read()) {
        0x08 -> readLegacyWaitForFundingConfirmed()
        0x09 -> readLegacyWaitForFundingLocked()
        0x00 -> readWaitForFundingConfirmed()
        0x01 -> readWaitForChannelReady()
        0x02 -> readNormal()
        0x03 -> readShuttingDown()
        0x04 -> readNegotiating()
        0x05 -> readClosing()
        0x06 -> readWaitForRemotePublishFutureCommitment()
        0x07 -> readClosed()
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

    private fun Input.readWaitForFundingConfirmed() = WaitForFundingConfirmed(
        commitments = readCommitments(),
        fundingParams = readInteractiveTxParams(),
        localPushAmount = readNumber().msat,
        remotePushAmount = readNumber().msat,
        fundingTx = readSignedSharedTransaction(),
        previousFundingTxs = readCollection { readSignedSharedTransaction() to readCommitments() }.toList(),
        waitingSinceBlock = readNumber(),
        deferred = readNullable { readLightningMessage() as ChannelReady }
    )

    private fun Input.readWaitForChannelReady() = WaitForChannelReady(
        commitments = readCommitments(),
        fundingParams = readInteractiveTxParams(),
        fundingTx = readSignedSharedTransaction(),
        shortChannelId = ShortChannelId(readNumber()),
        lastSent = readLightningMessage() as ChannelReady
    )

    private fun Input.readNormal(): Normal = Normal(
        commitments = readCommitments(),
        shortChannelId = ShortChannelId(readNumber()),
        buried = readBoolean(),
        channelAnnouncement = readNullable { readLightningMessage() as ChannelAnnouncement },
        channelUpdate = readLightningMessage() as ChannelUpdate,
        remoteChannelUpdate = readNullable { readLightningMessage() as ChannelUpdate },
        localShutdown = readNullable { readLightningMessage() as Shutdown },
        remoteShutdown = readNullable { readLightningMessage() as Shutdown },
        closingFeerates = readNullable { readClosingFeerates() }
    )

    private fun Input.readShuttingDown(): ShuttingDown = ShuttingDown(
        commitments = readCommitments(),
        localShutdown = readLightningMessage() as Shutdown,
        remoteShutdown = readLightningMessage() as Shutdown,
        closingFeerates = readNullable { readClosingFeerates() }
    )

    private fun Input.readNegotiating(): Negotiating = Negotiating(
        commitments = readCommitments(),
        localShutdown = readLightningMessage() as Shutdown,
        remoteShutdown = readLightningMessage() as Shutdown,
        closingTxProposed = readCollection {
            readCollection {
                ClosingTxProposed(
                    unsignedTx = readTransactionWithInputInfo() as ClosingTx,
                    localClosingSigned = readLightningMessage() as ClosingSigned
                )
            }.toList()
        }.toList(),
        bestUnpublishedClosingTx = readNullable { readTransactionWithInputInfo() as ClosingTx },
        closingFeerates = readNullable { readClosingFeerates() }
    )

    private fun Input.readClosing(): Closing = Closing(
        commitments = readCommitments(),
        fundingTx = readNullable { readTransaction() },
        waitingSinceBlock = readNumber(),
        alternativeCommitments = readCollection { readCommitments() }.toList(),
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
        claimMainOutputTx = readNullable { readTransactionWithInputInfo() as ClaimRemoteCommitMainOutputTx },
        claimHtlcTxs = readCollection { readOutPoint() to readNullable { readTransactionWithInputInfo() as ClaimHtlcTx } }.toMap(),
        claimAnchorTxs = readCollection { readTransactionWithInputInfo() as ClaimAnchorOutputTx }.toList(),
        irrevocablySpent = readIrrevocablySpent()
    )

    private fun Input.readRevokedCommitPublished(): RevokedCommitPublished = RevokedCommitPublished(
        commitTx = readTransaction(),
        remotePerCommitmentSecret = PrivateKey(readByteVector32()),
        claimMainOutputTx = readNullable { readTransactionWithInputInfo() as ClaimRemoteCommitMainOutputTx },
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

    private fun Input.readInteractiveTxParams() = InteractiveTxParams(
        channelId = readByteVector32(),
        isInitiator = readBoolean(),
        localAmount = readNumber().sat,
        remoteAmount = readNumber().sat,
        fundingPubkeyScript = readDelimitedByteArray().toByteVector(),
        lockTime = readNumber(),
        dustLimit = readNumber().sat,
        targetFeerate = FeeratePerKw(readNumber().sat)
    )

    private fun Input.readRemoteTxAddInput() = RemoteTxAddInput(
        serialId = readNumber(),
        outPoint = readOutPoint(),
        txOut = TxOut.read(readDelimitedByteArray()),
        sequence = readNumber().toUInt()
    )

    private fun Input.readRemoteTxAddOutput() = RemoteTxAddOutput(
        serialId = readNumber(),
        amount = readNumber().sat,
        pubkeyScript = readDelimitedByteArray().toByteVector()
    )

    private fun Input.readSignedSharedTransaction() = when (val discriminator = read()) {
        0x00 -> PartiallySignedSharedTransaction(
            tx = SharedTransaction(
                localInputs = readCollection { readLightningMessage() as TxAddInput }.toList(),
                remoteInputs = readCollection { readRemoteTxAddInput() }.toList(),
                localOutputs = readCollection { readLightningMessage() as TxAddOutput }.toList(),
                remoteOutputs = readCollection { readRemoteTxAddOutput() }.toList(),
                lockTime = readNumber()
            ),
            localSigs = readLightningMessage() as TxSignatures
        )
        0x01 -> FullySignedSharedTransaction(
            tx = SharedTransaction(
                localInputs = readCollection { readLightningMessage() as TxAddInput }.toList(),
                remoteInputs = readCollection { readRemoteTxAddInput() }.toList(),
                localOutputs = readCollection { readLightningMessage() as TxAddOutput }.toList(),
                remoteOutputs = readCollection { readRemoteTxAddOutput() }.toList(),
                lockTime = readNumber()
            ),
            localSigs = readLightningMessage() as TxSignatures,
            remoteSigs = readLightningMessage() as TxSignatures
        )
        else -> error("unknown discriminator $discriminator for class ${SignedSharedTransaction::class}")
    }

    private fun Input.readCommitments(): Commitments = Commitments(
        channelConfig = ChannelConfig(readDelimitedByteArray()),
        channelFeatures = ChannelFeatures(Features(readDelimitedByteArray()).activated.keys),
        localParams = LocalParams(
            nodeId = readPublicKey(),
            fundingKeyPath = KeyPath(readCollection { readNumber() }.toList()),
            dustLimit = readNumber().sat,
            maxHtlcValueInFlightMsat = readNumber(),
            htlcMinimum = readNumber().msat,
            toSelfDelay = CltvExpiryDelta(readNumber().toInt()),
            maxAcceptedHtlcs = readNumber().toInt(),
            isInitiator = readBoolean(),
            defaultFinalScriptPubKey = readDelimitedByteArray().toByteVector(),
            features = Features(readDelimitedByteArray().toByteVector())
        ),
        remoteParams = RemoteParams(
            nodeId = readPublicKey(),
            dustLimit = readNumber().sat,
            maxHtlcValueInFlightMsat = readNumber(),
            htlcMinimum = readNumber().msat,
            toSelfDelay = CltvExpiryDelta(readNumber().toInt()),
            maxAcceptedHtlcs = readNumber().toInt(),
            fundingPubKey = readPublicKey(),
            revocationBasepoint = readPublicKey(),
            paymentBasepoint = readPublicKey(),
            delayedPaymentBasepoint = readPublicKey(),
            htlcBasepoint = readPublicKey(),
            features = Features(readDelimitedByteArray().toByteVector())
        ),
        channelFlags = readNumber().toByte(),
        localCommit = LocalCommit(
            index = readNumber(),
            spec = readCommitmentSpec(),
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
        ),
        remoteCommit = RemoteCommit(
            index = readNumber(),
            spec = readCommitmentSpec(),
            txid = readByteVector32(),
            remotePerCommitmentPoint = readPublicKey()
        ),
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
        payments = readCollection {
            readNumber() to UUID.fromString(readString())
        }.toMap(),
        remoteNextCommitInfo = readEither(
            readLeft = {
                WaitingForRevocation(
                    nextRemoteCommit = RemoteCommit(
                        index = readNumber(),
                        spec = readCommitmentSpec(),
                        txid = readByteVector32(),
                        remotePerCommitmentPoint = readPublicKey()
                    ),
                    sent = readLightningMessage() as CommitSig,
                    sentAfterLocalCommitIndex = readNumber(),
                    reSignAsap = readBoolean()
                )
            },
            readRight = { readPublicKey() },
        ),
        commitInput = readInputInfo(),
        remotePerCommitmentSecrets = ShaChain(
            knownHashes = readCollection {
                readCollection { readBoolean() }.toList() to readByteVector32()
            }.toMap(),
            lastIndex = readNullable { readNumber() }
        ),
        channelId = readByteVector32(),
        remoteChannelData = EncryptedChannelData(readDelimitedByteArray().toByteVector())
    )

    private fun Input.readCommitmentSpec(): CommitmentSpec = CommitmentSpec(
        htlcs = readCollection {
            when (val discriminator = read()) {
                0 -> IncomingHtlc(readLightningMessage() as UpdateAddHtlc)
                1 -> OutgoingHtlc(readLightningMessage() as UpdateAddHtlc)
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
        0x08 -> ClaimRemoteCommitMainOutputTx.ClaimP2WPKHOutputTx(input = readInputInfo(), tx = readTransaction())
        0x09 -> ClaimRemoteCommitMainOutputTx.ClaimRemoteDelayedOutputTx(input = readInputInfo(), tx = readTransaction())
        0x10 -> ClaimLocalDelayedOutputTx(input = readInputInfo(), tx = readTransaction())
        0x0a -> MainPenaltyTx(input = readInputInfo(), tx = readTransaction())
        0x0b -> HtlcPenaltyTx(input = readInputInfo(), tx = readTransaction())
        0x0c -> ClaimHtlcDelayedOutputPenaltyTx(input = readInputInfo(), tx = readTransaction())
        0x0d -> ClosingTx(input = readInputInfo(), tx = readTransaction(), toLocalIndex = readNullable { readNumber().toInt() })
        else -> error("unknown discriminator $discriminator for class ${Transactions.TransactionWithInputInfo::class}")
    }

    private fun Input.readClosingFeerates(): ClosingFeerates = ClosingFeerates(
        preferred = FeeratePerKw(readNumber().sat),
        min = FeeratePerKw(readNumber().sat),
        max = FeeratePerKw(readNumber().sat)
    )

    private fun Input.readNumber(): Long = LightningCodecs.bigSize(this)

    private fun Input.readBoolean(): Boolean = read() == 1

    private fun Input.readString(): String = readDelimitedByteArray().decodeToString()

    private fun Input.readByteVector32(): ByteVector32 = ByteVector32(ByteArray(32).also { read(it, 0, it.size) })

    private fun Input.readByteVector64(): ByteVector64 = ByteVector64(ByteArray(64).also { read(it, 0, it.size) })

    private fun Input.readPublicKey() = PublicKey(ByteArray(33).also { read(it, 0, it.size) })

    private fun Input.readDelimitedByteArray(): ByteArray {
        val size = readNumber().toInt()
        return ByteArray(size).also { read(it, 0, size) }
    }

    private fun Input.readLightningMessage() = LightningMessage.decode(readDelimitedByteArray())

    private fun <T> Input.readCollection(readElem: () -> T): Collection<T> {
        val size = readNumber()
        return buildList {
            repeat(size.toInt()) {
                add(readElem())
            }
        }
    }

    private fun <L, R> Input.readEither(readLeft: () -> L, readRight: () -> R): Either<L, R> = when (read()) {
        0 -> Either.Left(readLeft())
        else -> Either.Right(readRight())
    }

    private fun <T : Any> Input.readNullable(readNotNull: () -> T): T? = when (read()) {
        1 -> readNotNull()
        else -> null
    }

}