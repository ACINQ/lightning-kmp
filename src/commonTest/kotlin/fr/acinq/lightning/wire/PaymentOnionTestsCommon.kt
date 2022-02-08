package fr.acinq.lightning.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.crypto.assertArrayEquals
import fr.acinq.lightning.payment.PaymentRequest
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.msat
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class PaymentOnionTestsCommon : LightningTestSuite() {
    @Test
    fun `encode - decode onion packet`() {
        val bin = Hex.decode(
            "0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619e5f14350c2a76fc232b5e46d421e9615471ab9e0bc887beff8c95fdb878f7b3a71da571226458c510bbadd1276f045c21c520a07d35da256ef75b4367962437b0dd10f7d61ab590531cf08000178a333a347f8b4072e216400406bdf3bf038659793a86cae5f52d32f3438527b47a1cfc54285a8afec3a4c9f3323db0c946f5d4cb2ce721caad69320c3a469a202f3e468c67eaf7a7cda226d0fd32f7b48084dca885d15222e60826d5d971f64172d98e0760154400958f00e86697aa1aa9d41bee8119a1ec866abe044a9ad635778ba61fc0776dc832b39451bd5d35072d2269cf9b040d6ba38b54ec35f81d7fc67678c3be47274f3c4cc472aff005c3469eb3bc140769ed4c7f0218ff8c6c7dd7221d189c65b3b9aaa71a01484b122846c7c7b57e02e679ea8469b70e14fe4f70fee4d87b910cf144be6fe48eef24da475c0b0bcc6565ae82cd3f4e3b24c76eaa5616c6111343306ab35c1fe5ca4a77c0e314ed7dba39d6f1e0de791719c241a939cc493bea2bae1c1e932679ea94d29084278513c77b899cc98059d06a27d171b0dbdf6bee13ddc4fc17a0c4d2827d488436b57baa167544138ca2e64a11b43ac8a06cd0c2fba2d4d900ed2d9205305e2d7383cc98dacb078133de5f6fb6bed2ef26ba92cea28aafc3b9948dd9ae5559e8bd6920b8cea462aa445ca6a95e0e7ba52961b181c79e73bd581821df2b10173727a810c92b83b5ba4a0403eb710d2ca10689a35bec6c3a708e9e92f7d78ff3c5d9989574b00c6736f84c199256e76e19e78f0c98a9d580b4a658c84fc8f2096c2fbea8f5f8c59d0fdacb3be2802ef802abbecb3aba4acaac69a0e965abd8981e9896b1f6ef9d60f7a164b371af869fd0e48073742825e9434fc54da837e120266d53302954843538ea7c6c3dbfb4ff3b2fdbe244437f2a153ccf7bdb4c92aa08102d4f3cff2ae5ef86fab4653595e6a5837fa2f3e29f27a9cde5966843fb847a4a61f1e76c281fe8bb2b0a181d096100db5a1a5ce7a910238251a43ca556712eaadea167fb4d7d75825e440f3ecd782036d7574df8bceacb397abefc5f5254d2722215c53ff54af8299aaaad642c6d72a14d27882d9bbd539e1cc7a527526ba89b8c037ad09120e98ab042d3e8652b31ae0e478516bfaf88efca9f3676ffe99d2819dcaeb7610a626695f53117665d267d3f7abebd6bbd6733f645c72c389f03855bdf1e4b8075b516569b118233a0f0971d24b83113c0b096f5216a207ca99a7cddc81c130923fe3d91e7508c9ac5f2e914ff5dccab9e558566fa14efb34ac98d878580814b94b73acbfde9072f30b881f7f0fff42d4045d1ace6322d86a97d164aa84d93a60498065cc7c20e636f5862dc81531a88c60305a2e59a985be327a6902e4bed986dbf4a0b50c217af0ea7fdf9ab37f9ea1a1aaa72f54cf40154ea9b269f1a7c09f9f43245109431a175d50e2db0132337baa0ef97eed0fcf20489da36b79a1172faccc2f7ded7c60e00694282d93359c4682135642bc81f433574aa8ef0c97b4ade7ca372c5ffc23c7eddd839bab4e0f14d6df15c9dbeab176bec8b5701cf054eb3072f6dadc98f88819042bf10c407516ee58bce33fbe3b3d86a54255e577db4598e30a135361528c101683a5fcde7e8ba53f3456254be8f45fe3a56120ae96ea3773631fcb3873aa3abd91bcff00bd38bd43697a2e789e00da6077482e7b1b1a677b5afae4c54e6cbdf7377b694eb7d7a5b913476a5be923322d3de06060fd5e819635232a2cf4f0731da13b8546d1d6d4f8d75b9fce6c2341a71b0ea6f780df54bfdb0dd5cd9855179f602f917265f21f9190c70217774a6fbaaa7d63ad64199f4664813b955cff954949076dcf"
        )
        val expected = OnionRoutingPacket(
            0, ByteVector("02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), ByteVector(
                "e5f14350c2a76fc232b5e46d421e9615471ab9e0bc887beff8c95fdb878f7b3a71da571226458c510bbadd1276f045c21c520a07d35da256ef75b4367962437b0dd10f7d61ab590531cf08000178a333a347f8b4072e216400406bdf3bf038659793a86cae5f52d32f3438527b47a1cfc54285a8afec3a4c9f3323db0c946f5d4cb2ce721caad69320c3a469a202f3e468c67eaf7a7cda226d0fd32f7b48084dca885d15222e60826d5d971f64172d98e0760154400958f00e86697aa1aa9d41bee8119a1ec866abe044a9ad635778ba61fc0776dc832b39451bd5d35072d2269cf9b040d6ba38b54ec35f81d7fc67678c3be47274f3c4cc472aff005c3469eb3bc140769ed4c7f0218ff8c6c7dd7221d189c65b3b9aaa71a01484b122846c7c7b57e02e679ea8469b70e14fe4f70fee4d87b910cf144be6fe48eef24da475c0b0bcc6565ae82cd3f4e3b24c76eaa5616c6111343306ab35c1fe5ca4a77c0e314ed7dba39d6f1e0de791719c241a939cc493bea2bae1c1e932679ea94d29084278513c77b899cc98059d06a27d171b0dbdf6bee13ddc4fc17a0c4d2827d488436b57baa167544138ca2e64a11b43ac8a06cd0c2fba2d4d900ed2d9205305e2d7383cc98dacb078133de5f6fb6bed2ef26ba92cea28aafc3b9948dd9ae5559e8bd6920b8cea462aa445ca6a95e0e7ba52961b181c79e73bd581821df2b10173727a810c92b83b5ba4a0403eb710d2ca10689a35bec6c3a708e9e92f7d78ff3c5d9989574b00c6736f84c199256e76e19e78f0c98a9d580b4a658c84fc8f2096c2fbea8f5f8c59d0fdacb3be2802ef802abbecb3aba4acaac69a0e965abd8981e9896b1f6ef9d60f7a164b371af869fd0e48073742825e9434fc54da837e120266d53302954843538ea7c6c3dbfb4ff3b2fdbe244437f2a153ccf7bdb4c92aa08102d4f3cff2ae5ef86fab4653595e6a5837fa2f3e29f27a9cde5966843fb847a4a61f1e76c281fe8bb2b0a181d096100db5a1a5ce7a910238251a43ca556712eaadea167fb4d7d75825e440f3ecd782036d7574df8bceacb397abefc5f5254d2722215c53ff54af8299aaaad642c6d72a14d27882d9bbd539e1cc7a527526ba89b8c037ad09120e98ab042d3e8652b31ae0e478516bfaf88efca9f3676ffe99d2819dcaeb7610a626695f53117665d267d3f7abebd6bbd6733f645c72c389f03855bdf1e4b8075b516569b118233a0f0971d24b83113c0b096f5216a207ca99a7cddc81c130923fe3d91e7508c9ac5f2e914ff5dccab9e558566fa14efb34ac98d878580814b94b73acbfde9072f30b881f7f0fff42d4045d1ace6322d86a97d164aa84d93a60498065cc7c20e636f5862dc81531a88c60305a2e59a985be327a6902e4bed986dbf4a0b50c217af0ea7fdf9ab37f9ea1a1aaa72f54cf40154ea9b269f1a7c09f9f43245109431a175d50e2db0132337baa0ef97eed0fcf20489da36b79a1172faccc2f7ded7c60e00694282d93359c4682135642bc81f433574aa8ef0c97b4ade7ca372c5ffc23c7eddd839bab4e0f14d6df15c9dbeab176bec8b5701cf054eb3072f6dadc98f88819042bf10c407516ee58bce33fbe3b3d86a54255e577db4598e30a135361528c101683a5fcde7e8ba53f3456254be8f45fe3a56120ae96ea3773631fcb3873aa3abd91bcff00bd38bd43697a2e789e00da6077482e7b1b1a677b5afae4c54e6cbdf7377b694eb7d7a5b913476a5be923322d3de06060fd5e819635232a2cf4f0731da13b8546d1d6d4f8d75b9fce6c2341a71b0ea6f780df54bfdb0dd5cd9855179f602f9172"
            ), ByteVector32("65f21f9190c70217774a6fbaaa7d63ad64199f4664813b955cff954949076dcf")
        )

        val serializer = OnionRoutingPacketSerializer(OnionRoutingPacket.PaymentPacketLength)
        val decoded = serializer.read(bin)
        assertEquals(decoded, expected)

        val encoded = serializer.write(decoded)
        assertArrayEquals(bin, encoded)
    }

    @Test
    fun `encode - decode channel relay per-hop payload`() {
        val testCases = mapOf(
            PaymentOnion.ChannelRelayPayload.create(ShortChannelId(0), MilliSatoshi(0), CltvExpiry(0)) to Hex.decode("0e 0200 0400 06080000000000000000"),
            PaymentOnion.ChannelRelayPayload.create(ShortChannelId(42), MilliSatoshi(142000), CltvExpiry(500000)) to Hex.decode("14 0203022ab0 040307a120 0608000000000000002a"),
            PaymentOnion.ChannelRelayPayload.create(ShortChannelId(561), MilliSatoshi(1105), CltvExpiry(1729)) to Hex.decode("12 02020451 040206c1 06080000000000000231")
        )

        testCases.forEach {
            val expected = it.key
            val decoded = PaymentOnion.ChannelRelayPayload.read(it.value)
            assertEquals(expected, decoded)
            val encoded = decoded.write()
            assertArrayEquals(it.value, encoded)
        }
    }

    @Test
    fun `encode - decode variable-length (tlv) node relay per-hop payload`() {
        val nodeId = PublicKey(Hex.decode("02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"))
        val expected = PaymentOnion.NodeRelayPayload(TlvStream(listOf(OnionPaymentPayloadTlv.AmountToForward(561.msat), OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(42)), OnionPaymentPayloadTlv.OutgoingNodeId(nodeId))))
        val bin = Hex.decode("2e 02020231 04012a fe000102322102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619")

        val decoded = PaymentOnion.NodeRelayPayload.read(bin)
        assertEquals(expected, decoded)
        assertEquals(decoded.amountToForward, 561.msat)
        assertEquals(decoded.totalAmount, 561.msat)
        assertEquals(decoded.outgoingCltv, CltvExpiry(42))
        assertEquals(decoded.outgoingNodeId, nodeId)
        assertNull(decoded.paymentSecret)
        assertNull(decoded.invoiceFeatures)
        assertNull(decoded.invoiceRoutingInfo)

        val encoded = expected.write()
        assertArrayEquals(bin, encoded)
    }

    @Test
    fun `encode - decode variable-length (tlv) node relay to legacy per-hop payload`() {
        val nodeId = PublicKey(Hex.decode("02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"))
        val features = ByteVector("0a")
        val node1 = PublicKey(Hex.decode("036d6caac248af96f6afa7f904f550253a0f3ef3f5aa2fe6838a95b216691468e2"))
        val node2 = PublicKey(Hex.decode("025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486"))
        val node3 = PublicKey(Hex.decode("02a051267759c3a149e3e72372f4e0c4054ba597ebfd0eda78a2273023667205ee"))
        val routingHints = listOf(
            listOf(PaymentRequest.TaggedField.ExtraHop(node1, ShortChannelId(1), 10.msat, 100, CltvExpiryDelta(144))),
            listOf(PaymentRequest.TaggedField.ExtraHop(node2, ShortChannelId(2), 20.msat, 150, CltvExpiryDelta(12)), PaymentRequest.TaggedField.ExtraHop(node3, ShortChannelId(3), 30.msat, 200, CltvExpiryDelta(24)))
        )
        val expected = PaymentOnion.NodeRelayPayload(
            TlvStream(
                listOf(
                    OnionPaymentPayloadTlv.AmountToForward(561.msat),
                    OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(42)),
                    OnionPaymentPayloadTlv.PaymentData(ByteVector32("eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 1105.msat),
                    OnionPaymentPayloadTlv.InvoiceFeatures(features),
                    OnionPaymentPayloadTlv.OutgoingNodeId(nodeId),
                    OnionPaymentPayloadTlv.InvoiceRoutingInfo(routingHints)
                )
            )
        )
        val bin =
            Hex.decode("fa 02020231 04012a 0822eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f2836866190451 fe00010231010a fe000102322102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619 fe000102339b01036d6caac248af96f6afa7f904f550253a0f3ef3f5aa2fe6838a95b216691468e200000000000000010000000a00000064009002025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce148600000000000000020000001400000096000c02a051267759c3a149e3e72372f4e0c4054ba597ebfd0eda78a2273023667205ee00000000000000030000001e000000c80018")

        val decoded = PaymentOnion.NodeRelayPayload.read(bin)
        assertEquals(decoded, expected)
        assertEquals(decoded.amountToForward, 561.msat)
        assertEquals(decoded.totalAmount, 1105.msat)
        assertEquals(decoded.paymentSecret, ByteVector32("eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"))
        assertEquals(decoded.outgoingCltv, CltvExpiry(42))
        assertEquals(decoded.outgoingNodeId, nodeId)
        assertEquals(decoded.invoiceFeatures, features)
        assertEquals(decoded.invoiceRoutingInfo, routingHints)

        val encoded = expected.write()
        assertArrayEquals(bin, encoded)
    }

    @Test
    fun `encode - decode variable-length (tlv) final per-hop payload`() {
        val testCases = mapOf(
            TlvStream(
                listOf(
                    OnionPaymentPayloadTlv.AmountToForward(561.msat),
                    OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(42)),
                    OnionPaymentPayloadTlv.PaymentData(ByteVector32("eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 0.msat)
                )
            ) to Hex.decode("29 02020231 04012a 0820eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"),
            TlvStream(
                listOf(
                    OnionPaymentPayloadTlv.AmountToForward(561.msat),
                    OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(42)),
                    OnionPaymentPayloadTlv.PaymentData(ByteVector32("eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 1105.msat)
                )
            ) to Hex.decode("2b 02020231 04012a 0822eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f2836866190451"),
            TlvStream(
                listOf(
                    OnionPaymentPayloadTlv.AmountToForward(561.msat),
                    OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(42)),
                    OnionPaymentPayloadTlv.PaymentData(ByteVector32("eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 4294967295L.msat)
                )
            ) to Hex.decode("2d 02020231 04012a 0824eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619ffffffff"),
            TlvStream(
                listOf(
                    OnionPaymentPayloadTlv.AmountToForward(561.msat),
                    OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(42)),
                    OnionPaymentPayloadTlv.PaymentData(ByteVector32("eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 4294967296L.msat)
                )
            ) to Hex.decode("2e 02020231 04012a 0825eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f2836866190100000000"),
            TlvStream(
                listOf(
                    OnionPaymentPayloadTlv.AmountToForward(561.msat),
                    OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(42)),
                    OnionPaymentPayloadTlv.PaymentData(ByteVector32("eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 1099511627775L.msat)
                )
            ) to Hex.decode("2e 02020231 04012a 0825eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619ffffffffff"),
            TlvStream(
                listOf(
                    OnionPaymentPayloadTlv.AmountToForward(561.msat),
                    OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(42)),
                    OnionPaymentPayloadTlv.OutgoingChannelId(ShortChannelId(1105)),
                    OnionPaymentPayloadTlv.PaymentData(ByteVector32("eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 0.msat)
                )
            ) to Hex.decode("33 02020231 04012a 06080000000000000451 0820eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"),
            TlvStream(
                listOf(
                    OnionPaymentPayloadTlv.AmountToForward(561.msat),
                    OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(42)),
                    OnionPaymentPayloadTlv.PaymentData(ByteVector32("eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"), 0.msat)
                ),
                listOf(GenericTlv(65535, ByteVector("06c1")))
            ) to Hex.decode("2f 02020231 04012a 0820eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619 fdffff0206c1"),
            TlvStream(
                listOf(
                    OnionPaymentPayloadTlv.AmountToForward(561.msat),
                    OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(42)),
                    OnionPaymentPayloadTlv.PaymentData(ByteVector32("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"), 0.msat),
                    OnionPaymentPayloadTlv.TrampolineOnion(
                        OnionRoutingPacket(
                            0,
                            ByteVector("02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"),
                            ByteVector("cff34152f3a36e52ca94e74927203a560392b9cc7ce3c45809c6be52166c24a595716880f95f178bf5b30ca63141f74db6e92795c6130877cfdac3d4bd3087ee73c65d627ddd709112a848cc99e303f3706509aa43ba7c8a88cba175fccf9a8f5016ef06d3b935dbb15196d7ce16dc1a7157845566901d7b2197e52cab4ce487014b14816e5805f9fcacb4f8f88b8ff176f1b94f6ce6b00bc43221130c17d20ef629db7c5f7eafaa166578c720619561dd14b3277db557ec7dcdb793771aef0f2f667cfdbeae3ac8d331c5994779dffb31e5fc0dbdedc0c592ca6d21c18e47fe3528d6975c19517d7e2ea8c5391cf17d0fe30c80913ed887234ccb48808f7ef9425bcd815c3b586210979e3bb286ef2851bf9ce04e28c40a203df98fd648d2f1936fd2f1def0e77eecb277229b4b682322371c0a1dbfcd723a991993df8cc1f2696b84b055b40a1792a29f710295a18fbd351b0f3ff34cd13941131b8278ba79303c89117120eea691738a9954908195143b039dbeed98f26a92585f3d15cf742c953799d3272e0545e9b744be9d3b4c"),
                            ByteVector32("bb079bfc4b35190eee9f59a1d7b41ba2f773179f322dafb4b1af900c289ebd6c")
                        )
                    )
                )
            ) to Hex.decode(
                "fd0203 02020231 04012a 0820ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff fe00010234fd01d20002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619cff34152f3a36e52ca94e74927203a560392b9cc7ce3c45809c6be52166c24a595716880f95f178bf5b30ca63141f74db6e92795c6130877cfdac3d4bd3087ee73c65d627ddd709112a848cc99e303f3706509aa43ba7c8a88cba175fccf9a8f5016ef06d3b935dbb15196d7ce16dc1a7157845566901d7b2197e52cab4ce487014b14816e5805f9fcacb4f8f88b8ff176f1b94f6ce6b00bc43221130c17d20ef629db7c5f7eafaa166578c720619561dd14b3277db557ec7dcdb793771aef0f2f667cfdbeae3ac8d331c5994779dffb31e5fc0dbdedc0c592ca6d21c18e47fe3528d6975c19517d7e2ea8c5391cf17d0fe30c80913ed887234ccb48808f7ef9425bcd815c3b586210979e3bb286ef2851bf9ce04e28c40a203df98fd648d2f1936fd2f1def0e77eecb277229b4b682322371c0a1dbfcd723a991993df8cc1f2696b84b055b40a1792a29f710295a18fbd351b0f3ff34cd13941131b8278ba79303c89117120eea691738a9954908195143b039dbeed98f26a92585f3d15cf742c953799d3272e0545e9b744be9d3b4cbb079bfc4b35190eee9f59a1d7b41ba2f773179f322dafb4b1af900c289ebd6c"
            )
        )

        testCases.forEach {
            val expected = it.key
            val decoded = PaymentOnion.FinalPayload.read(it.value)
            assertEquals(decoded, PaymentOnion.FinalPayload(expected))
            assertEquals(decoded.amount, 561.msat)
            assertEquals(decoded.expiry, CltvExpiry(42))

            val encoded = PaymentOnion.FinalPayload(expected).write()
            assertArrayEquals(it.value, encoded)
        }
    }

    @Test
    fun `encode - decode variable-length (tlv) final per-hop payload with custom user records`() {
        val tlvs = TlvStream(
            listOf(OnionPaymentPayloadTlv.AmountToForward(561.msat), OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(42)), OnionPaymentPayloadTlv.PaymentData(ByteVector32.Zeroes, 0.msat)),
            listOf(GenericTlv(5432123457L, ByteVector("16c7ec71663784ff100b6eface1e60a97b92ea9d18b8ece5e558586bc7453828")))
        )
        val bin = Hex.decode("53 02020231 04012a 08200000000000000000000000000000000000000000000000000000000000000000 ff0000000143c7a0412016c7ec71663784ff100b6eface1e60a97b92ea9d18b8ece5e558586bc7453828")

        val encoded = PaymentOnion.FinalPayload(tlvs).write()
        assertArrayEquals(bin, encoded)
        assertEquals(PaymentOnion.FinalPayload(tlvs), PaymentOnion.FinalPayload.read(bin))
    }

    @Test
    fun `decode multi-part final per-hop payload`() {
        val notMultiPart = PaymentOnion.FinalPayload.read(Hex.decode("29 02020231 04012a 08200000000000000000000000000000000000000000000000000000000000000000"))
        assertEquals(notMultiPart.totalAmount, 561.msat)
        assertEquals(notMultiPart.paymentSecret, ByteVector32.Zeroes)

        val multiPart = PaymentOnion.FinalPayload.read(Hex.decode("2b 02020231 04012a 0822eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f2836866190451"))
        assertEquals(multiPart.amount, 561.msat)
        assertEquals(multiPart.expiry, CltvExpiry(42))
        assertEquals(multiPart.totalAmount, 1105.msat)
        assertEquals(multiPart.paymentSecret, ByteVector32("eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"))

        val multiPartNoTotalAmount = PaymentOnion.FinalPayload.read(Hex.decode("29 02020231 04012a 0820eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"))
        assertEquals(multiPartNoTotalAmount.amount, 561.msat)
        assertEquals(multiPartNoTotalAmount.expiry, CltvExpiry(42))
        assertEquals(multiPartNoTotalAmount.totalAmount, 561.msat)
        assertEquals(multiPartNoTotalAmount.paymentSecret, ByteVector32("eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"))
    }

    @Test
    fun `decode variable-length (tlv) final per-hop payload missing information`() {
        val testCases = listOf(
            Hex.decode("25 04012a 0820ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"), // missing amount
            Hex.decode("26 02020231 0820ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"), // missing cltv
            Hex.decode("07 02020231 04012a") // missing payment secret
        )

        testCases.forEach {
            assertFails { PaymentOnion.FinalPayload.read(it) }
        }
    }

    @Test
    fun `decode invalid per-hop payload`() {
        val testCases = listOf(
            // Invalid fixed-size (legacy) payload.
            Hex.decode("00 000000000000002a 000000000000002a"), // invalid length
            Hex.decode("00 000000000000002a 0000000000022ab0 0007a120 000000000000000000000000"), // legacy channel relay payload
            Hex.decode("00 0000000000000000 0000000000000451 000006c1 000000000000000000000000"), // legacy final payload
            // Invalid variable-length (tlv) payload.
            Hex.decode("00"), // empty payload is missing required information
            Hex.decode("01"), // invalid length
            Hex.decode("01 0000"), // invalid length
            Hex.decode("04 0000 2a00"), // unknown even types
            Hex.decode("04 0000 0000"), // duplicate types
            Hex.decode("04 0100 0000") // unordered types
        )

        testCases.forEach {
            assertFails { PaymentOnion.ChannelRelayPayload.read(it) }
            assertFails { PaymentOnion.NodeRelayPayload.read(it) }
            assertFails { PaymentOnion.FinalPayload.read(it) }
        }
    }
}