package fr.acinq.eclair.wire

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.Eclair
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.crypto.assertArrayEquals
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.sat
import fr.acinq.eclair.utils.toByteVector
import fr.acinq.secp256k1.Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

@OptIn(ExperimentalUnsignedTypes::class)
class LightningSerializerTestsCommon {

    fun point(fill: Byte) = PrivateKey(ByteArray(32) { fill }).publicKey()

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
            LightningSerializer.writeU64(it.key.toLong(), out)
            assertArrayEquals(it.value, out.toByteArray())
            val decoded = LightningSerializer.u64(ByteArrayInput(it.value))
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
                assertFails(name) { LightningSerializer.bigSize(ByteArrayInput(bytes)) }
            } else {
                assertEquals(value, LightningSerializer.bigSize(ByteArrayInput(bytes)).toULong(), name)
                val out = ByteArrayOutput()
                LightningSerializer.writeBigSize(value.toLong(), out)
                assertArrayEquals(bytes, out.toByteArray())
            }
        }
    }

    @Test
    fun `encode and decode init message`() {
        data class TestCase(val encoded: ByteVector, val rawFeatures: ByteVector, val networks: List<ByteVector32>, val valid: Boolean, val reEncoded: ByteVector? = null)

        val chainHash1 = ByteVector32.fromValidHex("0101010101010101010101010101010101010101010101010101010101010101")
        val chainHash2 = ByteVector32.fromValidHex("0202020202020202020202020202020202020202020202020202020202020202")

        val testCases = listOf(
            TestCase(ByteVector("0000 0000"), ByteVector(""), listOf(), true), // no features
            TestCase(ByteVector("0000 0002088a"), ByteVector("088a"), listOf(), true), // no global features
            TestCase(ByteVector("00020200 0000"), ByteVector("0200"), listOf(), true, ByteVector("0000 00020200")), // no local features
            TestCase(ByteVector("00020200 0002088a"), ByteVector("0a8a"), listOf(), true, ByteVector("0000 00020a8a")), // local and global - no conflict - same size
            TestCase(ByteVector("00020200 0003020002"), ByteVector("020202"), listOf(), true, ByteVector("0000 0003020202")), // local and global - no conflict - different sizes
            TestCase(ByteVector("00020a02 0002088a"), ByteVector("0a8a"), listOf(), true, ByteVector("0000 00020a8a")), // local and global - conflict - same size
            TestCase(ByteVector("00022200 000302aaa2"), ByteVector("02aaa2"), listOf(), true, ByteVector("0000 000302aaa2")), // local and global - conflict - different sizes
            TestCase(ByteVector("0000 0002088a 03012a05022aa2"), ByteVector("088a"), listOf(), true), // unknown odd records
            TestCase(ByteVector("0000 0002088a 03012a04022aa2"), ByteVector("088a"), listOf(), false), // unknown even records
            TestCase(ByteVector("0000 0002088a 0120010101010101010101010101010101010101010101010101010101010101"), ByteVector("088a"), listOf(), false), // invalid tlv stream
            TestCase(ByteVector("0000 0002088a 01200101010101010101010101010101010101010101010101010101010101010101"), ByteVector("088a"), listOf(chainHash1), true), // single network
            TestCase(
                ByteVector("0000 0002088a 014001010101010101010101010101010101010101010101010101010101010101010202020202020202020202020202020202020202020202020202020202020202"),
                ByteVector("088a"),
                listOf(chainHash1, chainHash2),
                true
            ), // multiple networks
            TestCase(ByteVector("0000 0002088a 0120010101010101010101010101010101010101010101010101010101010101010103012a"), ByteVector("088a"), listOf(chainHash1), true), // network and unknown odd records
            TestCase(ByteVector("0000 0002088a 0120010101010101010101010101010101010101010101010101010101010101010102012a"), ByteVector("088a"), listOf(), false) // network and unknown even records
        )

        for (testCase in testCases) {
            val result = kotlin.runCatching {
                val init = Init.read(testCase.encoded.toByteArray())
                assertEquals(testCase.rawFeatures, init.features)
                assertEquals(testCase.networks, init.networks)
                val encoded = Init.write(init)
                assertEquals(testCase.reEncoded ?: testCase.encoded, ByteVector(encoded), testCase.toString())
            }
            assertEquals(result.isFailure, !testCase.valid, testCase.toString())
        }
    }

    @Test
    fun `encode - decode open_channel`() {
        val defaultOpen = OpenChannel(ByteVector32.Zeroes, ByteVector32.Zeroes, 1.sat, 1.msat, 1.sat, 1L, 1.sat, 1.msat, 1, CltvExpiryDelta(1), 1, publicKey(1), point(2), point(3), point(4), point(5), point(6), 0.toByte())
        // Legacy encoding that omits the upfront_shutdown_script and trailing tlv stream.
        // To allow extending all messages with TLV streams, the upfront_shutdown_script was moved to a TLV stream extension
        // in https://github.com/lightningnetwork/lightning-rfc/pull/714 and made mandatory when including a TLV stream.
        // We don't make it mandatory at the codec level: it's the job of the actor creating the message to include it.
        val defaultEncoded =
            ByteVector("000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001000000000000000100000000000000010000000000000001000000000000000100000000000000010000000100010001031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d076602531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe33703462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b0362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f703f006a18d5653c4edf5391ff23a61f03ff83d237e880ee61187fa9f379a028e0a00")

        val testCases = mapOf(
            // legacy encoding without upfront_shutdown_script
            defaultEncoded to defaultOpen,
            // empty upfront_shutdown_script
            defaultEncoded + ByteVector("0000") to defaultOpen.copy(tlvStream = TlvStream(listOf(ChannelTlv.UpfrontShutdownScript(ByteVector.empty)))),
            // non-empty upfront_shutdown_script
            defaultEncoded + ByteVector("0004 01abcdef") to defaultOpen.copy(tlvStream = TlvStream(listOf(ChannelTlv.UpfrontShutdownScript(ByteVector("01abcdef"))))),
            // missing upfront_shutdown_script + unknown odd tlv records
            defaultEncoded + ByteVector("0302002a 050102") to defaultOpen.copy(tlvStream = TlvStream(listOf(), listOf(GenericTlv(3L, ByteVector("002a")), GenericTlv(5L, ByteVector("02"))))),
            // empty upfront_shutdown_script + unknown odd tlv records
            defaultEncoded + ByteVector("0000 0302002a 050102") to defaultOpen.copy(
                tlvStream = TlvStream(
                    listOf(ChannelTlv.UpfrontShutdownScript(ByteVector.empty)),
                    listOf(GenericTlv(3L, ByteVector("002a")), GenericTlv(5L, ByteVector("02")))
                )
            ),
            // non-empty upfront_shutdown_script + unknown odd tlv records
            defaultEncoded + ByteVector("0002 1234 0303010203") to defaultOpen.copy(tlvStream = TlvStream(listOf(ChannelTlv.UpfrontShutdownScript(ByteVector("1234"))), listOf(GenericTlv(3L, ByteVector("010203")))))
        )

        testCases.forEach {
            val decoded = OpenChannel.read(it.key.toByteArray())
            val expected = it.value
            assertEquals(expected, decoded)
            val reEncoded = OpenChannel.write(decoded)
            assertEquals(it.key, ByteVector(reEncoded))
        }
    }

    @Test
    fun `decode invalid open_channel`() {
        val defaultEncoded = ByteVector("000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001000000000000000100000000000000010000000000000001000000000000000100000000000000010000000100010001031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d076602531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe33703462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b0362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f703f006a18d5653c4edf5391ff23a61f03ff83d237e880ee61187fa9f379a028e0a00")
        val testCases = listOf(
            defaultEncoded + ByteVector("00"), // truncated length
            defaultEncoded + ByteVector("01"), // truncated length
            defaultEncoded + ByteVector("0004 123456"), // truncated upfront_shutdown_script
            defaultEncoded + ByteVector("0000 02012a"), // invalid tlv stream (unknown even record)
            defaultEncoded + ByteVector("0000 01012a 030201"), // invalid tlv stream (truncated)
            defaultEncoded + ByteVector("02012a"), // invalid tlv stream (unknown even record)
            defaultEncoded + ByteVector("01012a 030201") // invalid tlv stream (truncated)
        )
        testCases.forEach {
            assertFails { OpenChannel.read(it.toByteArray()) }
        }
    }

    @Test
    fun `encode - decode accept_channel`() {
        val defaultAccept = AcceptChannel(ByteVector32.Zeroes, 1.sat, 1L, 1.sat, 1.msat, 1, CltvExpiryDelta(1), 1, publicKey(1), point(2), point(3), point(4), point(5), point(6))
        // Legacy encoding that omits the upfront_shutdown_script and trailing tlv stream.
        // To allow extending all messages with TLV streams, the upfront_shutdown_script was moved to a TLV stream extension
        // in https://github.com/lightningnetwork/lightning-rfc/pull/714 and made mandatory when including a TLV stream.
        // We don't make it mandatory at the codec level: it's the job of the actor creating the message to include it.
        val defaultEncoded = ByteVector("000000000000000000000000000000000000000000000000000000000000000000000000000000010000000000000001000000000000000100000000000000010000000100010001031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d076602531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe33703462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b0362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f703f006a18d5653c4edf5391ff23a61f03ff83d237e880ee61187fa9f379a028e0a")
        val testCases = mapOf(
            defaultEncoded to defaultAccept, // legacy encoding without upfront_shutdown_script
            defaultEncoded + ByteVector("0000") to defaultAccept.copy(tlvStream = TlvStream(listOf(ChannelTlv.UpfrontShutdownScript(ByteVector.empty)))), // empty upfront_shutdown_script
            defaultEncoded + ByteVector("0004 01abcdef") to defaultAccept.copy(tlvStream = TlvStream(listOf(ChannelTlv.UpfrontShutdownScript(ByteVector("01abcdef"))))), // non-empty upfront_shutdown_script
            defaultEncoded + ByteVector("0000 0102002a 030102") to defaultAccept.copy(tlvStream = TlvStream(listOf(ChannelTlv.UpfrontShutdownScript(ByteVector.empty)), listOf(GenericTlv(1L, ByteVector("002a")), GenericTlv(3L, ByteVector("02"))))), // empty upfront_shutdown_script + unknown odd tlv records
            defaultEncoded + ByteVector("0002 1234 0303010203") to defaultAccept.copy(tlvStream = TlvStream(listOf(ChannelTlv.UpfrontShutdownScript(ByteVector("1234"))), listOf(GenericTlv(3L, ByteVector("010203"))))), // non-empty upfront_shutdown_script + unknown odd tlv records
            defaultEncoded + ByteVector("0303010203 05020123") to defaultAccept.copy(tlvStream = TlvStream(listOf(), listOf(GenericTlv(3L, ByteVector("010203")), GenericTlv(5L, ByteVector("0123"))))) // no upfront_shutdown_script + unknown odd tlv records
        )

        testCases.forEach {
            val decoded = AcceptChannel.read(it.key.toByteArray())
            val expected = it.value
            assertEquals(expected, decoded)
            val reEncoded = AcceptChannel.write(decoded)
            assertEquals(it.key, ByteVector(reEncoded))
        }
    }

    @Test
    fun `encode - decode channel_reestablish`() {
        val channelReestablish = ChannelReestablish(
            ByteVector32("c11b8fbd682b3c6ee11f9d7268e22bb5887cd4d3bf3338bfcc340583f685733c"),
            242842,
            42,
            PrivateKey.fromHex("34f159d37cf7b5de52ec0adc3968886232f90d272e8c82e8b6f7fcb7e57c4b55"),
            PublicKey.fromHex("02bf050efff417efc09eb211ca9e4e845920e2503740800e88505b25e6f0e1e867")
        )
        val encoded = LightningMessage.encode(channelReestablish)!!
        val expected = "0088c11b8fbd682b3c6ee11f9d7268e22bb5887cd4d3bf3338bfcc340583f685733c000000000003b49a000000000000002a34f159d37cf7b5de52ec0adc3968886232f90d272e8c82e8b6f7fcb7e57c4b5502bf050efff417efc09eb211ca9e4e845920e2503740800e88505b25e6f0e1e867"
        assertEquals(expected, Hex.encode(encoded))
    }

    @Test
    fun `encode - decode channel_update`() {
        val channelUpdate = ChannelUpdate(
            Eclair.randomBytes64(),
            Eclair.randomBytes32(),
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
        val encoded = LightningMessage.encode(channelUpdate)!!
        val decoded = LightningMessage.decode(encoded)
        assertEquals(channelUpdate, decoded)
    }

    @Test
    fun `decode channel_update with htlc_maximum_msat`() {
        // this was generated by c-lightning
        val encoded = ByteVector("010258fff7d0e987e2cdd560e3bb5a046b4efe7b26c969c2f51da1dceec7bcb8ae1b634790503d5290c1a6c51d681cf8f4211d27ed33a257dcc1102862571bf1792306226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f0005a100000200005bc75919010100060000000000000001000000010000000a000000003a699d00")
        val decoded = LightningMessage.decode(encoded.toByteArray())!!
        val expected = ChannelUpdate(
            ByteVector64("58fff7d0e987e2cdd560e3bb5a046b4efe7b26c969c2f51da1dceec7bcb8ae1b634790503d5290c1a6c51d681cf8f4211d27ed33a257dcc1102862571bf17923"),
            ByteVector32("06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"),
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
        val reEncoded = LightningMessage.encode(decoded)!!.toByteVector()
        assertEquals(encoded, reEncoded)
    }

    @Test
    fun `encode - decode channel_update with unknown trailing bytes`() {
        val channelUpdate = ChannelUpdate(
            Eclair.randomBytes64(),
            Eclair.randomBytes32(),
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
        val encoded = LightningMessage.encode(channelUpdate)!!
        val decoded = LightningMessage.decode(encoded)
        assertEquals(channelUpdate, decoded)
    }
}