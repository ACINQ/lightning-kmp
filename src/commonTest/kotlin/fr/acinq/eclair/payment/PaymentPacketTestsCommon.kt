package fr.acinq.eclair.payment

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.eclair.CltvExpiry
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.Eclair.nodeFee
import fr.acinq.eclair.Eclair.randomBytes32
import fr.acinq.eclair.Eclair.randomBytes64
import fr.acinq.eclair.Eclair.randomKey
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.channel.Channel
import fr.acinq.eclair.crypto.sphinx.Sphinx
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.router.NodeHop
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.toByteVector32
import fr.acinq.eclair.wire.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PaymentPacketTestsCommon {

    private val privA = randomKey()
    private val a = privA.publicKey()
    private val privB = randomKey()
    private val b = privB.publicKey()
    private val privC = randomKey()
    private val c = privC.publicKey()
    private val privD = randomKey()
    private val d = privD.publicKey()
    private val privE = randomKey()
    private val e = privE.publicKey()
    private val defaultChannelUpdate = ChannelUpdate(randomBytes64(), Block.RegtestGenesisBlock.hash, ShortChannelId(0), 0, 1, 0, CltvExpiryDelta(0), 42000.msat, 0.msat, 0, 500000000.msat)
    private val channelUpdateAB = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(1), cltvExpiryDelta = CltvExpiryDelta(4), feeBaseMsat = 642000.msat, feeProportionalMillionths = 7)
    private val channelUpdateBC = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(2), cltvExpiryDelta = CltvExpiryDelta(5), feeBaseMsat = 153000.msat, feeProportionalMillionths = 4)
    private val channelUpdateCD = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(3), cltvExpiryDelta = CltvExpiryDelta(10), feeBaseMsat = 60000.msat, feeProportionalMillionths = 1)
    private val channelUpdateDE = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(4), cltvExpiryDelta = CltvExpiryDelta(7), feeBaseMsat = 766000.msat, feeProportionalMillionths = 10)

    // simple route a -> b -> c -> d -> e
    private val hops = listOf(
        ChannelHop(a, b, channelUpdateAB),
        ChannelHop(b, c, channelUpdateBC),
        ChannelHop(c, d, channelUpdateCD),
        ChannelHop(d, e, channelUpdateDE)
    )

    private val finalAmount = 42000000.msat
    private val currentBlockCount = 400000L
    private val finalExpiry = CltvExpiry(currentBlockCount) + Channel.MIN_CLTV_EXPIRY_DELTA
    private val paymentPreimage = randomBytes32()
    private val paymentHash = Crypto.sha256(paymentPreimage).toByteVector32()
    private val paymentSecret = randomBytes32()

    private val expiryDE = finalExpiry
    private val amountDE = finalAmount
    private val feeD = nodeFee(channelUpdateDE.feeBaseMsat, channelUpdateDE.feeProportionalMillionths, amountDE)

    private val expiryCD = expiryDE + channelUpdateDE.cltvExpiryDelta
    private val amountCD = amountDE + feeD
    private val feeC = nodeFee(channelUpdateCD.feeBaseMsat, channelUpdateCD.feeProportionalMillionths, amountCD)

    private val expiryBC = expiryCD + channelUpdateCD.cltvExpiryDelta
    private val amountBC = amountCD + feeC
    private val feeB = nodeFee(channelUpdateBC.feeBaseMsat, channelUpdateBC.feeProportionalMillionths, amountBC)

    private val expiryAB = expiryBC + channelUpdateBC.cltvExpiryDelta
    private val amountAB = amountBC + feeB

    // simple trampoline route to e:
    //             .--.   .--.
    //            /    \ /    \
    // a -> b -> c      d      e

    private val trampolineHops = listOf(
        NodeHop(a, c, channelUpdateAB.cltvExpiryDelta + channelUpdateBC.cltvExpiryDelta, feeB),
        NodeHop(c, d, channelUpdateCD.cltvExpiryDelta, feeC),
        NodeHop(d, e, channelUpdateDE.cltvExpiryDelta, feeD)
    )

    private val trampolineChannelHops = listOf(
        ChannelHop(a, b, channelUpdateAB),
        ChannelHop(b, c, channelUpdateBC)
    )

    private fun testBuildOnion(legacy: Boolean) {
        val finalPayload = if (legacy) {
            FinalLegacyPayload(finalAmount, finalExpiry)
        } else {
            FinalTlvPayload(TlvStream(listOf(OnionTlv.AmountToForward(finalAmount), OnionTlv.OutgoingCltv(finalExpiry))))
        }
        val (firstAmount, firstExpiry, onion) = OutgoingPacket.buildPacket(paymentHash, hops, finalPayload, OnionRoutingPacket.PaymentPacketLength)
        assertEquals(amountAB, firstAmount)
        assertEquals(expiryAB, firstExpiry)
        assertEquals(OnionRoutingPacket.PaymentPacketLength, onion.packet.payload.size())

        // let's peel the onion
        testPeelOnion(onion.packet)
    }

    private fun testPeelOnion(packet_b: OnionRoutingPacket) {
        val addB = UpdateAddHtlc(randomBytes32(), 0, amountAB, paymentHash, expiryAB, packet_b)
        val (payloadB, packetC) = decryptChannelRelay(addB, privB)
        assertEquals(OnionRoutingPacket.PaymentPacketLength, packetC.payload.size())
        assertEquals(amountBC, payloadB.amountToForward)
        assertEquals(expiryBC, payloadB.outgoingCltv)
        assertEquals(channelUpdateBC.shortChannelId, payloadB.outgoingChannelId)

        val addC = UpdateAddHtlc(randomBytes32(), 1, amountBC, paymentHash, expiryBC, packetC)
        val (payloadC, packetD) = decryptChannelRelay(addC, privC)
        assertEquals(OnionRoutingPacket.PaymentPacketLength, packetD.payload.size())
        assertEquals(amountCD, payloadC.amountToForward)
        assertEquals(expiryCD, payloadC.outgoingCltv)
        assertEquals(channelUpdateCD.shortChannelId, payloadC.outgoingChannelId)

        val addD = UpdateAddHtlc(randomBytes32(), 2, amountCD, paymentHash, expiryCD, packetD)
        val (payloadD, packetE) = decryptChannelRelay(addD, privD)
        assertEquals(OnionRoutingPacket.PaymentPacketLength, packetE.payload.size())
        assertEquals(amountDE, payloadD.amountToForward)
        assertEquals(expiryDE, payloadD.outgoingCltv)
        assertEquals(channelUpdateDE.shortChannelId, payloadD.outgoingChannelId)

        val addE = UpdateAddHtlc(randomBytes32(), 2, amountDE, paymentHash, expiryDE, packetE)
        val payloadE = IncomingPacket.decrypt(addE, privE).right!!
        assertEquals(finalAmount, payloadE.amount)
        assertEquals(finalAmount, payloadE.totalAmount)
        assertEquals(finalExpiry, payloadE.expiry)
        assertNull(payloadE.paymentSecret)
    }

    // Wallets don't need to decrypt onions for intermediate nodes, but it's useful to test that encryption works correctly.
    private fun decryptChannelRelay(add: UpdateAddHtlc, privateKey: PrivateKey): Pair<RelayLegacyPayload, OnionRoutingPacket> {
        val decrypted = Sphinx.peel(privateKey, paymentHash, add.onionRoutingPacket, OnionRoutingPacket.PaymentPacketLength).right!!
        assertFalse(decrypted.isLastPacket)
        val decoded = RelayLegacyPayload.read(ByteArrayInput(decrypted.payload.toByteArray()))
        return Pair(decoded, decrypted.nextPacket)
    }

    @Test
    fun `build onion with final legacy payload`() {
        testBuildOnion(legacy = true)
    }

}

