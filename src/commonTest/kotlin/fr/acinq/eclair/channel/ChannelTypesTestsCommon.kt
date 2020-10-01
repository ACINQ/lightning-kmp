package fr.acinq.eclair.channel

import fr.acinq.eclair.tests.utils.EclairTestSuite
import kotlin.test.Test
import kotlin.test.assertTrue

class ChannelTypesTestsCommon : EclairTestSuite() {
    @Test
    fun `standard channel features include deterministic channel key path`() {
        assertTrue(ChannelVersion.STANDARD.isSet(ChannelVersion.USE_PUBKEY_KEYPATH_BIT))
        assertTrue(!ChannelVersion.ZEROES.isSet(ChannelVersion.USE_PUBKEY_KEYPATH_BIT))
    }
}
