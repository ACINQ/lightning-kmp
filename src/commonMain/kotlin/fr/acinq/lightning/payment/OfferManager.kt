package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.utils.Either.Left
import fr.acinq.bitcoin.utils.Either.Right
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.WalletParams
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

class OfferManager(val nodeParams: NodeParams, val walletParams: WalletParams, val eventsFlow: MutableSharedFlow<PeerEvent>, val logger: MDCLogger) {
    val remoteNodeId: PublicKey = walletParams.trampolineNode.id
    private val pendingInvoiceRequests: HashMap<ByteVector32, PendingInvoiceRequest> = HashMap()
    private val localOffers: HashMap<ByteVector32, OfferTypes.Offer> = HashMap()

    fun registerOffer(offer: OfferTypes.Offer, pathId: ByteVector32) {
        localOffers[pathId] = offer
    }

    /**
     * @return invoice requests that must be sent and the corresponding path_id that must be used in case of a timeout.
     */
    fun requestInvoice(payOffer: PayOffer): Pair<ByteVector32, List<OnionMessage>> {
        val request = OfferTypes.InvoiceRequest(payOffer.offer, payOffer.amount, 1, nodeParams.features.bolt12Features(), payOffer.payerKey, nodeParams.chainHash)
        val replyPathId = randomBytes32()
        pendingInvoiceRequests[replyPathId] = PendingInvoiceRequest(payOffer, request)
        // We add dummy hops to the reply path: this way the receiver only learns that we're at most 3 hops away from our peer.
        val replyPathHops = listOf(remoteNodeId, nodeParams.nodeId, nodeParams.nodeId).map { IntermediateNode(it) }
        val lastHop = Destination.Recipient(nodeParams.nodeId, replyPathId)
        val replyPath = OnionMessages.buildRoute(randomKey(), replyPathHops, lastHop)
        val messageContent = TlvStream(OnionMessagePayloadTlv.ReplyPath(replyPath), OnionMessagePayloadTlv.InvoiceRequest(request.records))
        val invoiceRequests = payOffer.offer.contactInfos.mapNotNull { contactInfo -> buildMessage(randomKey(), randomKey(), listOf(IntermediateNode(remoteNodeId)), Destination(contactInfo), messageContent).right }
        return Pair(replyPathId, invoiceRequests)
    }

    suspend fun checkInvoiceRequestTimeout(pathId: ByteVector32, payOffer: PayOffer) {
        if (pendingInvoiceRequests.containsKey(pathId)) {
            logger.warning { "paymentId:${payOffer.paymentId} offerId=${payOffer.offer.offerId} invoice request timed out" }
            eventsFlow.emit(OfferNotPaid(payOffer, OfferPaymentFailure.NoResponse))
            pendingInvoiceRequests.remove(pathId)
        }
    }

    suspend fun receiveMessage(msg: OnionMessage, remoteChannelUpdates: List<ChannelUpdate>, currentBlockHeight: Int): OnionMessageAction? {
        val decrypted = OnionMessages.decryptMessage(nodeParams.nodePrivateKey, msg, logger)
        if (decrypted == null) {
            return null
        } else {
            if (pendingInvoiceRequests.containsKey(decrypted.pathId)) {
                val (payOffer, request) = pendingInvoiceRequests[decrypted.pathId]!!
                pendingInvoiceRequests.remove(decrypted.pathId)
                val invoice = decrypted.content.records.get<OnionMessagePayloadTlv.Invoice>()?.let { Bolt12Invoice.validate(it.tlvs).right }
                if (invoice == null) {
                    val error = decrypted.content.records.get<OnionMessagePayloadTlv.InvoiceError>()?.let { OfferTypes.InvoiceError.validate(it.tlvs).right }
                    val failure = error?.let { OfferPaymentFailure.InvoiceError(request, it) } ?: OfferPaymentFailure.InvalidResponse(request)
                    eventsFlow.emit(OfferNotPaid(payOffer, failure))
                    return null
                } else {
                    if (invoice.validateFor(request).isRight) {
                        eventsFlow.emit(OfferInvoiceReceived(payOffer, invoice))
                        return OnionMessageAction.PayInvoice(payOffer, invoice)
                    } else {
                        eventsFlow.emit(OfferNotPaid(payOffer, OfferPaymentFailure.InvalidInvoice(request, invoice)))
                        return null
                    }
                }
            } else if (localOffers.containsKey(decrypted.pathId) && decrypted.content.replyPath != null) {
                val offer = localOffers[decrypted.pathId]!!
                val offerPathId = ByteVector32(decrypted.pathId)
                val request = decrypted.content.records.get<OnionMessagePayloadTlv.InvoiceRequest>()?.let { OfferTypes.InvoiceRequest.validate(it.tlvs).right }
                if (request != null && request.offer == offer && request.isValid()) {
                    val amount = request.amount ?: (request.offer.amount!! * request.quantity)
                    val preimage = randomBytes32()
                    val pathId = OfferPaymentMetadata.V1(offerPathId, amount, preimage, request.payerId, request.quantity, currentTimestampMillis()).toPathId(nodeParams.nodePrivateKey)
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
                            RouteBlindingEncryptedDataTlv.OutgoingChannelId(ShortChannelId.peerId(nodeParams.nodeId)),
                            RouteBlindingEncryptedDataTlv.PaymentRelay(paymentInfo.cltvExpiryDelta, paymentInfo.feeProportionalMillionths, paymentInfo.feeBase),
                            RouteBlindingEncryptedDataTlv.PaymentConstraints((paymentInfo.cltvExpiryDelta + nodeParams.maxFinalCltvExpiryDelta).toCltvExpiry(currentBlockHeight.toLong()), paymentInfo.minHtlc)
                        )
                    ).write().toByteVector()
                    val blindedRoute = RouteBlinding.create(randomKey(), listOf(remoteNodeId, nodeParams.nodeId), listOf(remoteNodePayload, recipientPayload)).route
                    val path = Bolt12Invoice.Companion.PaymentBlindedContactInfo(OfferTypes.ContactInfo.BlindedPath(blindedRoute), paymentInfo)
                    val invoice = Bolt12Invoice(request, preimage, decrypted.blindedPrivateKey, nodeParams.bolt12invoiceExpiry.inWholeSeconds, nodeParams.features.bolt12Features(), listOf(path))
                    return when (val invoiceMessage = buildMessage(randomKey(), randomKey(), listOf(IntermediateNode(remoteNodeId)), Destination.BlindedPath(decrypted.content.replyPath), TlvStream(OnionMessagePayloadTlv.Invoice(invoice.records)))) {
                        is Left -> run {
                            logger.warning { "ignoring invoice request for path id ${decrypted.pathId}, could not build onion message: ${invoiceMessage.value}" }
                            null
                        }
                        is Right -> OnionMessageAction.SendMessage(invoiceMessage.value)
                    }
                } else {
                    // TODO: send back invoice error
                    if (request == null) {
                        logger.warning { "ignoring onion message for path id ${decrypted.pathId}: no invoice request" }
                    } else if (request.offer == offer) {
                        logger.warning { "ignoring invoice request for path id ${decrypted.pathId}: wrong offer" }
                    } else {
                        logger.warning { "ignoring invoice request for path id ${decrypted.pathId}: invalid invoice request" }
                    }
                    return null
                }
            } else {
                // Ignore unexpected messages.
                if (!localOffers.containsKey(decrypted.pathId)) {
                    logger.warning { "ignoring onion message with unrecognized path id" }
                } else if (decrypted.content.replyPath == null) {
                    logger.warning { "ignoring invoice request for path id ${decrypted.pathId}: no reply path" }
                }
                return null
            }
        }
    }
}
