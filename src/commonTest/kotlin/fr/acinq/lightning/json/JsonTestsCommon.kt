package fr.acinq.lightning.json

import fr.acinq.lightning.channel.ChannelState
import fr.acinq.lightning.channel.TestsHelper
import fr.acinq.lightning.tests.utils.LightningTestSuite
import kotlinx.serialization.encodeToString
import kotlin.test.Test

class JsonTestsCommon : LightningTestSuite() {

    @Test
    fun `basic json test`() {
        val (alice, _) = TestsHelper.reachNormal()
        val state: ChannelState = alice.state
        println(JsonSerializers.json.encodeToString(state))
    }

}
