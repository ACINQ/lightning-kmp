package fr.acinq.eclair.wire

import fr.acinq.eclair.channel.ChannelVersion
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenTlvTestsCommon : EclairTestSuite() {
    @Test
    fun `channel version TLV`() {
        val testCases = listOf(
            Triple(ChannelVersion.STANDARD, Hex.decode("fe47000000 00000001"), Hex.decode("fe47000000 00000001")),
            Triple(ChannelVersion.STANDARD, Hex.decode("fe47000001 04 00000001"), Hex.decode("fe47000000 00000001")),
            Triple(ChannelVersion.STANDARD or ChannelVersion.ZERO_RESERVE, Hex.decode("fe47000000 00000009"), Hex.decode("fe47000000 00000009")),
            Triple(ChannelVersion.STANDARD or ChannelVersion.ZERO_RESERVE, Hex.decode("fe47000001 04 00000009"), Hex.decode("fe47000000 00000009"))
        )
        val serializers = HashMap<Long, LightningSerializer<ChannelTlv>>()
        @Suppress("UNCHECKED_CAST")
        serializers.put(ChannelTlv.ChannelVersionTlvLegacy.tag, ChannelTlv.ChannelVersionTlvLegacy.Companion as LightningSerializer<ChannelTlv>)
        @Suppress("UNCHECKED_CAST")
        serializers.put(ChannelTlv.ChannelVersionTlv.tag, ChannelTlv.ChannelVersionTlv.Companion as LightningSerializer<ChannelTlv>)

        val tlvStreamSerializer = TlvStreamSerializer(false, serializers)

        testCases.forEach {
            val decoded = tlvStreamSerializer.read(it.second)
            val version = decoded.records.mapNotNull {
                when(it) {
                    is ChannelTlv.ChannelVersionTlvLegacy -> it.channelVersion
                    is ChannelTlv.ChannelVersionTlv -> it.channelVersion
                    else -> null
                }
            }.first()
            assertEquals(it.first, version)
        }
    }
}