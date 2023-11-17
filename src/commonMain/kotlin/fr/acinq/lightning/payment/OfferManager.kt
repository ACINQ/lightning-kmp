package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.utils.Either.Left
import fr.acinq.bitcoin.utils.Either.Right
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.channel.states.ChannelState
import fr.acinq.lightning.channel.states.Normal
import fr.acinq.lightning.channel.states.Offline
import fr.acinq.lightning.channel.states.Syncing
import fr.acinq.lightning.crypto.RouteBlinding
import fr.acinq.lightning.io.PayOffer
import fr.acinq.lightning.logging.MDCLogger
import fr.acinq.lightning.message.OnionMessages
import fr.acinq.lightning.message.OnionMessages.Destination
import fr.acinq.lightning.message.OnionMessages.IntermediateNode
import fr.acinq.lightning.message.OnionMessages.buildMessage
import fr.acinq.lightning.utils.currentTimestampMillis
import fr.acinq.lightning.utils.currentTimestampSeconds
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.*
import kotlin.math.max

sealed class OnionMessageAction {
    data class SendMessage(val message: OnionMessage): OnionMessageAction()
    data class PayInvoice(val payOffer: PayOffer, val invoice: Bolt12Invoice, val payerKey: PrivateKey): OnionMessageAction()
    data class ReportPaymentFailure(val payOffer: PayOffer, val failure: OfferPaymentFailure): OnionMessageAction()
}

private data class PendingInvoiceRequest(val payOffer: PayOffer, val payerKey: PrivateKey, val request: OfferTypes.InvoiceRequest, val createAtSeconds: Long)

class OfferManager(val nodeParams: NodeParams, val walletParams: WalletParams, val logger: MDCLogger) {
    val remoteNodeId: PublicKey = walletParams.trampolineNode.id
    private val pendingInvoiceRequests: HashMap<ByteVector32, PendingInvoiceRequest> = HashMap()
    private val localOffers: HashMap<ByteVector32, OfferTypes.Offer> = HashMap()

    fun registerOffer(offer: OfferTypes.Offer, pathId: ByteVector32) {
        localOffers[pathId] = offer
    }

    fun requestInvoice(payOffer: PayOffer): List<OnionMessage> {
        val payerKey = randomKey()
        val request = OfferTypes.InvoiceRequest(payOffer.offer, payOffer.amount, payOffer.quantity, nodeParams.features.bolt12Features(), payerKey, nodeParams.chainHash)
        val replyPathId = randomBytes32()
        pendingInvoiceRequests[replyPathId] = PendingInvoiceRequest(payOffer, payerKey, request, currentTimestampSeconds())
        val numHopsToAdd = max(0, payOffer.minReplyPathHops - 1)
        val replyPathHops = (listOf(remoteNodeId) + List(numHopsToAdd) { nodeParams.nodeId }).map { IntermediateNode(it) }
        val lastHop = Destination.Recipient(nodeParams.nodeId, replyPathId)
        val replyPath = OnionMessages.buildRoute(randomKey(), replyPathHops, lastHop)
        val messageContent = TlvStream(OnionMessagePayloadTlv.ReplyPath(replyPath), OnionMessagePayloadTlv.InvoiceRequest(request.records))
        return payOffer.offer.contactInfos.mapNotNull { contactInfo -> buildMessage(randomKey(), randomKey(), listOf(IntermediateNode(remoteNodeId)), Destination(contactInfo), messageContent).right }
    }

    fun receiveMessage(msg: OnionMessage, channels: Map<ByteVector32, ChannelState>, currentBlockHeight: Int): OnionMessageAction? {
        val decrypted = OnionMessages.decryptMessage(nodeParams.nodePrivateKey, msg, logger)
        if (decrypted == null) {
            return null
        } else {
            if (pendingInvoiceRequests.containsKey(decrypted.pathId)) {
                val (payOffer, payerKey, request, _) = pendingInvoiceRequests[decrypted.pathId]!!
                pendingInvoiceRequests.remove(decrypted.pathId)
                val invoice = decrypted.content.records.get<OnionMessagePayloadTlv.Invoice>()?.let { Bolt12Invoice.validate(it.tlvs).right }
                if (invoice == null) {
                    val error = decrypted.content.records.get<OnionMessagePayloadTlv.InvoiceError>()?.let { OfferTypes.InvoiceError.validate(it.tlvs).right }
                    val failure = error?.let { OfferPaymentFailure.InvoiceError(request, it) } ?: OfferPaymentFailure.InvalidResponse(request)
                    return OnionMessageAction.ReportPaymentFailure(payOffer, failure)
                } else {
                    if (invoice.validateFor(request).isRight) {
                        return OnionMessageAction.PayInvoice(payOffer, invoice, payerKey)
                    } else {
                        return OnionMessageAction.ReportPaymentFailure(
                            payOffer,
                            OfferPaymentFailure.InvalidInvoice(request, invoice)
                        )
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
                    val remoteChannelUpdates = channels.values.mapNotNull { channelState ->
                        when (channelState) {
                            is Normal -> channelState.remoteChannelUpdate
                            is Offline -> (channelState.state as? Normal)?.remoteChannelUpdate
                            is Syncing -> (channelState.state as? Normal)?.remoteChannelUpdate
                            else -> null
                        }
                    }
                    val paymentInfo = OfferTypes.PaymentInfo(
                        feeBase = remoteChannelUpdates.maxOfOrNull { it.feeBaseMsat } ?: walletParams.invoiceDefaultRoutingFees.feeBase,
                        feeProportionalMillionths = remoteChannelUpdates.maxOfOrNull { it.feeProportionalMillionths } ?: walletParams.invoiceDefaultRoutingFees.feeProportional,
                        cltvExpiryDelta = remoteChannelUpdates.maxOfOrNull { it.cltvExpiryDelta } ?: walletParams.invoiceDefaultRoutingFees.cltvExpiryDelta,
                        minHtlc = remoteChannelUpdates.minOfOrNull { it.htlcMinimumMsat } ?: 1.msat,
                        maxHtlc = amount,
                        allowedFeatures = Features.empty
                    )
                    val remoteNodePayload = RouteBlindingEncryptedData(TlvStream(
                        RouteBlindingEncryptedDataTlv.OutgoingChannelId(ShortChannelId.peerId(nodeParams.nodeId)),
                        RouteBlindingEncryptedDataTlv.PaymentRelay(paymentInfo.cltvExpiryDelta, paymentInfo.feeProportionalMillionths, paymentInfo.feeBase),
                        RouteBlindingEncryptedDataTlv.PaymentConstraints((paymentInfo.cltvExpiryDelta + nodeParams.maxFinalCltvExpiryDelta).toCltvExpiry(currentBlockHeight.toLong()), paymentInfo.minHtlc)
                    )).write().toByteVector()
                    val blindedRoute = RouteBlinding.create(randomKey(), listOf(remoteNodeId, nodeParams.nodeId), listOf(remoteNodePayload, recipientPayload)).route
                    val path = Bolt12Invoice.Companion.PaymentBlindedContactInfo(OfferTypes.ContactInfo.BlindedPath(blindedRoute), paymentInfo)
                    val invoice = Bolt12Invoice(request, preimage, decrypted.blindedPrivateKey, nodeParams.bolt12invoiceExpiry.inWholeSeconds, nodeParams.features.bolt12Features(), listOf(path))
                    return when (val invoiceMessage = buildMessage(randomKey(), randomKey(), listOf(IntermediateNode(remoteNodeId)), Destination.BlindedPath(decrypted.content.replyPath), TlvStream(OnionMessagePayloadTlv.Invoice(invoice.records)))) {
                        is Left -> null
                        is Right -> OnionMessageAction.SendMessage(invoiceMessage.value)
                    }
                } else {
                    // TODO: send back invoice error
                    return null
                }
            } else {
                // Ignore unexpected messages.
                return null
            }
        }
    }

    fun checkInvoiceRequestTimeout(timeoutSeconds: Long): List<OnionMessageAction.ReportPaymentFailure> {
        val timedOut = ArrayList<OnionMessageAction.ReportPaymentFailure>()
        val cutoff = currentTimestampSeconds() - timeoutSeconds
        for ((pathId, pending) in pendingInvoiceRequests) {
            if (pending.createAtSeconds < cutoff) {
                timedOut.add(OnionMessageAction.ReportPaymentFailure(pending.payOffer, OfferPaymentFailure.NoResponse))
                pendingInvoiceRequests.remove(pathId)
            }
        }
        return timedOut
    }
}
