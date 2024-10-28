package fr.acinq.lightning.wire

import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.channel.ChannelType
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.secp256k1.Hex
import kotlin.test.*

class OpenTlvTestsCommon : LightningTestSuite() {

    @Test
    fun `channel type TLV`() {
        val testCases = listOf(
            Pair(ChannelType.UnsupportedChannelType(Features.empty), Hex.decode("0100")),
            Pair(ChannelType.UnsupportedChannelType(Features(Feature.StaticRemoteKey to FeatureSupport.Mandatory)), Hex.decode("01021000")),
            Pair(ChannelType.SupportedChannelType.AnchorOutputs, Hex.decode("0103101000")),
            Pair(ChannelType.SupportedChannelType.AnchorOutputsZeroReserve, Hex.decode("01110100000000000000000000000000101000")),
        )

        @Suppress("UNCHECKED_CAST")
        val readers = mapOf(ChannelTlv.ChannelTypeTlv.tag to ChannelTlv.ChannelTypeTlv.Companion as TlvValueReader<ChannelTlv>)
        val tlvStreamSerializer = TlvStreamSerializer(false, readers)

        testCases.forEach {
            val decoded = tlvStreamSerializer.read(it.second)
            val encoded = tlvStreamSerializer.write(decoded)
            assertContentEquals(it.second, encoded)
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

}
