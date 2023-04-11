package fr.acinq.lightning.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.tests.utils.LightningTestSuite
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
                    OutgoingNodeId(PublicKey(ByteVector("02edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145")))
                )
            ),
            ByteVector("0109000000000000000000 06204242424242424242424242424242424242424242424242424242424242424242") to RouteBlindingEncryptedData(
                TlvStream(
                    Padding(ByteVector("000000000000000000")),
                    PathId(ByteVector("4242424242424242424242424242424242424242424242424242424242424242"))
                )
            ),
            ByteVector("0421032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991") to RouteBlindingEncryptedData(
                TlvStream(OutgoingNodeId(PublicKey(ByteVector("032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991"))))
            ),
            ByteVector("042102edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145") to RouteBlindingEncryptedData(
                TlvStream(OutgoingNodeId(PublicKey(ByteVector("02edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145"))))
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
                    OutgoingNodeId(PublicKey(ByteVector("0324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c")))
                )
            ),
            ByteVector("0421027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007 0821031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f") to RouteBlindingEncryptedData(
                TlvStream(
                    OutgoingNodeId(PublicKey(ByteVector("027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007"))),
                    NextBlinding(PublicKey(ByteVector("031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f")))
                )
            ),
        )

        for (payload in payloads) {
            val encoded = payload.key
            val data = payload.value
            assertEquals(data, RouteBlindingEncryptedData.read(encoded.toByteArray()))
            assertEquals(encoded, ByteVector(data.write()))
        }
    }
}