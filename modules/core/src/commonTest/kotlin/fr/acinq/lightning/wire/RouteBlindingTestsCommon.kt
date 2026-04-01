package fr.acinq.lightning.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.EncodedNodeId
import fr.acinq.lightning.Features
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.RouteBlindingEncryptedDataTlv.*
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteBlindingTestsCommon : LightningTestSuite() {
    @Test
    fun `decode route blinding data -- reference test vector`() {
        val payloads = mapOf(
            ByteVector("01080000000000000000 042102edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145") to RouteBlindingEncryptedData(
                TlvStream(
                    Padding(ByteVector("0000000000000000")),
                    OutgoingNodeId(EncodedNodeId(PublicKey(ByteVector("02edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145"))))
                )
            ),
            ByteVector("0109000000000000000000 06204242424242424242424242424242424242424242424242424242424242424242") to RouteBlindingEncryptedData(
                TlvStream(
                    Padding(ByteVector("000000000000000000")),
                    PathId(ByteVector("4242424242424242424242424242424242424242424242424242424242424242"))
                )
            ),
            ByteVector("0421032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991") to RouteBlindingEncryptedData(
                TlvStream(OutgoingNodeId(EncodedNodeId(PublicKey(ByteVector("032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991")))))
            ),
            ByteVector("042102edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145") to RouteBlindingEncryptedData(
                TlvStream(OutgoingNodeId(EncodedNodeId(PublicKey(ByteVector("02edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145")))))
            ),
            ByteVector("010f000000000000000000000000000000 061000112233445566778899aabbccddeeff") to RouteBlindingEncryptedData(
                TlvStream(
                    Padding(ByteVector("000000000000000000000000000000")),
                    PathId(ByteVector("00112233445566778899aabbccddeeff"))
                )
            ),
            ByteVector("0121000000000000000000000000000000000000000000000000000000000000000000 04210324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c") to RouteBlindingEncryptedData(
                TlvStream(
                    Padding(ByteVector("000000000000000000000000000000000000000000000000000000000000000000")),
                    OutgoingNodeId(EncodedNodeId(PublicKey(ByteVector("0324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c"))))
                )
            ),
            ByteVector("0421027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007 0821031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f") to RouteBlindingEncryptedData(
                TlvStream(
                    OutgoingNodeId(EncodedNodeId(PublicKey(ByteVector("027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007")))),
                    NextPathKey(PublicKey(ByteVector("031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f")))
                )
            ),
        )

        for (payload in payloads) {
            val encoded = payload.key
            val data = payload.value
            assertEquals(data, RouteBlindingEncryptedData.read(encoded.toByteArray()).right)
            assertEquals(encoded, ByteVector(data.write()))
        }
    }

    @Test
    fun `decode payment onion route blinding data for accountable invoice`() {
        val payloads = mapOf(
            ByteVector("01200000000000000000000000000000000000000000000000000000000000000000 02080000000000000001 0300 0a080032000000002710 0c05000b724632 0e00") to RouteBlindingEncryptedData(TlvStream(
                Padding(ByteVector("0000000000000000000000000000000000000000000000000000000000000000")),
                OutgoingChannelId(ShortChannelId(1)),
                UpgradeAccountability,
                PaymentRelay(CltvExpiryDelta(50), 0, 10000.msat),
                PaymentConstraints(CltvExpiry(750150), 50.msat),
                AllowedFeatures(Features.empty))),
            ByteVector("02080000000000000002 0300 0821031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f 0a07004b0000009664 0c05000b721432 0e00") to RouteBlindingEncryptedData(TlvStream(
                OutgoingChannelId(ShortChannelId(2)),
                UpgradeAccountability,
                NextPathKey(PublicKey(ByteVector("031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f"))),
                PaymentRelay(CltvExpiryDelta(75), 150, 100.msat),
                PaymentConstraints(CltvExpiry(750100), 50.msat),
                AllowedFeatures(Features.empty))),
            ByteVector("012200000000000000000000000000000000000000000000000000000000000000000000 02080000000000000003 0300 0a06001900000064 0c05000b71c932 0e00") to RouteBlindingEncryptedData(TlvStream(
                Padding(ByteVector("00000000000000000000000000000000000000000000000000000000000000000000")),
                OutgoingChannelId(ShortChannelId(3)),
                UpgradeAccountability,
                PaymentRelay(CltvExpiryDelta(25), 100, 0.msat),
                PaymentConstraints(CltvExpiry(750025), 50.msat),
                AllowedFeatures(Features.empty))),
            ByteVector("011c00000000000000000000000000000000000000000000000000000000 0300 0616c9cf92f45ade68345bc20ae672e2012f4af487ed4415 0c05000b71b032 0e00") to RouteBlindingEncryptedData(TlvStream(
                Padding(ByteVector("00000000000000000000000000000000000000000000000000000000")),
                UpgradeAccountability,
                PathId(ByteVector("c9cf92f45ade68345bc20ae672e2012f4af487ed4415")),
                PaymentConstraints(CltvExpiry(750000), 50.msat),
                AllowedFeatures(Features.empty))),
        )

        for (payload in payloads) {
            val encoded = payload.key
            val data = payload.value
            assertEquals(data, RouteBlindingEncryptedData.read(encoded.toByteArray()).right)
            assertEquals(encoded, ByteVector(data.write()))
        }
    }
}