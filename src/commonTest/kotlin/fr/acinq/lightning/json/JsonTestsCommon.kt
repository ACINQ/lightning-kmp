package fr.acinq.lightning.json

import fr.acinq.lightning.channel.TestsHelper
import fr.acinq.lightning.channel.states.ChannelState
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.wire.OfferTypes
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
        JsonSerializers.json.encodeToString(invoice)
    }
    
    @Test
    fun `serialize bolt12 offer`() {
        val offer =
            OfferTypes.Offer.decode("lno1qgsyxjtl6luzd9t3pr62xr7eemp6awnejusgf6gw45q75vcfqqqqqqqsespexwyy4tcadvgg89l9aljus6709kx235hhqrk6n8dey98uyuftzdqrt2gkjvf2rj2vnt7m7chnmazen8wpur2h65ttgftkqaugy6ql9dcsyq39xc2g084xfn0s50zlh2ex22vvaqxqz3vmudklz453nns4d0624sqr8ux4p5usm22qevld4ydfck7hwgcg9wc3f78y7jqhc6hwdq7e9dwkhty3svq5ju4dptxtldjumlxh5lw48jsz6pnagtwrmeus7uq9rc5g6uddwcwldpklxexvlezld8egntua4gsqqy8auz966nksacdac8yv3maq6elp")
                .get()
        JsonSerializers.json.encodeToString(offer)
    }
}
