package fr.acinq.lightning.json

import fr.acinq.lightning.channel.PersistedChannelState
import fr.acinq.lightning.channel.TestsHelper
import fr.acinq.lightning.tests.utils.LightningTestSuite
import kotlin.test.Test

class JsonTestsCommon : LightningTestSuite() {

    @Test
    fun `basic json test`() {
        val (alice, _) = TestsHelper.reachNormal()
        val state: PersistedChannelState = alice.state
        println(JsonSerializers.toJsonString(state))
    }

}
