package fr.acinq.eclair.wire

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel.ChannelOrigin
import fr.acinq.eclair.channel.ChannelVersion
import fr.acinq.eclair.crypto.assertArrayEquals
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.sat
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenTlvTestsCommon : EclairTestSuite() {
    @Test
    fun `channel version TLV`() {
        val testCases = listOf(
            Pair(ChannelVersion.STANDARD, Hex.decode("fe47000001 04 00000007")),
            Pair(ChannelVersion.STANDARD or ChannelVersion.ZERO_RESERVE, Hex.decode("fe47000001 04 0000000f"))
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

    @Test
    fun `channel origin TLV`() {
        val testCases = listOf(
            Pair(
                ChannelOrigin.PayToOpenOrigin(ByteVector32.fromValidHex("187bf923f7f11ef732b73c417eb5a57cd4667b20a6f130ff505cd7ad3ab87281"), 1234.sat),
                Hex.decode("fe47000005 2a 0001 187bf923f7f11ef732b73c417eb5a57cd4667b20a6f130ff505cd7ad3ab87281 00000000000004d2")
            ),
            Pair(
                ChannelOrigin.SwapInOrigin("3AuM8hSkXBetjdHxWthRFiH6hYhqF2Prjr", 420.sat),
                Hex.decode("fe47000005 2d 0002 223341754d3868536b584265746a644878577468524669483668596871463250726a72 00000000000001a4")
            )
        )

        @Suppress("UNCHECKED_CAST")
        val readers = mapOf(
            ChannelTlv.ChannelOriginTlv.tag to ChannelTlv.ChannelOriginTlv.Companion as TlvValueReader<ChannelTlv>
        )
        val tlvStreamSerializer = TlvStreamSerializer(false, readers)

        testCases.forEach {
            val decoded = tlvStreamSerializer.read(it.second)
            val encoded = tlvStreamSerializer.write(decoded)
            assertArrayEquals(it.second, encoded)
            val channelOrigin = decoded.records.mapNotNull { record ->
                when (record) {
                    is ChannelTlv.ChannelOriginTlv -> record.channelOrigin
                    else -> null
                }
            }.first()
            assertEquals(it.first, channelOrigin)
        }
    }
}
