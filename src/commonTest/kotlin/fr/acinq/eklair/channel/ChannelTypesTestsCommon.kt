package fr.acinq.eklair.channel

import kotlin.test.Test
import kotlin.test.assertTrue

class ChannelTypesTestsCommon {
    @Test
    fun `standard channel features include deterministic channel key path`() {
        assertTrue(ChannelVersion.STANDARD.isSet(ChannelVersion.USE_PUBKEY_KEYPATH_BIT))
        assertTrue(!ChannelVersion.ZEROES.isSet(ChannelVersion.USE_PUBKEY_KEYPATH_BIT))
    }
}
