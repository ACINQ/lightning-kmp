package fr.acinq.lightning.wire

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.channel.ChannelOrigin
import fr.acinq.lightning.channel.ChannelType
import fr.acinq.lightning.crypto.assertArrayEquals
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenTlvTestsCommon : LightningTestSuite() {

    @Test
    fun `channel type TLV`() {
        val testCases = listOf(
            Pair(ChannelType.SupportedChannelType.Standard, Hex.decode("0100")),
            Pair(ChannelType.SupportedChannelType.StaticRemoteKey, Hex.decode("01021000")),
            Pair(ChannelType.SupportedChannelType.AnchorOutputs, Hex.decode("0103101000")),
            Pair(ChannelType.SupportedChannelType.AnchorOutputsZeroReserve, Hex.decode("01110100000000000000000000000000101000")),
        )

        @Suppress("UNCHECKED_CAST")
        val readers = mapOf(ChannelTlv.ChannelTypeTlv.tag to ChannelTlv.ChannelTypeTlv.Companion as TlvValueReader<ChannelTlv>)
        val tlvStreamSerializer = TlvStreamSerializer(false, readers)

        testCases.forEach {
            val decoded = tlvStreamSerializer.read(it.second)
            val encoded = tlvStreamSerializer.write(decoded)
            assertArrayEquals(it.second, encoded)
            val channelType = decoded.get<ChannelTlv.ChannelTypeTlv>()?.channelType
            assertNotNull(channelType)
            assertEquals(it.first, channelType)
        }
    }

    @Test
    fun `invalid channel type TLV`() {
        val testCases = listOf(
            Hex.decode("010101"),
            Hex.decode("01022000"),
            Hex.decode("0103202000"),
            Hex.decode("0103100000"),
            Hex.decode("0103401000"),
        )

        @Suppress("UNCHECKED_CAST")
        val readers = mapOf(ChannelTlv.ChannelTypeTlv.tag to ChannelTlv.ChannelTypeTlv.Companion as TlvValueReader<ChannelTlv>)
        val tlvStreamSerializer = TlvStreamSerializer(false, readers)

        testCases.forEach {
            val decoded = tlvStreamSerializer.read(it)
            val channelType = decoded.get<ChannelTlv.ChannelTypeTlv>()
            assertNotNull(channelType)
            assertTrue(channelType.channelType is ChannelType.UnsupportedChannelType)
        }
    }

    @Test
    fun `channel origin TLV`() {
        val testCases = listOf(
            Pair(
                ChannelOrigin.PayToOpenOrigin(ByteVector32.fromValidHex("187bf923f7f11ef732b73c417eb5a57cd4667b20a6f130ff505cd7ad3ab87281"), 1234.sat),
                Hex.decode("fe47000005 2a 0001 187bf923f7f11ef732b73c417eb5a57cd4667b20a6f130ff505cd7ad3ab87281 00000000000004d2")
            ),
            Pair(
                ChannelOrigin.PleaseOpenChannelOrigin(ByteVector32("2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db25"), 1_234_567.msat, 321.sat),
                Hex.decode("fe47000005 32 0004 2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db25 000000000012d687 0000000000000141")
            )
        )

        @Suppress("UNCHECKED_CAST")
        val readers = mapOf(ChannelTlv.ChannelOriginTlv.tag to ChannelTlv.ChannelOriginTlv.Companion as TlvValueReader<ChannelTlv>)
        val tlvStreamSerializer = TlvStreamSerializer(false, readers)

        testCases.forEach {
            val decoded = tlvStreamSerializer.read(it.second)
            val encoded = tlvStreamSerializer.write(decoded)
            assertArrayEquals(it.second, encoded)
            val channelOrigin = decoded.get<ChannelTlv.ChannelOriginTlv>()?.channelOrigin
            assertNotNull(channelOrigin)
            assertEquals(it.first, channelOrigin)
        }
    }

}
