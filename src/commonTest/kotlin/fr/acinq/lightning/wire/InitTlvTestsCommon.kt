package fr.acinq.lightning.wire

import fr.acinq.bitcoin.*
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InitTlvTestsCommon : LightningTestSuite() {
    @Test
    fun `legacy phoenix TLV`() {
        val keyManager = LocalKeyManager(MnemonicCode.toSeed("sock able evoke work output half bamboo energy simple fiber unhappy afford", passphrase = "").byteVector(), NodeParams.Chain.Testnet)
        val testCases = listOf(
            Pair(
                first = keyManager.nodeKeys.legacyNodeKey.publicKey,
                second = Hex.decode("fe47020001 61 0388a99397c5a599c4c56ea2b9f938bd2893744a590af7c1f05c9c3ee822c13fdc abc7feb0f7b2473552864bcbf76406aecee86ed6d29349392a8876ce4cb543ee5d67a7ea48248970c7605e2861e93ab2336c813a30d1376bd6d0eb6e619c8d9f")
            )
        )

        @Suppress("UNCHECKED_CAST")
        val readers = mapOf(
            InitTlv.PhoenixAndroidLegacyNodeId.tag to InitTlv.PhoenixAndroidLegacyNodeId.Companion as TlvValueReader<InitTlv>
        )
        val tlvStreamSerializer = TlvStreamSerializer(false, readers)

        testCases.forEach {
            val decoded = tlvStreamSerializer.read(it.second)
            val result = decoded.records.mapNotNull { record ->
                when (record) {
                    is InitTlv.PhoenixAndroidLegacyNodeId -> {
                        assertEquals(InitTlv.PhoenixAndroidLegacyNodeId.tag, record.tag)
                        record
                    }
                    else -> null
                }
            }.first()
            assertEquals(it.first, result.legacyNodeId)
            // signature is legacy public key signed with the regular private key
            assertTrue { Crypto.verifySignature(Crypto.sha256(it.first.toUncompressedBin()), result.signature, keyManager.nodeKeys.nodeKey.publicKey) }
        }
    }
}