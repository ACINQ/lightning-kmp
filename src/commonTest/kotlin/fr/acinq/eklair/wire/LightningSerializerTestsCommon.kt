package fr.acinq.eklair.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.eklair.CltvExpiryDelta
import fr.acinq.secp256k1.Hex
import fr.acinq.eklair.utils.msat
import fr.acinq.eklair.utils.sat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.json.content
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LightningSerializerTestsCommon {

    fun point(fill: Byte) = PrivateKey(ByteArray(32) { fill }).publicKey()

    fun publicKey(fill: Byte) = point(fill)

    @Test
    fun `bigsize serialization`() {
        val raw = """[
    {
        "name": "zero",
        "value": 0,
        "bytes": "00"
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

        val json = Json(JsonConfiguration.Default)
        val items = json.parseJson(raw)
        items.jsonArray.forEach {
            val name = it.jsonObject["name"]?.content
            val bytes = it.jsonObject["bytes"]?.content
            val value = it.jsonObject["value"]?.primitive?.content
            println(name)
            println(value)
            println(bytes)
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
        val defaultOpen = OpenChannel(ByteVector32.Zeroes, ByteVector32.Zeroes, 1.sat, 1.msat, 1.sat, 1UL, 1.sat, 1.msat, 1, CltvExpiryDelta(1), 1, publicKey(1), point(2), point(3), point(4), point(5), point(6), 0.toByte())
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
            defaultEncoded + ByteVector("0000") to defaultOpen.copy(tlvStream = TlvStream(listOf(ChannelTlv.Companion.UpfrontShutdownScript(ByteVector.empty)))),
            // non-empty upfront_shutdown_script
            defaultEncoded + ByteVector("0004 01abcdef") to defaultOpen.copy(tlvStream = TlvStream(listOf(ChannelTlv.Companion.UpfrontShutdownScript(ByteVector("01abcdef"))))),
            // missing upfront_shutdown_script + unknown odd tlv records
            defaultEncoded + ByteVector("0302002a 050102") to defaultOpen.copy(tlvStream = TlvStream(listOf(), listOf(GenericTlv(3UL, ByteVector("002a")), GenericTlv(5UL, ByteVector("02"))))),
            // empty upfront_shutdown_script + unknown odd tlv records
            defaultEncoded + ByteVector("0000 0302002a 050102") to defaultOpen.copy(
                tlvStream = TlvStream(
                    listOf(ChannelTlv.Companion.UpfrontShutdownScript(ByteVector.empty)),
                    listOf(GenericTlv(3UL, ByteVector("002a")), GenericTlv(5UL, ByteVector("02")))
                )
            ),
            // non-empty upfront_shutdown_script + unknown odd tlv records
            defaultEncoded + ByteVector("0002 1234 0303010203") to defaultOpen.copy(tlvStream = TlvStream(listOf(ChannelTlv.Companion.UpfrontShutdownScript(ByteVector("1234"))), listOf(GenericTlv(3UL, ByteVector("010203")))))
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
        val defaultAccept = AcceptChannel(ByteVector32.Zeroes, 1.sat, 1UL, 1.sat, 1.msat, 1, CltvExpiryDelta(1), 1, publicKey(1), point(2), point(3), point(4), point(5), point(6))
        // Legacy encoding that omits the upfront_shutdown_script and trailing tlv stream.
        // To allow extending all messages with TLV streams, the upfront_shutdown_script was moved to a TLV stream extension
        // in https://github.com/lightningnetwork/lightning-rfc/pull/714 and made mandatory when including a TLV stream.
        // We don't make it mandatory at the codec level: it's the job of the actor creating the message to include it.
        val defaultEncoded = ByteVector("000000000000000000000000000000000000000000000000000000000000000000000000000000010000000000000001000000000000000100000000000000010000000100010001031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d076602531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe33703462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b0362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f703f006a18d5653c4edf5391ff23a61f03ff83d237e880ee61187fa9f379a028e0a")
        val testCases = mapOf(
            defaultEncoded to defaultAccept, // legacy encoding without upfront_shutdown_script
        defaultEncoded + ByteVector("0000") to defaultAccept.copy(tlvStream = TlvStream(listOf(ChannelTlv.Companion.UpfrontShutdownScript(ByteVector.empty)))), // empty upfront_shutdown_script
        defaultEncoded + ByteVector("0004 01abcdef") to defaultAccept.copy(tlvStream = TlvStream(listOf(ChannelTlv.Companion.UpfrontShutdownScript(ByteVector("01abcdef"))))), // non-empty upfront_shutdown_script
        defaultEncoded + ByteVector("0000 0102002a 030102") to defaultAccept.copy(tlvStream = TlvStream(listOf(ChannelTlv.Companion.UpfrontShutdownScript(ByteVector.empty)), listOf(GenericTlv(1UL, ByteVector("002a")), GenericTlv(3UL, ByteVector("02"))))), // empty upfront_shutdown_script + unknown odd tlv records
        defaultEncoded + ByteVector("0002 1234 0303010203") to defaultAccept.copy(tlvStream = TlvStream(listOf(ChannelTlv.Companion.UpfrontShutdownScript(ByteVector("1234"))), listOf(GenericTlv(3UL, ByteVector("010203"))))), // non-empty upfront_shutdown_script + unknown odd tlv records
        defaultEncoded + ByteVector("0303010203 05020123") to defaultAccept.copy(tlvStream = TlvStream(listOf(), listOf(GenericTlv(3UL, ByteVector("010203")), GenericTlv(5UL, ByteVector("0123"))))) // no upfront_shutdown_script + unknown odd tlv records
        )

        testCases.forEach {
            val decoded = AcceptChannel.read(it.key.toByteArray())
            val expected = it.value
            assertEquals(expected, decoded)
            val reEncoded = AcceptChannel.write(decoded)
            assertEquals(it.key, ByteVector(reEncoded))
        }

    }

}