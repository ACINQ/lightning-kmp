package fr.acinq.lightning.channel

import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ChannelConfigTestsCommon : LightningTestSuite() {

    @Test
    fun `convert from bytes`() {
        assertContentEquals(ChannelConfig(ChannelConfigOption.FundingPubKeyBasedChannelKeyPath).toByteArray(), Hex.decode("01"))
        assertEquals(ChannelConfig(ChannelConfigOption.FundingPubKeyBasedChannelKeyPath), ChannelConfig(Hex.decode("ff")))
        assertEquals(ChannelConfig(ChannelConfigOption.FundingPubKeyBasedChannelKeyPath), ChannelConfig(Hex.decode("01")))
        assertEquals(ChannelConfig(ChannelConfigOption.FundingPubKeyBasedChannelKeyPath), ChannelConfig(Hex.decode("0001")))
    }

}