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
            Triple(ChannelVersion.STANDARD, Hex.decode("fe47000001 04 00000007"), Hex.decode("fe47000000 00000001")),
            Triple(ChannelVersion.STANDARD or ChannelVersion.ZERO_RESERVE, Hex.decode("fe47000001 04 0000000f"), Hex.decode("fe47000000 0000000f"))
        )

        @Suppress("UNCHECKED_CAST")
        val readers = mapOf(
            ChannelTlv.ChannelVersionTlv.tag to ChannelTlv.ChannelVersionTlv.Companion as TlvValueReader<ChannelTlv>
        )
        val tlvStreamSerializer = TlvStreamSerializer(false, readers)

        testCases.forEach {
            val decoded = tlvStreamSerializer.read(it.second)
            val version = decoded.records.mapNotNull { record ->
                when (record) {
                    is ChannelTlv.ChannelVersionTlv -> record.channelVersion
                    else -> null
                }
            }.first()
            assertEquals(it.first, version)
        }
    }
}