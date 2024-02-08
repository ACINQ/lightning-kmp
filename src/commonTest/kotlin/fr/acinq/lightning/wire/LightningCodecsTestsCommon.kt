package fr.acinq.lightning.wire

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.randomBytes
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomBytes64
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.crypto.assertArrayEquals
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.secp256k1.Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class LightningCodecsTestsCommon : LightningTestSuite() {

    private fun point(fill: Byte) = PrivateKey(ByteArray(32) { fill }).publicKey()

    fun publicKey(fill: Byte) = point(fill)

    @Test
    fun `encode - decode uint64`() {
        val testCases = mapOf(
            0UL to Hex.decode("00 00 00 00 00 00 00 00"),
            42UL to Hex.decode("00 00 00 00 00 00 00 2a"),
            6211610197754262546UL to Hex.decode("56 34 12 90 78 56 34 12"),
            17293822569102704638UL to Hex.decode("ef ff ff ff ff ff ff fe"),
            17293822569102704639UL to Hex.decode("ef ff ff ff ff ff ff ff"),
            18446744073709551614UL to Hex.decode("ff ff ff ff ff ff ff fe"),
            18446744073709551615UL to Hex.decode("ff ff ff ff ff ff ff ff")
        )

        testCases.forEach {
            val out = ByteArrayOutput()
            LightningCodecs.writeU64(it.key.toLong(), out)
            assertArrayEquals(it.value, out.toByteArray())
            val decoded = LightningCodecs.u64(ByteArrayInput(it.value))
            assertEquals(it.key, decoded.toULong())
        }
    }

    @Test
    fun `bigsize serialization`() {
        val raw = """[
    {
        "name": "zero",
        "value": 0,
        "bytes": "00"
    },
    {
        "name": "one byte value",
        "value": 42,
        "bytes": "2a"
    },
    {
        "name": "one byte high",
        "value": 252,
        "bytes": "fc"
    },
    {
        "name": "two byte low",
        "value": 253,
        "bytes": "fd00fd"
    },
    {
        "name": "two byte value",
        "value": 255,
        "bytes": "fd00ff"
    },
    {
        "name": "two byte value",
        "value": 550,
        "bytes": "fd0226"
    },
    {
        "name": "two byte high",
        "value": 65535,
        "bytes": "fdffff"
    },
    {
        "name": "four byte low",
        "value": 65536,
        "bytes": "fe00010000"
    },
    {
        "name": "four byte value",
        "value": 998000,
        "bytes": "fe000f3a70"
    },
    {
        "name": "four byte high",
        "value": 4294967295,
        "bytes": "feffffffff"
    },
    {
        "name": "eight byte low",
        "value": 4294967296,
        "bytes": "ff0000000100000000"
    },
    {
        "name": "eight byte high",
        "value": 18446744073709551615,
        "bytes": "ffffffffffffffffff"
    },
    {
        "name": "two byte not canonical",
        "value": 0,
        "bytes": "fd00fc",
        "exp_error": "decoded bigsize is not canonical"
    },
    {
        "name": "four byte not canonical",
        "value": 0,
        "bytes": "fe0000ffff",
        "exp_error": "decoded bigsize is not canonical"
    },
    {
        "name": "eight byte not canonical",
        "value": 0,
        "bytes": "ff00000000ffffffff",
        "exp_error": "decoded bigsize is not canonical"
    },
    {
        "name": "two byte short read",
        "value": 0,
        "bytes": "fd00",
        "exp_error": "unexpected EOF"
    },
    {
        "name": "four byte short read",
        "value": 0,
        "bytes": "feffff",
        "exp_error": "unexpected EOF"
    },
    {
        "name": "eight byte short read",
        "value": 0,
        "bytes": "ffffffffff",
        "exp_error": "unexpected EOF"
    },
    {
        "name": "one byte no read",
        "value": 0,
        "bytes": "",
        "exp_error": "EOF"
    },
    {
        "name": "two byte no read",
        "value": 0,
        "bytes": "fd",
        "exp_error": "unexpected EOF"
    },
    {
        "name": "four byte no read",
        "value": 0,
        "bytes": "fe",
        "exp_error": "unexpected EOF"
    },
    {
        "name": "eight byte no read",
        "value": 0,
        "bytes": "ff",
        "exp_error": "unexpected EOF"
    }
]"""

        val items = Json.parseToJsonElement(raw)
        items.jsonArray.forEach {
            val name = it.jsonObject["name"]?.jsonPrimitive?.content!!
            val bytes = Hex.decode(it.jsonObject["bytes"]?.jsonPrimitive?.content!!)
            val value = it.jsonObject["value"]?.jsonPrimitive?.content?.toULong()!!
            if (it.jsonObject["exp_error"] != null) {
                assertFails(name) { LightningCodecs.bigSize(ByteArrayInput(bytes)) }
            } else {
                assertEquals(value, LightningCodecs.bigSize(ByteArrayInput(bytes)).toULong(), name)
                val out = ByteArrayOutput()
                LightningCodecs.writeBigSize(value.toLong(), out)
                assertArrayEquals(bytes, out.toByteArray())
            }
        }
    }

    @Test
    fun `encode - decode init message`() {
        data class TestCase(val encoded: ByteVector, val decoded: Init?, val reEncoded: ByteVector? = null)

        val chainHash1 = ByteVector32.fromValidHex("0101010101010101010101010101010101010101010101010101010101010101")
        val chainHash2 = ByteVector32.fromValidHex("0202020202020202020202020202020202020202020202020202020202020202")

        val testCases = listOf(
            TestCase(ByteVector("0000 0000"), Init(Features.empty)), // no features
            TestCase(ByteVector("0000 0002088a"), Init(Features(ByteVector("088a")))), // no global features
            TestCase(ByteVector("00020200 0000"), Init(Features(ByteVector("0200"))), ByteVector("0000 00020200")), // no local features
            TestCase(ByteVector("00020200 0002088a"), Init(Features(ByteVector("0a8a"))), ByteVector("0000 00020a8a")), // local and global - no conflict - same size
            TestCase(ByteVector("00020200 0003020002"), Init(Features(ByteVector("020202"))), ByteVector("0000 0003020202")), // local and global - no conflict - different sizes
            TestCase(ByteVector("00020a02 0002088a"), Init(Features(ByteVector("0a8a"))), ByteVector("0000 00020a8a")), // local and global - conflict - same size
            TestCase(ByteVector("00022200 000302aaa2"), Init(Features(ByteVector("02aaa2"))), ByteVector("0000 000302aaa2")), // local and global - conflict - different sizes
            TestCase(
                ByteVector("0000 0002088a 03012a05022aa2"),
                Init(Features(ByteVector("088a")), tlvs = TlvStream(records = emptySet(), unknown = setOf(GenericTlv(3, ByteVector("2a")), GenericTlv(5, ByteVector("2aa2")))))
            ), // unknown odd records
            TestCase(ByteVector("0000 0002088a 03012a04022aa2"), decoded = null), // unknown even records
            TestCase(ByteVector("0000 0002088a 0120010101010101010101010101010101010101010101010101010101010101"), decoded = null), // invalid tlv stream
            TestCase(ByteVector("0000 0002088a 01200101010101010101010101010101010101010101010101010101010101010101"), Init(Features(ByteVector("088a")), listOf(chainHash1), listOf())), // single network
            TestCase(
                ByteVector("0000 0002088a 014001010101010101010101010101010101010101010101010101010101010101010202020202020202020202020202020202020202020202020202020202020202"),
                Init(Features(ByteVector("088a")), listOf(chainHash1, chainHash2), listOf())
            ), // multiple networks
            TestCase(
                ByteVector("0000 0002088a 01200101010101010101010101010101010101010101010101010101010101010101 03012a"),
                Init(Features(ByteVector("088a")), tlvs = TlvStream(records = setOf(InitTlv.Networks(listOf(chainHash1))), unknown = setOf(GenericTlv(3, ByteVector("2a")))))
            ), // network and unknown odd records
            TestCase(ByteVector("0000 0002088a 01200101010101010101010101010101010101010101010101010101010101010101 02012a"), decoded = null), // network and unknown even records
            TestCase(
                ByteVector("0000 0002088a fd05391007d001f4003200000000025800000000"),
                Init(Features(ByteVector("088a")), chainHashs = listOf(), liquidityRates = listOf(LiquidityAds.LeaseRate(2000, 500, 50, 0.sat, 600, 0.msat))),
            ), // one liquidity ads
            TestCase(
                ByteVector("0000 0002088a fd05392003f0019000c8000061a80064000186a00fc001f401f4000027100096000249f0"),
                Init(
                    Features(ByteVector("088a")),
                    chainHashs = listOf(),
                    liquidityRates = listOf(LiquidityAds.LeaseRate(1008, 400, 200, 25_000.sat, 100, 100_000.msat), LiquidityAds.LeaseRate(4032, 500, 500, 10_000.sat, 150, 150_000.msat))
                ),
            ), // two liquidity ads
        )

        for (testCase in testCases) {
            val result = kotlin.runCatching { Init.read(testCase.encoded.toByteArray()) }
            if (testCase.decoded == null) {
                assertTrue(result.isFailure, testCase.toString())
            } else {
                val decoded = result.getOrNull()!!
                assertEquals(testCase.decoded, decoded)
                val encoded = decoded.write()
                assertEquals(testCase.reEncoded ?: testCase.encoded, ByteVector(encoded), testCase.toString())
            }
        }
    }

    @Test
    fun `encode - decode warning message`() {
        val testCases = mapOf(
            Warning("") to ByteVector("000100000000000000000000000000000000000000000000000000000000000000000000"),
            Warning("connection-level issue") to ByteVector("000100000000000000000000000000000000000000000000000000000000000000000016636f6e6e656374696f6e2d6c6576656c206973737565"),
            Warning(ByteVector32.One, "") to ByteVector("000101000000000000000000000000000000000000000000000000000000000000000000"),
            Warning(ByteVector32.One, "channel-specific issue") to ByteVector("0001010000000000000000000000000000000000000000000000000000000000000000166368616e6e656c2d7370656369666963206973737565"),
        )

        testCases.forEach {
            val decoded = LightningMessage.decode(it.value.toByteArray())
            assertNotNull(decoded)
            assertEquals(it.key, decoded)
            val reEncoded = LightningMessage.encode(decoded)
            assertEquals(it.value, ByteVector(reEncoded))
        }
    }

    @Test
    fun `decode invalid open_channel`() {
        val defaultEncoded = ByteVector(
            "0000000000000000000000000000000000000000000000000000000000000000 0100000000000000000000000000000000000000000000000000000000000000 00001388 00000fa0 000000000003d090 00000000000001f4 000000000000c350 000000000000000f 0090 01e3 0009eb10 031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f 024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d0766 02531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe337 03462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b 0362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f7 03f006a18d5653c4edf5391ff23a61f03ff83d237e880ee61187fa9f379a028e0a 01"
        )
        val testCases = listOf(
            defaultEncoded + ByteVector("00"), // truncated length
            defaultEncoded + ByteVector("01"), // truncated length
            defaultEncoded + ByteVector("0004 123456"), // truncated upfront_shutdown_script
            defaultEncoded + ByteVector("0000 2a012a"), // invalid tlv stream (unknown even record)
            defaultEncoded + ByteVector("0000 01012a 030201"), // invalid tlv stream (truncated)
            defaultEncoded + ByteVector("2a012a"), // invalid tlv stream (unknown even record)
            defaultEncoded + ByteVector("01012a 030201") // invalid tlv stream (truncated)
        )
        testCases.forEach {
            assertFails { OpenDualFundedChannel.read(it.toByteArray()) }
        }
    }

    @Test
    fun `encode - decode open_channel`() {
        // @formatter:off
        val defaultOpen = OpenDualFundedChannel(BlockHash(ByteVector32.Zeroes), ByteVector32.One, FeeratePerKw(5000.sat), FeeratePerKw(4000.sat), 250_000.sat, 500.sat, 50_000, 15.msat, CltvExpiryDelta(144), 483, 650_000, publicKey(1), publicKey(2), publicKey(3), publicKey(4), publicKey(5), publicKey(6), publicKey(7), 1.toByte())
        val defaultEncoded = ByteVector("0040 0000000000000000000000000000000000000000000000000000000000000000 0100000000000000000000000000000000000000000000000000000000000000 00001388 00000fa0 000000000003d090 00000000000001f4 000000000000c350 000000000000000f 0090 01e3 0009eb10 031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f 024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d0766 02531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe337 03462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b 0362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f7 03f006a18d5653c4edf5391ff23a61f03ff83d237e880ee61187fa9f379a028e0a 02989c0b76cb563971fdc9bef31ec06c3560f3249d6ee9e5d83c57625596e05f6f 01")
        val testCases = listOf(
            defaultOpen to defaultEncoded,
            defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.ChannelTypeTlv(ChannelType.SupportedChannelType.AnchorOutputs))) to (defaultEncoded + ByteVector("0103101000")),
            defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.ChannelTypeTlv(ChannelType.SupportedChannelType.AnchorOutputs), ChannelTlv.PushAmountTlv(25_000.msat))) to (defaultEncoded + ByteVector("0103101000 fe470000070261a8")),
            defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.ChannelTypeTlv(ChannelType.SupportedChannelType.AnchorOutputs), ChannelTlv.RequireConfirmedInputsTlv)) to (defaultEncoded + ByteVector("0103101000 0200")),
            defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.ChannelTypeTlv(ChannelType.SupportedChannelType.AnchorOutputs), ChannelTlv.RequestFunds(50_000.sat, 2500, 500_000))) to (defaultEncoded + ByteVector("0103101000 fd05390e000000000000c35009c40007a120")),
            defaultOpen.copy(tlvStream = TlvStream(setOf(ChannelTlv.ChannelTypeTlv(ChannelType.SupportedChannelType.AnchorOutputs)), setOf(GenericTlv(321, ByteVector("2a2a")), GenericTlv(325, ByteVector("02"))))) to (defaultEncoded + ByteVector("0103101000 fd0141022a2a fd01450102")),
            defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.OriginTlv(Origin.PayToOpenOrigin(ByteVector32.fromValidHex("187bf923f7f11ef732b73c417eb5a57cd4667b20a6f130ff505cd7ad3ab87281"), 1_000_000.msat, 1234.sat, 200_000_000.msat)))) to (defaultEncoded + ByteVector("fe47000005 3a 0001 187bf923f7f11ef732b73c417eb5a57cd4667b20a6f130ff505cd7ad3ab87281 00000000000004d2 00000000000f4240 000000000bebc200")),
            defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.OriginTlv(Origin.PleaseOpenChannelOrigin(ByteVector32("2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db25"), 1_234_567.msat, 321.sat, 1_111_000.msat)))) to (defaultEncoded + ByteVector("fe47000005 3a 0004 2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db25 0000000000000141 000000000012d687 000000000010f3d8")),
        )
        // @formatter:on
        testCases.forEach { (open, bin) ->
            val decoded = LightningMessage.decode(bin.toByteArray())
            assertNotNull(decoded)
            assertEquals(decoded, open)
            val encoded = LightningMessage.encode(open)
            assertEquals(encoded.byteVector(), bin)
        }
    }

    @Test
    fun `encode - decode accept_channel`() {
        // @formatter:off
        val defaultAccept = AcceptDualFundedChannel(ByteVector32.One, 50_000.sat, 473.sat, 100_000_000, 1.msat, 6, CltvExpiryDelta(144), 50, publicKey(1), point(2), point(3), point(4), point(5), point(6), publicKey(7))
        val defaultEncoded = ByteVector("0041 0100000000000000000000000000000000000000000000000000000000000000 000000000000c350 00000000000001d9 0000000005f5e100 0000000000000001 00000006 0090 0032 031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f 024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d0766 02531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe337 03462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b 0362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f7 03f006a18d5653c4edf5391ff23a61f03ff83d237e880ee61187fa9f379a028e0a 02989c0b76cb563971fdc9bef31ec06c3560f3249d6ee9e5d83c57625596e05f6f")
        val testCases = listOf(
            defaultAccept to defaultEncoded,
            defaultAccept.copy(tlvStream = TlvStream(ChannelTlv.ChannelTypeTlv(ChannelType.UnsupportedChannelType(Features(Feature.StaticRemoteKey to FeatureSupport.Mandatory))))) to (defaultEncoded + ByteVector("01021000")),
            defaultAccept.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector("01abcdef")), ChannelTlv.ChannelTypeTlv(ChannelType.SupportedChannelType.AnchorOutputs))) to (defaultEncoded + ByteVector("000401abcdef 0103101000")),
            defaultAccept.copy(tlvStream = TlvStream(ChannelTlv.ChannelTypeTlv(ChannelType.SupportedChannelType.AnchorOutputs), ChannelTlv.WillFund(ByteVector64.Zeroes, 750, 150, 250.sat, 100, 5.msat))) to (defaultEncoded + ByteVector("0103101000 fd05394e0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000002ee0096000000fa006400000005")),
            defaultAccept.copy(tlvStream = TlvStream(ChannelTlv.ChannelTypeTlv(ChannelType.SupportedChannelType.AnchorOutputs), ChannelTlv.PushAmountTlv(1729.msat))) to (defaultEncoded + ByteVector("0103101000 fe470000070206c1")),
            defaultAccept.copy(tlvStream = TlvStream(ChannelTlv.ChannelTypeTlv(ChannelType.SupportedChannelType.AnchorOutputs), ChannelTlv.RequireConfirmedInputsTlv)) to (defaultEncoded + ByteVector("0103101000 0200")),
            defaultAccept.copy(tlvStream = TlvStream(setOf(ChannelTlv.ChannelTypeTlv(ChannelType.SupportedChannelType.AnchorOutputs)), setOf(GenericTlv(113, ByteVector("deadbeef"))))) to (defaultEncoded + ByteVector("0103101000 7104deadbeef")),
        )
        // @formatter:on
        testCases.forEach { (accept, bin) ->
            val decoded = LightningMessage.decode(bin.toByteArray())
            assertNotNull(decoded)
            assertEquals(decoded, accept)
            val encoded = LightningMessage.encode(accept)
            assertEquals(encoded.byteVector(), bin)
        }
    }

    @Test
    fun `encode - decode channel_ready`() {
        val testCases = listOf(
            // @formatter:off
            ChannelReady(ByteVector32("02094a1009491c4aa4320ce4400bbb556399b720a35b0922b73316bfeb49e118"), PublicKey.fromHex("02df89f6e2a2c3e7dfd536c4b65add892026c032e6ec818347e0e44b4ab2fcadca")) to "002402094a1009491c4aa4320ce4400bbb556399b720a35b0922b73316bfeb49e11802df89f6e2a2c3e7dfd536c4b65add892026c032e6ec818347e0e44b4ab2fcadca",
            ChannelReady(ByteVector32("02094a1009491c4aa4320ce4400bbb556399b720a35b0922b73316bfeb49e118"), PublicKey.fromHex("02df89f6e2a2c3e7dfd536c4b65add892026c032e6ec818347e0e44b4ab2fcadca"), TlvStream(ChannelReadyTlv.ShortChannelIdTlv(ShortChannelId(1729)))) to "002402094a1009491c4aa4320ce4400bbb556399b720a35b0922b73316bfeb49e11802df89f6e2a2c3e7dfd536c4b65add892026c032e6ec818347e0e44b4ab2fcadca010800000000000006c1",
            // @formatter:on
        )
        testCases.forEach { (channelReady, bin) ->
            val decoded = LightningMessage.decode(Hex.decode(bin))
            assertEquals(decoded, channelReady)
            val encoded = LightningMessage.encode(channelReady)
            assertEquals(Hex.encode(encoded), bin)
        }
    }

    @Test
    fun `encode - decode commit_sig`() {
        val channelId = ByteVector32.fromValidHex("2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db25")
        val signature = ByteVector64.fromValidHex("05e06d9a8fdfbb3625051ff2e3cdf82679cc2268beee6905941d6dd8a067cd62711e04b119a836aa0eebe07545172cefb228860fea6c797178453a319169bed7")
        val alternateSigs = listOf(
            CommitSigTlv.AlternativeFeerateSig(FeeratePerKw(253.sat), ByteVector64.fromValidHex("c49269a9baa73a5ec44b63bdcaabf9c7c6477f72866b822f8502e5c989aa3562fe69d72bec62025d3474b9c2d947ec6d68f9f577be5fab8ee80503cefd8846c3")),
            CommitSigTlv.AlternativeFeerateSig(FeeratePerKw(500.sat), ByteVector64.fromValidHex("2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db252a2f914ea1fcbd580b80cdea60226f63288cd44bd84a8850c9189a24f08c7cc5")),
            CommitSigTlv.AlternativeFeerateSig(FeeratePerKw(750.sat), ByteVector64.fromValidHex("83a7a1a04141ac8ab2818f4a872ea86716ef9aac0852146bcdbc2cc49aecc985899a63513f41ed2502a321a4945689239d12bdab778c1a2e8bf7c3f19ec53b58")),
        )
        val backup = EncryptedChannelData(ByteVector.fromHex("fe69d72bec62025d3474b9c2d947ec6d68f9f577be5fab8ee80503cefd8846c303a6680e79e30f050d4f32f1fb9d046cc6efb5ed4cc99eeedba6b2e89cbf838691"))
        val testCases = listOf(
            // @formatter:off
            CommitSig(channelId, signature, listOf(), TlvStream(CommitSigTlv.ChannelData(backup))) to "00842dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db2505e06d9a8fdfbb3625051ff2e3cdf82679cc2268beee6905941d6dd8a067cd62711e04b119a836aa0eebe07545172cefb228860fea6c797178453a319169bed70000fe4701000041fe69d72bec62025d3474b9c2d947ec6d68f9f577be5fab8ee80503cefd8846c303a6680e79e30f050d4f32f1fb9d046cc6efb5ed4cc99eeedba6b2e89cbf838691",
            CommitSig(channelId, signature, listOf(), TlvStream(CommitSigTlv.ChannelData(backup), CommitSigTlv.AlternativeFeerateSigs(listOf()))) to "00842dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db2505e06d9a8fdfbb3625051ff2e3cdf82679cc2268beee6905941d6dd8a067cd62711e04b119a836aa0eebe07545172cefb228860fea6c797178453a319169bed70000fe4701000041fe69d72bec62025d3474b9c2d947ec6d68f9f577be5fab8ee80503cefd8846c303a6680e79e30f050d4f32f1fb9d046cc6efb5ed4cc99eeedba6b2e89cbf838691fe470100010100",
            CommitSig(channelId, signature, listOf(), TlvStream(CommitSigTlv.AlternativeFeerateSigs(alternateSigs))) to "00842dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db2505e06d9a8fdfbb3625051ff2e3cdf82679cc2268beee6905941d6dd8a067cd62711e04b119a836aa0eebe07545172cefb228860fea6c797178453a319169bed70000fe47010001cd03000000fdc49269a9baa73a5ec44b63bdcaabf9c7c6477f72866b822f8502e5c989aa3562fe69d72bec62025d3474b9c2d947ec6d68f9f577be5fab8ee80503cefd8846c3000001f42dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db252a2f914ea1fcbd580b80cdea60226f63288cd44bd84a8850c9189a24f08c7cc5000002ee83a7a1a04141ac8ab2818f4a872ea86716ef9aac0852146bcdbc2cc49aecc985899a63513f41ed2502a321a4945689239d12bdab778c1a2e8bf7c3f19ec53b58",
            CommitSig(channelId, signature, listOf(), TlvStream(CommitSigTlv.ChannelData(backup), CommitSigTlv.AlternativeFeerateSigs(alternateSigs))) to "00842dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db2505e06d9a8fdfbb3625051ff2e3cdf82679cc2268beee6905941d6dd8a067cd62711e04b119a836aa0eebe07545172cefb228860fea6c797178453a319169bed70000fe4701000041fe69d72bec62025d3474b9c2d947ec6d68f9f577be5fab8ee80503cefd8846c303a6680e79e30f050d4f32f1fb9d046cc6efb5ed4cc99eeedba6b2e89cbf838691fe47010001cd03000000fdc49269a9baa73a5ec44b63bdcaabf9c7c6477f72866b822f8502e5c989aa3562fe69d72bec62025d3474b9c2d947ec6d68f9f577be5fab8ee80503cefd8846c3000001f42dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db252a2f914ea1fcbd580b80cdea60226f63288cd44bd84a8850c9189a24f08c7cc5000002ee83a7a1a04141ac8ab2818f4a872ea86716ef9aac0852146bcdbc2cc49aecc985899a63513f41ed2502a321a4945689239d12bdab778c1a2e8bf7c3f19ec53b58",
            // @formatter:on
        )
        testCases.forEach { (commitSig, bin) ->
            val decoded = LightningMessage.decode(Hex.decode(bin))
            assertEquals(decoded, commitSig)
            val encoded = LightningMessage.encode(commitSig)
            assertEquals(Hex.encode(encoded), bin)
        }
    }

    @Test
    fun `encode - decode interactive-tx messages`() {
        val channelId1 = ByteVector32("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val channelId2 = ByteVector32("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        val swapInUserKey = PublicKey.fromHex("03462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b")
        val swapInServerKey = PublicKey.fromHex("03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f")
        val swapInRefundDelay = 144
        val swapInSignatures = listOf(
            ByteVector64("c49269a9baa73a5ec44b63bdcaabf9c7c6477f72866b822f8502e5c989aa3562fe69d72bec62025d3474b9c2d947ec6d68f9f577be5fab8ee80503cefd8846c3"),
            ByteVector64("2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db252a2f914ea1fcbd580b80cdea60226f63288cd44bd84a8850c9189a24f08c7cc5"),
        )
        val signature = ByteVector64("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        // This is a random mainnet transaction.
        val tx1 = Transaction.read(
            "020000000001014ade359c5deb7c1cde2e94f401854658f97d7fa31c17ce9a831db253120a0a410100000017160014eb9a5bd79194a23d19d6ec473c768fb74f9ed32cffffffff021ca408000000000017a914946118f24bb7b37d5e9e39579e4a411e70f5b6a08763e703000000000017a9143638b2602d11f934c04abc6adb1494f69d1f14af8702473044022059ddd943b399211e4266a349f26b3289979e29f9b067792c6cfa8cc5ae25f44602204d627a5a5b603d0562e7969011fb3d64908af90a3ec7c876eaa9baf61e1958af012102f5188df1da92ed818581c29778047800ed6635788aa09d9469f7d17628f7323300000000"
        )
        // This is a random, longer mainnet transaction.
        val tx2 = Transaction.read(
            "0200000000010142180a8812fc79a3da7fb2471eff3e22d7faee990604c2ba7f2fc8dfb15b550a0200000000feffffff030f241800000000001976a9146774040642a78ca3b8b395e70f8391b21ec026fc88ac4a155801000000001600148d2e0b57adcb8869e603fd35b5179caf053361253b1d010000000000160014e032f4f4b9f8611df0d30a20648c190c263bbc33024730440220506005aa347f5b698542cafcb4f1a10250aeb52a609d6fd67ef68f9c1a5d954302206b9bb844343f4012bccd9d08a0f5430afb9549555a3252e499be7df97aae477a012103976d6b3eea3de4b056cd88cdfd50a22daf121e0fb5c6e45ba0f40e1effbd275a00000000"
        )
        val testCases = listOf(
            // @formatter:off
            TxAddInput(channelId1, 561, tx1, 1, 5u) to ByteVector("0042 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000000231 00f7 020000000001014ade359c5deb7c1cde2e94f401854658f97d7fa31c17ce9a831db253120a0a410100000017160014eb9a5bd79194a23d19d6ec473c768fb74f9ed32cffffffff021ca408000000000017a914946118f24bb7b37d5e9e39579e4a411e70f5b6a08763e703000000000017a9143638b2602d11f934c04abc6adb1494f69d1f14af8702473044022059ddd943b399211e4266a349f26b3289979e29f9b067792c6cfa8cc5ae25f44602204d627a5a5b603d0562e7969011fb3d64908af90a3ec7c876eaa9baf61e1958af012102f5188df1da92ed818581c29778047800ed6635788aa09d9469f7d17628f7323300000000 00000001 00000005"),
            TxAddInput(channelId2, 0, tx2, 2, 0u) to ByteVector("0042 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 0000000000000000 0100 0200000000010142180a8812fc79a3da7fb2471eff3e22d7faee990604c2ba7f2fc8dfb15b550a0200000000feffffff030f241800000000001976a9146774040642a78ca3b8b395e70f8391b21ec026fc88ac4a155801000000001600148d2e0b57adcb8869e603fd35b5179caf053361253b1d010000000000160014e032f4f4b9f8611df0d30a20648c190c263bbc33024730440220506005aa347f5b698542cafcb4f1a10250aeb52a609d6fd67ef68f9c1a5d954302206b9bb844343f4012bccd9d08a0f5430afb9549555a3252e499be7df97aae477a012103976d6b3eea3de4b056cd88cdfd50a22daf121e0fb5c6e45ba0f40e1effbd275a00000000 00000002 00000000"),
            TxAddInput(channelId1, 561, tx1, 0, 0xfffffffdu) to ByteVector("0042 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000000231 00f7 020000000001014ade359c5deb7c1cde2e94f401854658f97d7fa31c17ce9a831db253120a0a410100000017160014eb9a5bd79194a23d19d6ec473c768fb74f9ed32cffffffff021ca408000000000017a914946118f24bb7b37d5e9e39579e4a411e70f5b6a08763e703000000000017a9143638b2602d11f934c04abc6adb1494f69d1f14af8702473044022059ddd943b399211e4266a349f26b3289979e29f9b067792c6cfa8cc5ae25f44602204d627a5a5b603d0562e7969011fb3d64908af90a3ec7c876eaa9baf61e1958af012102f5188df1da92ed818581c29778047800ed6635788aa09d9469f7d17628f7323300000000 00000000 fffffffd"),
            TxAddInput(channelId1, 561, OutPoint(tx1, 1), 5u) to ByteVector("0042 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000000231 0000 00000001 00000005 fd0451201f2ec025a33e39ef8e177afcdc1adc855bf128dc906182255aeb64efa825f106"),
            TxAddInput(channelId1, 561, tx1, 1, 5u, TlvStream(TxAddInputTlv.SwapInParams(swapInUserKey, swapInServerKey, swapInRefundDelay))) to ByteVector("0042 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000000231 00f7 020000000001014ade359c5deb7c1cde2e94f401854658f97d7fa31c17ce9a831db253120a0a410100000017160014eb9a5bd79194a23d19d6ec473c768fb74f9ed32cffffffff021ca408000000000017a914946118f24bb7b37d5e9e39579e4a411e70f5b6a08763e703000000000017a9143638b2602d11f934c04abc6adb1494f69d1f14af8702473044022059ddd943b399211e4266a349f26b3289979e29f9b067792c6cfa8cc5ae25f44602204d627a5a5b603d0562e7969011fb3d64908af90a3ec7c876eaa9baf61e1958af012102f5188df1da92ed818581c29778047800ed6635788aa09d9469f7d17628f7323300000000 00000001 00000005 fd04534603462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f00000090"),
            TxAddOutput(channelId1, 1105, 2047.sat, ByteVector("00149357014afd0ccd265658c9ae81efa995e771f472")) to ByteVector("0043 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000000451 00000000000007ff 0016 00149357014afd0ccd265658c9ae81efa995e771f472"),
            TxRemoveInput(channelId2, 561) to ByteVector("0044 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 0000000000000231"),
            TxRemoveOutput(channelId1, 1) to ByteVector("0045 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000000001"),
            TxComplete(channelId1) to ByteVector("0046 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            TxSignatures(channelId1, tx2, listOf(ScriptWitness(listOf(ByteVector("68656c6c6f2074686572652c2074686973206973206120626974636f6e212121"), ByteVector("82012088a820add57dfe5277079d069ca4ad4893c96de91f88ffb981fdc6a2a34d5336c66aff87"))), ScriptWitness(listOf(ByteVector("304402207de9ba56bb9f641372e805782575ee840a899e61021c8b1572b3ec1d5b5950e9022069e9ba998915dae193d3c25cb89b5e64370e6a3a7755e7f31cf6d7cbc2a49f6d01"), ByteVector("034695f5b7864c580bf11f9f8cb1a94eb336f2ce9ef872d2ae1a90ee276c772484")))), null, listOf(), listOf()) to ByteVector("0047 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa fc7aa8845f192959202c1b7ff704e7cbddded463c05e844676a94ccb4bed69f1 0002 004a 022068656c6c6f2074686572652c2074686973206973206120626974636f6e2121212782012088a820add57dfe5277079d069ca4ad4893c96de91f88ffb981fdc6a2a34d5336c66aff87 006b 0247304402207de9ba56bb9f641372e805782575ee840a899e61021c8b1572b3ec1d5b5950e9022069e9ba998915dae193d3c25cb89b5e64370e6a3a7755e7f31cf6d7cbc2a49f6d0121034695f5b7864c580bf11f9f8cb1a94eb336f2ce9ef872d2ae1a90ee276c772484"),
            TxSignatures(channelId2, tx1, listOf(), null, listOf(), listOf()) to ByteVector("0047 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 1f2ec025a33e39ef8e177afcdc1adc855bf128dc906182255aeb64efa825f106 0000"),
            TxSignatures(channelId2, tx1, listOf(), null, swapInSignatures, listOf()) to ByteVector("0047 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 1f2ec025a33e39ef8e177afcdc1adc855bf128dc906182255aeb64efa825f106 0000 fd025b 80 c49269a9baa73a5ec44b63bdcaabf9c7c6477f72866b822f8502e5c989aa3562fe69d72bec62025d3474b9c2d947ec6d68f9f577be5fab8ee80503cefd8846c3 2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db252a2f914ea1fcbd580b80cdea60226f63288cd44bd84a8850c9189a24f08c7cc5"),
            TxSignatures(channelId2, tx1, listOf(), null, listOf(), swapInSignatures) to ByteVector("0047 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 1f2ec025a33e39ef8e177afcdc1adc855bf128dc906182255aeb64efa825f106 0000 fd025d 80 c49269a9baa73a5ec44b63bdcaabf9c7c6477f72866b822f8502e5c989aa3562fe69d72bec62025d3474b9c2d947ec6d68f9f577be5fab8ee80503cefd8846c3 2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db252a2f914ea1fcbd580b80cdea60226f63288cd44bd84a8850c9189a24f08c7cc5"),
            TxSignatures(channelId2, tx1, listOf(), null, swapInSignatures.take(1), swapInSignatures.drop(1)) to ByteVector("0047 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 1f2ec025a33e39ef8e177afcdc1adc855bf128dc906182255aeb64efa825f106 0000 fd025b 40 c49269a9baa73a5ec44b63bdcaabf9c7c6477f72866b822f8502e5c989aa3562fe69d72bec62025d3474b9c2d947ec6d68f9f577be5fab8ee80503cefd8846c3 fd025d 40 2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db252a2f914ea1fcbd580b80cdea60226f63288cd44bd84a8850c9189a24f08c7cc5"),
            TxSignatures(channelId2, tx1, listOf(), signature, listOf(), listOf()) to ByteVector("0047 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 1f2ec025a33e39ef8e177afcdc1adc855bf128dc906182255aeb64efa825f106 0000 fd0259 40 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
            TxSignatures(channelId2, tx1, listOf(), signature, swapInSignatures, listOf()) to ByteVector("0047 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 1f2ec025a33e39ef8e177afcdc1adc855bf128dc906182255aeb64efa825f106 0000 fd0259 40 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb fd025b 80 c49269a9baa73a5ec44b63bdcaabf9c7c6477f72866b822f8502e5c989aa3562fe69d72bec62025d3474b9c2d947ec6d68f9f577be5fab8ee80503cefd8846c3 2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db252a2f914ea1fcbd580b80cdea60226f63288cd44bd84a8850c9189a24f08c7cc5"),
            TxSignatures(channelId2, tx1, listOf(), signature, listOf(), swapInSignatures) to ByteVector("0047 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 1f2ec025a33e39ef8e177afcdc1adc855bf128dc906182255aeb64efa825f106 0000 fd0259 40 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb fd025d 80 c49269a9baa73a5ec44b63bdcaabf9c7c6477f72866b822f8502e5c989aa3562fe69d72bec62025d3474b9c2d947ec6d68f9f577be5fab8ee80503cefd8846c3 2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db252a2f914ea1fcbd580b80cdea60226f63288cd44bd84a8850c9189a24f08c7cc5"),
            TxSignatures(channelId2, tx1, listOf(), signature, swapInSignatures.take(1), swapInSignatures.drop(1)) to ByteVector("0047 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 1f2ec025a33e39ef8e177afcdc1adc855bf128dc906182255aeb64efa825f106 0000 fd0259 40 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb fd025b 40 c49269a9baa73a5ec44b63bdcaabf9c7c6477f72866b822f8502e5c989aa3562fe69d72bec62025d3474b9c2d947ec6d68f9f577be5fab8ee80503cefd8846c3 fd025d 40 2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db252a2f914ea1fcbd580b80cdea60226f63288cd44bd84a8850c9189a24f08c7cc5"),
            TxInitRbf(channelId1, 8388607, FeeratePerKw(4000.sat)) to ByteVector("0048 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 007fffff 00000fa0"),
            TxInitRbf(channelId1, 0, FeeratePerKw(4000.sat), TlvStream(TxInitRbfTlv.SharedOutputContributionTlv(1_500_000.sat))) to ByteVector("0048 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 00000000 00000fa0 0008000000000016e360"),
            TxInitRbf(channelId1, 0, FeeratePerKw(4000.sat), TlvStream(TxInitRbfTlv.SharedOutputContributionTlv(0.sat))) to ByteVector("0048 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 00000000 00000fa0 00080000000000000000"),
            TxInitRbf(channelId1, 0, FeeratePerKw(4000.sat), TlvStream(TxInitRbfTlv.SharedOutputContributionTlv((-25_000).sat))) to ByteVector("0048 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 00000000 00000fa0 0008ffffffffffff9e58"),
            TxAckRbf(channelId2) to ByteVector("0049 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
            TxAckRbf(channelId2, TlvStream(TxAckRbfTlv.SharedOutputContributionTlv(450_000.sat))) to ByteVector("0049 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 0008000000000006ddd0"),
            TxAckRbf(channelId2, TlvStream(TxAckRbfTlv.SharedOutputContributionTlv(0.sat))) to ByteVector("0049 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 00080000000000000000"),
            TxAckRbf(channelId2, TlvStream(TxAckRbfTlv.SharedOutputContributionTlv((-250_000).sat))) to ByteVector("0049 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 0008fffffffffffc2f70"),
            TxAbort(channelId1, "") to ByteVector("004a aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000"),
            TxAbort(channelId1, "internal error") to ByteVector("004a aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 000e 696e7465726e616c206572726f72"),
            // @formatter:on
        )
        testCases.forEach { (message, bin) ->
            val decoded = LightningMessage.decode(bin.toByteArray())
            assertNotNull(decoded)
            assertEquals(decoded, message)
            val encoded = LightningMessage.encode(message)
            assertEquals(encoded.byteVector(), bin)
        }
    }

    @Test
    fun `encode - decode splice messages`() {
        val channelId = ByteVector32("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val fundingTxId = TxId(TxHash("24e1b2c94c4e734dd5b9c5f3c910fbb6b3b436ced6382c7186056a5a23f14566"))
        val fundingPubkey = PublicKey(ByteVector.fromHex("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"))
        val testCases = listOf(
            // @formatter:off
            Stfu(channelId, false) to ByteVector("0002 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 00"),
            Stfu(channelId, true) to ByteVector("0002 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 01"),
            SpliceInit(channelId, 100_000.sat, FeeratePerKw(2500.sat), 100, fundingPubkey) to ByteVector("9088 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 00000000000186a0 000009c4 00000064 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"),
            SpliceInit(channelId, 150_000.sat, 25_000_000.msat, FeeratePerKw(2500.sat), 100, fundingPubkey, null) to ByteVector("9088 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 00000000000249f0 000009c4 00000064 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798 fe4700000704017d7840"),
            SpliceInit(channelId, 0.sat, FeeratePerKw(500.sat), 0, fundingPubkey) to ByteVector("9088 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000000000 000001f4 00000000 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"),
            SpliceInit(channelId, (-50_000).sat, FeeratePerKw(500.sat), 0, fundingPubkey) to ByteVector("9088 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ffffffffffff3cb0 000001f4 00000000 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"),
            SpliceInit(channelId, 100_000.sat, 0.msat, FeeratePerKw(2500.sat), 100, fundingPubkey, ChannelTlv.RequestFunds(100_000.sat, 4000, 850_000)) to ByteVector("9088 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 00000000000186a0 000009c4 00000064 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798 fd05390e00000000000186a00fa0000cf850"),
            SpliceAck(channelId, 25_000.sat, fundingPubkey) to ByteVector("908a aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 00000000000061a8 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"),
            SpliceAck(channelId, 40_000.sat, 10_000_000.msat, fundingPubkey, null) to ByteVector("908a aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000009c40 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798 fe4700000703989680"),
            SpliceAck(channelId, 0.sat, fundingPubkey) to ByteVector("908a aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000000000 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"),
            SpliceAck(channelId, (-25_000).sat, fundingPubkey) to ByteVector("908a aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ffffffffffff9e58 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"),
            SpliceAck(channelId, 25_000.sat, 0.msat, fundingPubkey, ChannelTlv.WillFund(ByteVector64.Zeroes, 750, 150, 250.sat, 100, 0.msat)) to ByteVector("908a aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 00000000000061a8 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798 fd05394e0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000002ee0096000000fa006400000000"),
            SpliceLocked(channelId, fundingTxId) to ByteVector("908c aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 24e1b2c94c4e734dd5b9c5f3c910fbb6b3b436ced6382c7186056a5a23f14566"),
            // @formatter:on
        )
        testCases.forEach { (message, bin) ->
            val decoded = LightningMessage.decode(bin.toByteArray())
            assertNotNull(decoded)
            assertEquals(decoded, message)
            val encoded = LightningMessage.encode(message)
            assertEquals(encoded.byteVector(), bin)
        }
    }

    @Test
    fun `encode - decode channel_reestablish`() {
        val channelId = ByteVector32("c11b8fbd682b3c6ee11f9d7268e22bb5887cd4d3bf3338bfcc340583f685733c")
        val commitmentSecret = PrivateKey.fromHex("34f159d37cf7b5de52ec0adc3968886232f90d272e8c82e8b6f7fcb7e57c4b55")
        val commitmentPoint = PublicKey.fromHex("02bf050efff417efc09eb211ca9e4e845920e2503740800e88505b25e6f0e1e867")
        val fundingTxId = TxId(TxHash("24e1b2c94c4e734dd5b9c5f3c910fbb6b3b436ced6382c7186056a5a23f14566"))
        val testCases = listOf(
            // @formatter:off
            ChannelReestablish(channelId, 242842, 42, commitmentSecret, commitmentPoint) to ByteVector("0088 c11b8fbd682b3c6ee11f9d7268e22bb5887cd4d3bf3338bfcc340583f685733c 000000000003b49a 000000000000002a 34f159d37cf7b5de52ec0adc3968886232f90d272e8c82e8b6f7fcb7e57c4b55 02bf050efff417efc09eb211ca9e4e845920e2503740800e88505b25e6f0e1e867"),
            ChannelReestablish(channelId, 242842, 42, commitmentSecret, commitmentPoint, TlvStream(ChannelReestablishTlv.NextFunding(fundingTxId))) to ByteVector("0088 c11b8fbd682b3c6ee11f9d7268e22bb5887cd4d3bf3338bfcc340583f685733c 000000000003b49a 000000000000002a 34f159d37cf7b5de52ec0adc3968886232f90d272e8c82e8b6f7fcb7e57c4b55 02bf050efff417efc09eb211ca9e4e845920e2503740800e88505b25e6f0e1e867 00 20 24e1b2c94c4e734dd5b9c5f3c910fbb6b3b436ced6382c7186056a5a23f14566")
            // @formatter:on
        )
        testCases.forEach { (message, bin) ->
            val decoded = LightningMessage.decode(bin.toByteArray())
            assertNotNull(decoded)
            assertEquals(decoded, message)
            val encoded = LightningMessage.encode(message)
            assertEquals(encoded.byteVector(), bin)
        }
    }

    @Test
    fun `encode - decode channel_update`() {
        val channelUpdate = ChannelUpdate(
            randomBytes64(),
            BlockHash(randomBytes32()),
            ShortChannelId(561),
            1105,
            0,
            1,
            CltvExpiryDelta(144),
            100.msat,
            0.msat,
            10,
            null
        )
        val encoded = LightningMessage.encode(channelUpdate)
        val decoded = LightningMessage.decode(encoded)
        assertEquals(channelUpdate, decoded)
    }

    @Test
    fun `decode channel_update with htlc_maximum_msat`() {
        // this was generated by c-lightning
        val encoded =
            ByteVector("010258fff7d0e987e2cdd560e3bb5a046b4efe7b26c969c2f51da1dceec7bcb8ae1b634790503d5290c1a6c51d681cf8f4211d27ed33a257dcc1102862571bf1792306226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f0005a100000200005bc75919010100060000000000000001000000010000000a000000003a699d00")
        val decoded = LightningMessage.decode(encoded.toByteArray())
        val expected = ChannelUpdate(
            ByteVector64("58fff7d0e987e2cdd560e3bb5a046b4efe7b26c969c2f51da1dceec7bcb8ae1b634790503d5290c1a6c51d681cf8f4211d27ed33a257dcc1102862571bf17923"),
            BlockHash("06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"),
            ShortChannelId(0x5a10000020000L),
            1539791129,
            1,
            1,
            CltvExpiryDelta(6),
            1.msat,
            1.msat,
            10,
            980000000.msat
        )
        assertEquals(expected, decoded)
        val reEncoded = LightningMessage.encode(decoded).toByteVector()
        assertEquals(encoded, reEncoded)
    }

    @Test
    fun `encode - decode channel_update with unknown trailing bytes`() {
        val channelUpdate = ChannelUpdate(
            randomBytes64(),
            BlockHash(randomBytes32()),
            ShortChannelId(561),
            1105,
            0,
            1,
            CltvExpiryDelta(144),
            0.msat,
            10.msat,
            10,
            null,
            ByteVector("010203")
        )
        val encoded = LightningMessage.encode(channelUpdate)
        val decoded = LightningMessage.decode(encoded)
        assertEquals(channelUpdate, decoded)
    }

    @Test
    fun `encode - decode channel_announcement`() {
        val testCases = listOf(
            ChannelAnnouncement(
                randomBytes64(),
                randomBytes64(),
                randomBytes64(),
                randomBytes64(),
                Features(Hex.decode("09004200")),
                BlockHash(randomBytes32()),
                ShortChannelId(42),
                randomKey().publicKey(),
                randomKey().publicKey(),
                randomKey().publicKey(),
                randomKey().publicKey()
            ),
            ChannelAnnouncement(
                randomBytes64(),
                randomBytes64(),
                randomBytes64(),
                randomBytes64(),
                Features(mapOf()),
                BlockHash(randomBytes32()),
                ShortChannelId(42),
                randomKey().publicKey(),
                randomKey().publicKey(),
                randomKey().publicKey(),
                randomKey().publicKey(),
                ByteVector("01020304")
            ),
        )

        testCases.forEach {
            val encoded = LightningMessage.encode(it)
            val decoded = LightningMessage.decode(encoded)
            assertNotNull(decoded)
            assertEquals(it, decoded)
        }
    }

    @Test
    fun `encode - decode closing_signed`() {
        val defaultSig = ByteVector64("01010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101")
        val testCases = listOf(
            Hex.decode("0027 0100000000000000000000000000000000000000000000000000000000000000 0000000000000000 00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000") to ClosingSigned(
                ByteVector32.One,
                0.sat,
                ByteVector64.Zeroes
            ),
            Hex.decode("0027 0100000000000000000000000000000000000000000000000000000000000000 00000000000003e8 00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000") to ClosingSigned(
                ByteVector32.One,
                1000.sat,
                ByteVector64.Zeroes
            ),
            Hex.decode("0027 0100000000000000000000000000000000000000000000000000000000000000 00000000000005dc 01010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101") to ClosingSigned(
                ByteVector32.One,
                1500.sat,
                defaultSig
            ),
            Hex.decode("0027 0100000000000000000000000000000000000000000000000000000000000000 00000000000005dc 00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000 0110000000000000006400000000000007d0") to ClosingSigned(
                ByteVector32.One,
                1500.sat,
                ByteVector64.Zeroes,
                TlvStream(ClosingSignedTlv.FeeRange(100.sat, 2000.sat))
            ),
            Hex.decode("0027 0100000000000000000000000000000000000000000000000000000000000000 00000000000003e8 01010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101 0110000000000000006400000000000007d0") to ClosingSigned(
                ByteVector32.One,
                1000.sat,
                defaultSig,
                TlvStream(ClosingSignedTlv.FeeRange(100.sat, 2000.sat))
            ),
            Hex.decode("0027 0100000000000000000000000000000000000000000000000000000000000000 0000000000000064 00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000 0110000000000000006400000000000003e8 030401020304") to ClosingSigned(
                ByteVector32.One,
                100.sat,
                ByteVector64.Zeroes,
                TlvStream(setOf(ClosingSignedTlv.FeeRange(100.sat, 1000.sat)), setOf(GenericTlv(3, ByteVector("01020304"))))
            ),
        )

        testCases.forEach {
            val decoded = LightningMessage.decode(it.first)
            assertNotNull(decoded)
            assertEquals(decoded, it.second)
            val reEncoded = LightningMessage.encode(decoded)
            assertArrayEquals(reEncoded, it.first)
        }
    }

    @Test
    fun `nonreg backup channel data`() {
        val channelId = randomBytes32()
        val txHash = TxHash(randomBytes32())
        val signature = randomBytes64()
        val key = randomKey()
        val point = randomKey().publicKey()
        val randomData = randomBytes(42)

        // @formatter:off
        val refs = mapOf(
            // channel_reestablish
            Hex.decode("0088") + channelId.toByteArray() + Hex.decode("0001020304050607 0809aabbccddeeff") + key.value.toByteArray() + point.value.toByteArray() to ChannelReestablish(channelId, 0x01020304050607L, 0x0809aabbccddeeffL, key, point),
            Hex.decode("0088") + channelId.toByteArray() + Hex.decode("0001020304050607 0809aabbccddeeff") + key.value.toByteArray() + point.value.toByteArray() + Hex.decode("01 02 0102") to ChannelReestablish(channelId, 0x01020304050607L, 0x0809aabbccddeeffL, key, point, TlvStream(setOf(), setOf(GenericTlv(1, ByteVector("0102"))))),
            Hex.decode("0088") + channelId.toByteArray() + Hex.decode("0001020304050607 0809aabbccddeeff") + key.value.toByteArray() + point.value.toByteArray() + Hex.decode("fe47010000 00") to ChannelReestablish(channelId, 0x01020304050607L, 0x0809aabbccddeeffL, key, point, TlvStream(ChannelReestablishTlv.ChannelData(EncryptedChannelData.empty))),
            Hex.decode("0088") + channelId.toByteArray() + Hex.decode("0001020304050607 0809aabbccddeeff") + key.value.toByteArray() + point.value.toByteArray() + Hex.decode("01 02 0102") + Hex.decode("fe47010000 00") to ChannelReestablish(channelId, 0x01020304050607L, 0x0809aabbccddeeffL, key, point, TlvStream(setOf(ChannelReestablishTlv.ChannelData(EncryptedChannelData(ByteVector.empty))), setOf(GenericTlv(1, ByteVector("0102"))))),
            Hex.decode("0088") + channelId.toByteArray() + Hex.decode("0001020304050607 0809aabbccddeeff") + key.value.toByteArray() + point.value.toByteArray() + Hex.decode("fe47010000 07 bbbbbbbbbbbbbb") to ChannelReestablish(channelId, 0x01020304050607L, 0x0809aabbccddeeffL, key, point).withChannelData(ByteVector("bbbbbbbbbbbbbb")),
            Hex.decode("0088") + channelId.toByteArray() + Hex.decode("0001020304050607 0809aabbccddeeff") + key.value.toByteArray() + point.value.toByteArray() + Hex.decode("01 02 0102") + Hex.decode("fe47010000 07 bbbbbbbbbbbbbb") to ChannelReestablish(channelId, 0x01020304050607L, 0x0809aabbccddeeffL, key, point, TlvStream(setOf(ChannelReestablishTlv.ChannelData(EncryptedChannelData(ByteVector("bbbbbbbbbbbbbb")))), setOf(GenericTlv(1, ByteVector("0102"))))),
            // tx_signatures
            Hex.decode("0047") + channelId.toByteArray() + txHash.value.toByteArray() + Hex.decode("0000") to TxSignatures(channelId, TxId(txHash), listOf()),
            Hex.decode("0047") + channelId.toByteArray() + txHash.value.toByteArray() + Hex.decode("0000 fe47010000 00") to TxSignatures(channelId, TxId(txHash), listOf(), TlvStream(TxSignaturesTlv.ChannelData(EncryptedChannelData.empty))),
            Hex.decode("0047") + channelId.toByteArray() + txHash.value.toByteArray() + Hex.decode("0000 fe47010000 04 deadbeef") to TxSignatures(channelId, TxId(txHash), listOf(), TlvStream(TxSignaturesTlv.ChannelData(EncryptedChannelData(ByteVector("deadbeef"))))),
            Hex.decode("0047") + channelId.toByteArray() + txHash.value.toByteArray() + Hex.decode("0000 2b012a fe47010000 04 deadbeef") to TxSignatures(channelId, TxId(txHash), listOf(), TlvStream(setOf(TxSignaturesTlv.ChannelData(EncryptedChannelData(ByteVector("deadbeef")))), setOf(GenericTlv(43, ByteVector("2a"))))),
            // commit_sig
            Hex.decode("0084") + channelId.toByteArray() + signature.toByteArray() + Hex.decode("0000") to CommitSig(channelId, signature, listOf()),
            Hex.decode("0084") + channelId.toByteArray() + signature.toByteArray() + Hex.decode("0000") + Hex.decode("01 02 0102") to CommitSig(channelId, signature, listOf(), TlvStream(setOf(), setOf(GenericTlv(1, ByteVector("0102"))))),
            Hex.decode("0084") + channelId.toByteArray() + signature.toByteArray() + Hex.decode("0000 fe47010000 00") to CommitSig(channelId, signature, listOf(), TlvStream(CommitSigTlv.ChannelData(EncryptedChannelData.empty))),
            Hex.decode("0084") + channelId.toByteArray() + signature.toByteArray() + Hex.decode("0000 01020102 fe47010000 00") to CommitSig(channelId, signature, listOf(), TlvStream(setOf(CommitSigTlv.ChannelData(EncryptedChannelData.empty)), setOf(GenericTlv(1, ByteVector("0102"))))),
            Hex.decode("0084") + channelId.toByteArray() + signature.toByteArray() + Hex.decode("0000 fe47010000 07 cccccccccccccc") to CommitSig(channelId, signature, listOf()).withChannelData(ByteVector("cccccccccccccc")),
            Hex.decode("0084") + channelId.toByteArray() + signature.toByteArray() + Hex.decode("0000 01020102 fe47010000 07 cccccccccccccc") to CommitSig(channelId, signature, listOf(), TlvStream(setOf(CommitSigTlv.ChannelData(EncryptedChannelData(ByteVector("cccccccccccccc")))), setOf(GenericTlv(1, ByteVector("0102"))))),
            // revoke_and_ack
            Hex.decode("0085") + channelId.toByteArray() + key.value.toByteArray() + point.value.toByteArray() to RevokeAndAck(channelId, key, point),
            Hex.decode("0085") + channelId.toByteArray() + key.value.toByteArray() + point.value.toByteArray() + Hex.decode("01 02 0102") to RevokeAndAck(channelId, key, point, TlvStream(setOf(), setOf(GenericTlv(1, ByteVector("0102"))))),
            Hex.decode("0085") + channelId.toByteArray() + key.value.toByteArray() + point.value.toByteArray() + Hex.decode("fe47010000 00") to RevokeAndAck(channelId, key, point, TlvStream(RevokeAndAckTlv.ChannelData(EncryptedChannelData.empty))),
            Hex.decode("0085") + channelId.toByteArray() + key.value.toByteArray() + point.value.toByteArray() + Hex.decode("01 02 0102") + Hex.decode("fe47010000 00") to RevokeAndAck(channelId, key, point, TlvStream(setOf(RevokeAndAckTlv.ChannelData(EncryptedChannelData.empty)), setOf(GenericTlv(1, ByteVector("0102"))))),
            Hex.decode("0085") + channelId.toByteArray() + key.value.toByteArray() + point.value.toByteArray() + Hex.decode("fe47010000 07 cccccccccccccc") to RevokeAndAck(channelId, key, point).withChannelData(ByteVector("cccccccccccccc")),
            Hex.decode("0085") + channelId.toByteArray() + key.value.toByteArray() + point.value.toByteArray() + Hex.decode("01 02 0102") + Hex.decode("fe47010000 07 cccccccccccccc") to RevokeAndAck(channelId, key, point, TlvStream(setOf(RevokeAndAckTlv.ChannelData(EncryptedChannelData(ByteVector("cccccccccccccc")))), setOf(GenericTlv(1, ByteVector("0102"))))),
            // shutdown
            Hex.decode("0026") + channelId.toByteArray() + Hex.decode("002a") + randomData to Shutdown(channelId, randomData.toByteVector()),
            Hex.decode("0026") + channelId.toByteArray() + Hex.decode("002a") + randomData + Hex.decode("01 02 0102") to Shutdown(channelId, randomData.toByteVector(), TlvStream(setOf(), setOf(GenericTlv(1, ByteVector("0102"))))),
            Hex.decode("0026") + channelId.toByteArray() + Hex.decode("002a") + randomData + Hex.decode("fe47010000 00") to Shutdown(channelId, randomData.toByteVector(), TlvStream(ShutdownTlv.ChannelData(EncryptedChannelData.empty))),
            Hex.decode("0026") + channelId.toByteArray() + Hex.decode("002a") + randomData + Hex.decode("01 02 0102") + Hex.decode("fe47010000 00") to Shutdown(channelId, randomData.toByteVector(), TlvStream(setOf(ShutdownTlv.ChannelData(EncryptedChannelData.empty)), setOf(GenericTlv(1, ByteVector("0102"))))),
            Hex.decode("0026") + channelId.toByteArray() + Hex.decode("002a") + randomData + Hex.decode("fe47010000 07 cccccccccccccc") to Shutdown(channelId, randomData.toByteVector()).withChannelData(ByteVector("cccccccccccccc")),
            Hex.decode("0026") + channelId.toByteArray() + Hex.decode("002a") + randomData + Hex.decode("01 02 0102") + Hex.decode("fe47010000 07 cccccccccccccc") to Shutdown(channelId, randomData.toByteVector(), TlvStream(setOf(ShutdownTlv.ChannelData(EncryptedChannelData(ByteVector("cccccccccccccc")))), setOf(GenericTlv(1, ByteVector("0102"))))),
            // closing_signed
            Hex.decode("0027") + channelId.toByteArray() + Hex.decode("00000000075bcd15") + signature.toByteArray() to ClosingSigned(channelId, 123456789.sat, signature),
            Hex.decode("0027") + channelId.toByteArray() + Hex.decode("00000000075bcd15") + signature.toByteArray() + Hex.decode("03 02 0102") to ClosingSigned(channelId, 123456789.sat, signature, TlvStream(setOf(), setOf(GenericTlv(3, ByteVector("0102"))))),
            Hex.decode("0027") + channelId.toByteArray() + Hex.decode("00000000075bcd15") + signature.toByteArray() + Hex.decode("fe47010000 00") to ClosingSigned(channelId, 123456789.sat, signature, TlvStream(ClosingSignedTlv.ChannelData(EncryptedChannelData.empty))),
            Hex.decode("0027") + channelId.toByteArray() + Hex.decode("00000000075bcd15") + signature.toByteArray() + Hex.decode("03 02 0102") + Hex.decode("fe47010000 00") to ClosingSigned(channelId, 123456789.sat, signature, TlvStream(setOf(ClosingSignedTlv.ChannelData(EncryptedChannelData.empty)), setOf(GenericTlv(3, ByteVector("0102"))))),
            Hex.decode("0027") + channelId.toByteArray() + Hex.decode("00000000075bcd15") + signature.toByteArray() + Hex.decode("fe47010000 07 cccccccccccccc") to ClosingSigned(channelId, 123456789.sat, signature).withChannelData(ByteVector("cccccccccccccc")),
            Hex.decode("0027") + channelId.toByteArray() + Hex.decode("00000000075bcd15") + signature.toByteArray() + Hex.decode("03 02 0102") + Hex.decode("fe47010000 07 cccccccccccccc") to ClosingSigned(channelId, 123456789.sat, signature, TlvStream(setOf(ClosingSignedTlv.ChannelData(EncryptedChannelData(ByteVector("cccccccccccccc")))), setOf(GenericTlv(3, ByteVector("0102")))))
        )
        // @formatter:on

        refs.forEach {
            val decoded = LightningMessage.decode(it.key)
            assertEquals(it.value, decoded)
            val encoded = LightningMessage.encode(it.value)
            assertArrayEquals(it.key, encoded)
        }
    }

    @Test
    fun `skip backup channel data when too large`() {
        // We omit the channel backup when it risks overflowing the lightning message.
        val belowLimit = EncryptedChannelData(ByteVector(ByteArray(59500) { 42 }))
        val aboveLimit = EncryptedChannelData(ByteVector(ByteArray(60000) { 42 }))
        val messages = listOf(
            ChannelReestablish(randomBytes32(), 0, 0, randomKey(), randomKey().publicKey()),
            TxSignatures(randomBytes32(), TxId(randomBytes32()), listOf()),
            CommitSig(randomBytes32(), randomBytes64(), listOf()),
            RevokeAndAck(randomBytes32(), randomKey(), randomKey().publicKey()),
            Shutdown(randomBytes32(), ByteVector("deadbeef")),
            ClosingSigned(randomBytes32(), 0.sat, randomBytes64()),
        )
        messages.forEach {
            assertEquals(it.withChannelData(belowLimit).channelData, belowLimit)
            assertTrue(it.withChannelData(aboveLimit).channelData.isEmpty())
        }
    }

    @Test
    fun `skip backup channel data when message is too large`() {
        val channelData = EncryptedChannelData(ByteVector(ByteArray(59500) { 42 }))
        val smallCommit = CommitSig(randomBytes32(), randomBytes64(), listOf())
        assertEquals(smallCommit.withChannelData(channelData).channelData, channelData)
        val largeCommit = CommitSig(randomBytes32(), randomBytes64(), List(50) { randomBytes64() })
        assertTrue(largeCommit.withChannelData(channelData).channelData.isEmpty())
    }

    @Test
    fun `encode - decode pay-to-open messages`() {
        val testCases = listOf(
            PayToOpenRequest(BlockHash(randomBytes32()), 10_000.sat, 5_000.msat, 100.msat, 10.sat, randomBytes32(), 100, OnionRoutingPacket(0, randomKey().publicKey().value, ByteVector("0102030405"), randomBytes32())),
            PayToOpenResponse(BlockHash(randomBytes32()), randomBytes32(), PayToOpenResponse.Result.Success(randomBytes32())),
            PayToOpenResponse(BlockHash(randomBytes32()), randomBytes32(), PayToOpenResponse.Result.Failure(null)),
            PayToOpenResponse(BlockHash(randomBytes32()), randomBytes32(), PayToOpenResponse.Result.Failure(ByteVector("deadbeef"))),
        )

        testCases.forEach {
            val encoded = LightningMessage.encode(it)
            val decoded = LightningMessage.decode(encoded)
            assertNotNull(decoded)
            assertEquals(it, decoded)
        }
    }

    @Test
    fun `encode - decode please-open-channel messages`() {
        val testCases = listOf(
            // @formatter:off
            PleaseOpenChannel(Block.RegtestGenesisBlock.hash, ByteVector32("2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db25"), 123_456.sat, 2, 522_000) to Hex.decode("8ca1 06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f 2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db25 000000000001e240 0002 0007f710"),
            PleaseOpenChannel(Block.RegtestGenesisBlock.hash, ByteVector32("2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db25"), 123_456.sat, 2, 522_000, TlvStream(PleaseOpenChannelTlv.GrandParents(listOf()))) to Hex.decode("8ca1 06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f 2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db25 000000000001e240 0002 0007f710 fd023100"),
            PleaseOpenChannel(Block.RegtestGenesisBlock.hash, ByteVector32("2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db25"), 123_456.sat, 2, 522_000, TlvStream(PleaseOpenChannelTlv.GrandParents(listOf(OutPoint(TxHash("d0556c8cc004933f40b9ca5e87e18cb549298fb02d7e64b0c0ee95303485145a"), 5))))) to Hex.decode("8ca1 06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f 2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db25 000000000001e240 0002 0007f710 fd023128d0556c8cc004933f40b9ca5e87e18cb549298fb02d7e64b0c0ee95303485145a0000000000000005"),
            PleaseOpenChannel(Block.RegtestGenesisBlock.hash, ByteVector32("2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db25"), 123_456.sat, 2, 522_000, TlvStream(PleaseOpenChannelTlv.GrandParents(listOf(OutPoint(TxHash("572b045edb5f0e3ff667e914e368273b11a874fae56a735b332b54048b7978c2"), 0), OutPoint(TxHash("cd6ac843158a1c317021de1323cdd2071f0f59744f79b298a8a45fda2dd7989f"), 1105))))) to Hex.decode("8ca1 06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f 2dadacd65b585e4061421b5265ff543e2a7bdc4d4a7fea932727426bdc53db25 000000000001e240 0002 0007f710 fd023150572b045edb5f0e3ff667e914e368273b11a874fae56a735b332b54048b7978c20000000000000000cd6ac843158a1c317021de1323cdd2071f0f59744f79b298a8a45fda2dd7989f0000000000000451"),
            // @formatter:on
        )

        testCases.forEach {
            val decoded = LightningMessage.decode(it.second)
            assertNotNull(decoded)
            assertEquals(it.first, decoded)
            val encoded = LightningMessage.encode(decoded)
            assertArrayEquals(it.second, encoded)
        }
    }

    @Test
    fun `encode - decode phoenix-android-legacy-info messages`() {
        val testCases = listOf(
            Pair(PhoenixAndroidLegacyInfo(hasChannels = true), Hex.decode("88cfff")),
            Pair(PhoenixAndroidLegacyInfo(hasChannels = false), Hex.decode("88cf00")),
        )
        testCases.forEach {
            val decoded = LightningMessage.decode(it.second)
            assertNotNull(decoded)
            assertEquals(it.first, decoded)
            val encoded = LightningMessage.encode(decoded)
            assertArrayEquals(it.second, encoded)
        }
    }

    @Test
    fun `validate liquidity ads lease`() {
        // The following lease has been signed by eclair.
        val channelId = randomBytes32()
        val remoteNodeId = PublicKey.fromHex("024dd1d24f950df788c124fe855d5a48c632d5fb6e59cf95f7ea6bee2ad47e5bc8")
        val fundingScript = ByteVector.fromHex("00202395c9c52c02ca069f1d56a3c6124bf8b152a617328c76e6b31f83ace370c2ff")
        val remoteWillFund = ChannelTlv.WillFund(
            sig = ByteVector64("a1b9850389d21b49e074f183e6e1e2d0416e47b4c031843f4cf6f02f68e44ebd5f6ad1baee0b49098c517ac1f04fee6c58335e64ed45f5b0e4ce4b8546cbba09"),
            fundingWeight = 500,
            leaseFeeProportional = 100,
            leaseFeeBase = 10.sat,
            maxRelayFeeProportional = 250,
            maxRelayFeeBase = 2000.msat,
        )
        assertEquals(remoteWillFund.leaseRate(0).fees(FeeratePerKw(FeeratePerByte(5.sat)), 500_000.sat, 500_000.sat).total, 5635.sat)
        assertEquals(remoteWillFund.leaseRate(0).fees(FeeratePerKw(FeeratePerByte(5.sat)), 500_000.sat, 600_000.sat).total, 5635.sat)
        assertEquals(remoteWillFund.leaseRate(0).fees(FeeratePerKw(FeeratePerByte(5.sat)), 500_000.sat, 400_000.sat).total, 4635.sat)

        data class TestCase(val remoteFundingAmount: Satoshi, val feerate: FeeratePerKw, val willFund: ChannelTlv.WillFund?, val failure: ChannelException?)

        val testCases = listOf(
            TestCase(500_000.sat, FeeratePerKw(FeeratePerByte(5.sat)), remoteWillFund, failure = null),
            TestCase(500_000.sat, FeeratePerKw(FeeratePerByte(5.sat)), willFund = null, failure = MissingLiquidityAds(channelId)),
            TestCase(500_000.sat, FeeratePerKw(FeeratePerByte(5.sat)), remoteWillFund.copy(sig = randomBytes64()), failure = InvalidLiquidityAdsSig(channelId)),
            TestCase(0.sat, FeeratePerKw(FeeratePerByte(5.sat)), remoteWillFund, failure = InvalidLiquidityAdsAmount(channelId, 0.sat, 500_000.sat)),
        )
        testCases.forEach {
            val request = LiquidityAds.RequestRemoteFunding(500_000.sat, leaseStart = 820_000, rate = remoteWillFund.leaseRate(leaseDuration = 0))
            val result = request.validateLease(remoteNodeId, channelId, fundingScript, it.remoteFundingAmount, it.feerate, it.willFund)
            assertEquals(result.left, it.failure)
        }

    }
}