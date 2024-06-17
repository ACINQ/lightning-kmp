package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.utils.Either.Left
import fr.acinq.bitcoin.utils.Either.Right
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.crypto.RouteBlinding
import fr.acinq.lightning.io.OfferInvoiceReceived
import fr.acinq.lightning.io.OfferNotPaid
import fr.acinq.lightning.io.PayOffer
import fr.acinq.lightning.io.PeerEvent
import fr.acinq.lightning.logging.MDCLogger
import fr.acinq.lightning.message.OnionMessages
import fr.acinq.lightning.message.OnionMessages.Destination
import fr.acinq.lightning.message.OnionMessages.IntermediateNode
import fr.acinq.lightning.message.OnionMessages.buildMessage
import fr.acinq.lightning.utils.currentTimestampMillis
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.*
import kotlinx.coroutines.flow.MutableSharedFlow

sealed class OnionMessageAction {
    /** Send an outgoing onion message (invoice or invoice_request). */
    data class SendMessage(val message: OnionMessage) : OnionMessageAction()
    /** We received a valid invoice for an offer we're trying to pay: we should now pay that invoice. */
    data class PayInvoice(val payOffer: PayOffer, val invoice: Bolt12Invoice) : OnionMessageAction()
}

private data class PendingInvoiceRequest(val payOffer: PayOffer, val request: OfferTypes.InvoiceRequest)

/** Failures occurring when fetching an invoice to pay an offer */
sealed class Bolt12InvoiceRequestFailure {
    // @formatter:off
    data class NoResponse(val request: OfferTypes.InvoiceRequest) : Bolt12InvoiceRequestFailure() { override fun toString(): String = "no response to the invoice request" }
    data class MalformedResponse(val request: OfferTypes.InvoiceRequest, val failure: Bolt12Invoice.Companion.Bolt12ParsingResult.Failure.Malformed) : Bolt12InvoiceRequestFailure() { override fun toString(): String = "recipient returned an invalid response to the invoice request" }
    data class ErrorFromRecipient(val request: OfferTypes.InvoiceRequest, val failure: Bolt12Invoice.Companion.Bolt12ParsingResult.Failure.RecipientError) : Bolt12InvoiceRequestFailure() { override fun toString(): String = "recipient responded to the invoice request with an error" }
    data class InvoiceMismatch(val request: OfferTypes.InvoiceRequest, val reason: String) : Bolt12InvoiceRequestFailure() { override fun toString(): String = "recipient returned an invoice that does not match the request" }
    // @formatter:on
}

class OfferManager(val nodeParams: NodeParams, val walletParams: WalletParams, val eventsFlow: MutableSharedFlow<PeerEvent>, private val logger: MDCLogger) {
    val remoteNodeId: PublicKey = walletParams.trampolineNode.id
    private val pendingInvoiceRequests: HashMap<ByteVector32, PendingInvoiceRequest> = HashMap()
    private val localOffers: HashMap<ByteVector32, OfferTypes.Offer> = HashMap()

    init {
        registerOffer(nodeParams.defaultOffer(walletParams.trampolineNode.id), null)
    }

    fun registerOffer(offer: OfferTypes.Offer, pathId: ByteVector32?) {
        localOffers[pathId ?: ByteVector32.Zeroes] = offer
    }

    /**
     * @return invoice requests that must be sent and the corresponding path_id that must be used in case of a timeout.
     */
    fun requestInvoice(payOffer: PayOffer): Triple<ByteVector32, List<OnionMessage>, OfferTypes.InvoiceRequest> {
        val request = OfferTypes.InvoiceRequest(payOffer.offer, payOffer.amount, 1, nodeParams.features.bolt12Features(), payOffer.payerKey, payOffer.payerNote, nodeParams.chainHash)
        val replyPathId = randomBytes32()
        pendingInvoiceRequests[replyPathId] = PendingInvoiceRequest(payOffer, request)
        // We add dummy hops to the reply path: this way the receiver only learns that we're at most 3 hops away from our peer.
        val replyPathHops = listOf(IntermediateNode(EncodedNodeId.WithPublicKey.Plain(remoteNodeId)), IntermediateNode(EncodedNodeId.WithPublicKey.Wallet(nodeParams.nodeId)), IntermediateNode(EncodedNodeId.WithPublicKey.Wallet(nodeParams.nodeId)))
        val lastHop = Destination.Recipient(EncodedNodeId.WithPublicKey.Wallet(nodeParams.nodeId), replyPathId)
        val replyPath = OnionMessages.buildRoute(randomKey(), replyPathHops, lastHop)
        val messageContent = TlvStream(OnionMessagePayloadTlv.ReplyPath(replyPath), OnionMessagePayloadTlv.InvoiceRequest(request.records))
        val invoiceRequests = payOffer.offer.contactInfos.mapNotNull { contactInfo ->
            val destination = Destination(contactInfo)
            buildMessage(randomKey(), randomKey(), intermediateNodes(destination), destination, messageContent).right
        }
        return Triple(replyPathId, invoiceRequests, request)
    }

    suspend fun checkInvoiceRequestTimeout(pathId: ByteVector32, payOffer: PayOffer) {
        if (pendingInvoiceRequests.containsKey(pathId)) {
            val request = pendingInvoiceRequests[pathId]!!.request
            logger.warning { "paymentId:${payOffer.paymentId} pathId=$pathId invoice request timed out" }
            eventsFlow.emit(OfferNotPaid(payOffer, Bolt12InvoiceRequestFailure.NoResponse(request)))
            pendingInvoiceRequests.remove(pathId)
        }
    }

    suspend fun receiveMessage(msg: OnionMessage, remoteChannelUpdates: List<ChannelUpdate>, currentBlockHeight: Int): OnionMessageAction? {
        return OnionMessages.decryptMessage(nodeParams.nodePrivateKey, msg, logger)?.let { decrypted ->
            when {
                pendingInvoiceRequests.containsKey(decrypted.pathId) -> receiveInvoiceResponse(decrypted)
                localOffers.containsKey(decrypted.pathId) -> receiveInvoiceRequest(decrypted, remoteChannelUpdates, currentBlockHeight)
                else -> {
                    logger.warning { "pathId:${decrypted.pathId} ignoring onion message (could be a duplicate invoice response)" }
                    null
                }
            }
        }
    }

    private suspend fun receiveInvoiceResponse(decrypted: OnionMessages.DecryptedMessage): OnionMessageAction.PayInvoice? {
        val (payOffer, request) = pendingInvoiceRequests[decrypted.pathId]!!
        pendingInvoiceRequests.remove(decrypted.pathId)
        return when (val res = Bolt12Invoice.extract(decrypted.content.records)) {
            is Bolt12Invoice.Companion.Bolt12ParsingResult.Failure.Malformed -> {
                logger.warning { "paymentId:${payOffer.paymentId} pathId=${decrypted.pathId} malformed response: invalid_tlv=${res.invalidTlvPayload}" }
                eventsFlow.emit(OfferNotPaid(payOffer, Bolt12InvoiceRequestFailure.MalformedResponse(request, res)))
                null
            }
            is Bolt12Invoice.Companion.Bolt12ParsingResult.Failure.RecipientError -> {
                logger.warning { "paymentId:${payOffer.paymentId} pathId=${decrypted.pathId} response did not contain an invoice: invoice_error=${res.invoiceError.error}" }
                eventsFlow.emit(OfferNotPaid(payOffer, Bolt12InvoiceRequestFailure.ErrorFromRecipient(request, res)))
                null
            }
            is Bolt12Invoice.Companion.Bolt12ParsingResult.Success -> {
                when (val reason = res.invoice.validateFor(request)) {
                    is Left -> {
                        logger.warning { "paymentId:${payOffer.paymentId} pathId=${decrypted.pathId} invoice does not match request: ${reason.value}" }
                        eventsFlow.emit(OfferNotPaid(payOffer, Bolt12InvoiceRequestFailure.InvoiceMismatch(request, reason.value)))
                        null
                    }
                    is Right -> {
                        logger.info { "paymentId:${payOffer.paymentId} pathId=${decrypted.pathId} received valid invoice: ${res.invoice}" }
                        eventsFlow.emit(OfferInvoiceReceived(payOffer, res.invoice))
                        OnionMessageAction.PayInvoice(payOffer, res.invoice)
                    }
                }
            }
        }
    }

    private fun receiveInvoiceRequest(decrypted: OnionMessages.DecryptedMessage, remoteChannelUpdates: List<ChannelUpdate>, currentBlockHeight: Int): OnionMessageAction.SendMessage? {
        val offer = localOffers[decrypted.pathId]!!
        val request = decrypted.content.records.get<OnionMessagePayloadTlv.InvoiceRequest>()?.let { OfferTypes.InvoiceRequest.validate(it.tlvs).right }
        return when {
            request == null -> {
                logger.warning { "offerId:${offer.offerId} pathId:${decrypted.pathId} ignoring onion message: missing or invalid invoice request" }
                null
            }
            decrypted.content.replyPath == null -> {
                logger.warning { "offerId:${offer.offerId} pathId:${decrypted.pathId} ignoring invoice request: no reply path ($request)" }
                null
            }
            request.offer != offer -> {
                logger.warning { "offerId:${offer.offerId} pathId:${decrypted.pathId} ignoring invoice request: wrong offer (expected=$offer actual=${request.offer})" }
                sendInvoiceError("ignoring invoice request for wrong offer", decrypted.content.replyPath)
            }
            !request.isValid() -> {
                logger.warning { "offerId:${offer.offerId} pathId:${decrypted.pathId} ignoring invalid invoice request ($request)" }
                sendInvoiceError("ignoring invalid invoice request", decrypted.content.replyPath)
            }
            else -> {
                val amount = request.amount ?: (request.offer.amount!! * request.quantity)
                val preimage = randomBytes32()
                val pathId = OfferPaymentMetadata.V1(ByteVector32(decrypted.pathId), amount, preimage, request.payerId, request.payerNote, request.quantity, currentTimestampMillis()).toPathId(nodeParams.nodePrivateKey)
                val recipientPayload = RouteBlindingEncryptedData(TlvStream(RouteBlindingEncryptedDataTlv.PathId(pathId))).write().toByteVector()
                val paymentInfo = OfferTypes.PaymentInfo(
                    feeBase = remoteChannelUpdates.maxOfOrNull { it.feeBaseMsat } ?: walletParams.invoiceDefaultRoutingFees.feeBase,
                    feeProportionalMillionths = remoteChannelUpdates.maxOfOrNull { it.feeProportionalMillionths } ?: walletParams.invoiceDefaultRoutingFees.feeProportional,
                    cltvExpiryDelta = remoteChannelUpdates.maxOfOrNull { it.cltvExpiryDelta } ?: walletParams.invoiceDefaultRoutingFees.cltvExpiryDelta,
                    minHtlc = remoteChannelUpdates.minOfOrNull { it.htlcMinimumMsat } ?: 1.msat,
                    maxHtlc = amount,
                    allowedFeatures = Features.empty
                )
                val remoteNodePayload = RouteBlindingEncryptedData(
                    TlvStream(
                        RouteBlindingEncryptedDataTlv.OutgoingNodeId(EncodedNodeId.WithPublicKey.Wallet(nodeParams.nodeId)),
                        RouteBlindingEncryptedDataTlv.PaymentRelay(paymentInfo.cltvExpiryDelta, paymentInfo.feeProportionalMillionths, paymentInfo.feeBase),
                        RouteBlindingEncryptedDataTlv.PaymentConstraints((paymentInfo.cltvExpiryDelta + nodeParams.maxFinalCltvExpiryDelta).toCltvExpiry(currentBlockHeight.toLong()), paymentInfo.minHtlc)
                    )
                ).write().toByteVector()
                val blindedRoute = RouteBlinding.create(randomKey(), listOf(remoteNodeId, nodeParams.nodeId), listOf(remoteNodePayload, recipientPayload)).route
                val path = Bolt12Invoice.Companion.PaymentBlindedContactInfo(OfferTypes.ContactInfo.BlindedPath(blindedRoute), paymentInfo)
                val invoice = Bolt12Invoice(request, preimage, decrypted.blindedPrivateKey, nodeParams.bolt12invoiceExpiry.inWholeSeconds, nodeParams.features.bolt12Features(), listOf(path))
                val destination = Destination.BlindedPath(decrypted.content.replyPath)
                when (val invoiceMessage = buildMessage(randomKey(), randomKey(), intermediateNodes(destination), destination, TlvStream(OnionMessagePayloadTlv.Invoice(invoice.records)))) {
                    is Left -> {
                        logger.warning { "offerId:${offer.offerId} pathId:${decrypted.pathId} ignoring invoice request, could not build onion message: ${invoiceMessage.value}" }
                        sendInvoiceError("failed to build onion message", decrypted.content.replyPath)
                    }
                    is Right -> OnionMessageAction.SendMessage(invoiceMessage.value)
                }
            }
        }
    }

    private fun sendInvoiceError(message: String, replyPath: RouteBlinding.BlindedRoute): OnionMessageAction.SendMessage? {
        val error = TlvStream<OnionMessagePayloadTlv>(OnionMessagePayloadTlv.InvoiceError(TlvStream(OfferTypes.Error(message))))
        val destination = Destination.BlindedPath(replyPath)
        return buildMessage(randomKey(), randomKey(), intermediateNodes(destination), destination, error)
            .right
            ?.let { OnionMessageAction.SendMessage(it) }
    }

    /** If our trampoline node is the introduction node, we don't need an intermediate encryption step. */
    private fun intermediateNodes(destination: Destination): List<IntermediateNode> {
        val needIntermediateHop = when (destination) {
            is Destination.BlindedPath -> when (val introduction = destination.route.introductionNodeId) {
                is EncodedNodeId.WithPublicKey.Plain -> introduction.publicKey != remoteNodeId
                is EncodedNodeId.WithPublicKey.Wallet -> true
                is EncodedNodeId.ShortChannelIdDir -> true // we don't have access to the graph data and rely on our peer to resolve the scid
            }
            is Destination.Recipient -> destination.nodeId.publicKey != remoteNodeId
        }
        return if (needIntermediateHop) listOf(IntermediateNode(EncodedNodeId.WithPublicKey.Plain(remoteNodeId))) else listOf()
    }
}
