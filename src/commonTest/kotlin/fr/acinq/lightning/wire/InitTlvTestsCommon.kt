package fr.acinq.lightning.wire

import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals

class InitTlvTestsCommon : LightningTestSuite() {
    @Test
    fun `legacy phoenix TLV`() {
        val testCases = listOf(
            Pair(PublicKey(Hex.decode("03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f")), Hex.decode("fe47020001 21 03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"))
        )

        @Suppress("UNCHECKED_CAST")
        val readers = mapOf(
            InitTlv.PhoenixAndroidLegacyNodeId.tag to InitTlv.PhoenixAndroidLegacyNodeId.Companion as TlvValueReader<InitTlv>
        )
        val tlvStreamSerializer = TlvStreamSerializer(false, readers)

        testCases.forEach {
            val decoded = tlvStreamSerializer.read(it.second)
            val legacyNodeId = decoded.records.mapNotNull { record ->
                when (record) {
                    is InitTlv.PhoenixAndroidLegacyNodeId -> record.legacyNodeId
                    else -> null
                }
            }.first()
            assertEquals(it.first, legacyNodeId)
        }
    }
}