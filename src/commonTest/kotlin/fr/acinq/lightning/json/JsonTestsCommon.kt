package fr.acinq.lightning.json

import fr.acinq.lightning.channel.TestsHelper
import fr.acinq.lightning.channel.states.ChannelState
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.tests.utils.LightningTestSuite
import kotlinx.serialization.encodeToString
import kotlin.test.Test

class JsonTestsCommon : LightningTestSuite() {

    @Test
    fun `basic json test`() {
        val (alice, _) = TestsHelper.reachNormal()
        val state: ChannelState = alice.state
        JsonSerializers.json.encodeToString(state)
    }

    @Test
    fun `serialize bolt11 invoice`() {
        val invoice =
            Bolt11Invoice.read("lntb123450n1pnx4cf2pp565tka26famjckm35lakwsrtnmfk7nzwm3va2u8tdu3kw7u80n5hqcqpjsp5guyuj6v84zyfxm3ae8y49rffgkcxsky73hun8mwvqfjdxw46ea0q9q7sqqqqqqqqqqqqqqqqqqqsqqqqqysgqdq523jhxapqd9h8vmmfvdjsmqz9gxqyjw5qrzjqwfn3p9278ttzzpe0e00uhyxhned3j5d9acqak5emwfpflp8z2cnfl6m5dzjwjw4hyqqqqlgqqqqqeqqjqedwgzyf2kuzyu4erj4xhdtknc9d8y8xkt8z80cpulg2q0mvgdcv4e7mgntf2nhur0x72k57ql7zx8ydzwtrxcnx9nk4pj65vfnhd3hsqyzlyxm")
                .get()
        JsonSerializers.json.encodeToString(JsonSerializers.Bolt11InvoiceSerializer, invoice)
    }
}
