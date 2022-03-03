package fr.acinq.lightning.wire

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InitTlvTestsCommon : LightningTestSuite() {
    @Test
    fun `legacy phoenix TLV`() {
        val testCases = listOf(
            Pair(
                first = DeterministicWallet.generate(MnemonicCode.toSeed("ghost runway obscure", passphrase = "")).publicKey,
                second = Hex.decode("fe47020001 61 0284a00b96b3fc7b1d874d639cd9e679c22149da5c51976f29738340c9e6b770a0 d0cb0003e64e3dcfce82a11065cccca82d29c446cc094303cf03a9286abbcf7e720d68e89abb18102bb5cf9f6db47156698527c75b70b8c51fa886e6fd11b1e4")
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
            assertTrue { Crypto.verifySignature(Crypto.sha256(result.legacyNodeId.toUncompressedBin()), result.signature, it.first) }
        }
    }
}