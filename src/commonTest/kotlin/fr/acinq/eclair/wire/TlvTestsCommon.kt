package fr.acinq.eclair.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.crypto.assertArrayEquals
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

@OptIn(ExperimentalUnsignedTypes::class)
class TlvTestsCommon : EclairTestSuite() {

    @Test
    fun `encode - decode truncated uint16`() {
        val testCases = mapOf(
            "" to 0,
            "01" to 1,
            "2a" to 42,
            "ff" to 255,
            "0100" to 256,
            "0231" to 561,
            "ffff" to 65535
        )

        testCases.forEach {
            val decoded = LightningSerializer.tu16(ByteArrayInput(Hex.decode(it.key)))
            assertEquals(it.value, decoded)
            val out = ByteArrayOutput()
            LightningSerializer.writeTU16(it.value, out)
            assertEquals(it.key, Hex.encode(out.toByteArray()))
        }
    }

    @Test
    fun `encode - decode truncated u32`() {
        val testCases = mapOf(
            "" to 0,
            "01" to 1,
            "2a" to 42,
            "ff" to 255,
            "0100" to 256,
            "0231" to 561,
            "ffff" to 65535,
            "010000" to 65536,
            "ffffff" to 16777215,
            "01000000" to 16777216,
            "01020304" to 16909060,
            // TODO: reactivate once we support unsigned
            // "ffffffff" to 4294967295
        )

        testCases.forEach {
            val decoded = LightningSerializer.tu32(ByteArrayInput(Hex.decode(it.key)))
            assertEquals(it.value, decoded)
            val out = ByteArrayOutput()
            LightningSerializer.writeTU32(it.value, out)
            assertEquals(it.key, Hex.encode(out.toByteArray()))
        }
    }

    @Test
    fun `encode - decode truncated u64`() {
        val testCases = mapOf(
            "" to 0,
            "01" to 1,
            "2a" to 42,
            "ff" to 255,
            "0100" to 256,
            "0231" to 561,
            "ffff" to 65535,
            "010000" to 65536,
            "ffffff" to 16777215,
            "01000000" to 16777216,
            "01020304" to 16909060,
            "ffffffff" to 4294967295L,
            "0100000000" to 4294967296L,
            "0102030405" to 4328719365L,
            "ffffffffff" to 1099511627775L,
            "010000000000" to 1099511627776L,
            "010203040506" to 1108152157446L,
            "ffffffffffff" to 281474976710655L,
            "01000000000000" to 281474976710656L,
            "01020304050607" to 283686952306183L,
            "ffffffffffffff" to 72057594037927935L,
            "0100000000000000" to 72057594037927936L,
            "0102030405060708" to 72623859790382856L,
            // TODO: reactivate once we support unsigned
            // "ffffffffffffffff" to 18446744073709551615UL
        )

        testCases.forEach {
            val decoded = LightningSerializer.tu64(ByteArrayInput(Hex.decode(it.key)))
            assertEquals(it.value, decoded)
            val out = ByteArrayOutput()
            LightningSerializer.writeTU64(it.value, out)
            assertEquals(it.key, Hex.encode(out.toByteArray()))
        }
    }

    @Test
    fun `decode invalid truncated integers`() {
        val testCases16 = listOf(
            "00", // not minimal
            "0001", // not minimal
            "ffffff", // length too big
        )
        val testCases32 = listOf(
            "00", // not minimal
            "0001", // not minimal
            "000100", // not minimal
            "00010000", // not minimal
            "ffffffffff", // length too big
        )
        val testCases64 = listOf(
            "00", // not minimal
            "0001", // not minimal
            "000100", // not minimal
            "00010000", // not minimal
            "0001000000", // not minimal
            "000100000000", // not minimal
            "00010000000000", // not minimal
            "0001000000000000", // not minimal
            "ffffffffffffffffff" // length too big
        )

        testCases16.forEach { assertFails { LightningSerializer.tu16(ByteArrayInput(Hex.decode(it))) } }
        testCases32.forEach { assertFails { LightningSerializer.tu32(ByteArrayInput(Hex.decode(it))) } }
        testCases64.forEach { assertFails { LightningSerializer.tu64(ByteArrayInput(Hex.decode(it))) } }
    }

    @Test
    fun `encode - decode tlv stream`() {
        val testCases = listOf<Pair<ByteArray, TlvStream<TestTlv>>>(
            Pair(Hex.decode(""), TlvStream.empty()),
            Pair(Hex.decode("21 00"), TlvStream(listOf(), listOf(GenericTlv(33, ByteVector.empty)))),
            Pair(Hex.decode("fd0201 00"), TlvStream(listOf(), listOf(GenericTlv(513, ByteVector.empty)))),
            Pair(Hex.decode("fd00fd 00"), TlvStream(listOf(), listOf(GenericTlv(253, ByteVector.empty)))),
            Pair(Hex.decode("fd00ff 00"), TlvStream(listOf(), listOf(GenericTlv(255, ByteVector.empty)))),
            Pair(Hex.decode("fe02000001 00"), TlvStream(listOf(), listOf(GenericTlv(33554433, ByteVector.empty)))),
            Pair(Hex.decode("ff0200000000000001 00"), TlvStream(listOf(), listOf(GenericTlv(144115188075855873L, ByteVector.empty)))),
            Pair(Hex.decode("01 00"), TlvStream(listOf(TestTlv.TestType1(0)))),
            Pair(Hex.decode("01 01 01"), TlvStream(listOf(TestTlv.TestType1(1)))),
            Pair(Hex.decode("01 01 2a"), TlvStream(listOf(TestTlv.TestType1(42)))),
            Pair(Hex.decode("01 02 0100"), TlvStream(listOf(TestTlv.TestType1(256)))),
            Pair(Hex.decode("01 03 010000"), TlvStream(listOf(TestTlv.TestType1(65536)))),
            Pair(Hex.decode("01 04 01000000"), TlvStream(listOf(TestTlv.TestType1(16777216)))),
            Pair(Hex.decode("01 05 0100000000"), TlvStream(listOf(TestTlv.TestType1(4294967296)))),
            Pair(Hex.decode("01 06 010000000000"), TlvStream(listOf(TestTlv.TestType1(1099511627776)))),
            Pair(Hex.decode("01 07 01000000000000"), TlvStream(listOf(TestTlv.TestType1(281474976710656)))),
            Pair(Hex.decode("01 08 0100000000000000"), TlvStream(listOf(TestTlv.TestType1(72057594037927936)))),
            Pair(Hex.decode("02 08 0000000000000226"), TlvStream(listOf(TestTlv.TestType2(ShortChannelId(550))))),
            Pair(
                Hex.decode("03 31 023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb 0000000000000231 0000000000000451"),
                TlvStream(listOf(TestTlv.TestType3(PublicKey(ByteVector("023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb")), 561, 1105)))
            ),
            Pair(Hex.decode("fd00fe 02 0226"), TlvStream(listOf(TestTlv.TestType254(550)))),
            Pair(
                Hex.decode("01020231 02080000000000000451 033102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661900000000000002310000000000000451"),
                TlvStream(listOf(TestTlv.TestType1(561), TestTlv.TestType2(ShortChannelId(1105)), TestTlv.TestType3(PublicKey(ByteVector("02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619")), 561, 1105)))
            ),
            Pair(Hex.decode("01020231 0b020451 fd00fe02002a"), TlvStream(listOf(TestTlv.TestType1(561), TestTlv.TestType254(42)), listOf(GenericTlv(11, ByteVector("0451")))))
        )

        testCases.forEach {
            val decoded = testTlvStreamSerializer.read(it.first)
            assertEquals(it.second, decoded)
            val encoded = testTlvStreamSerializer.write(it.second)
            assertArrayEquals(it.first, encoded)
        }
    }

    @Test
    fun `decode invalid tlv stream`() {
        val testCases = listOf(
            // Type truncated.
            "fd",
            "fd01",
            // Not minimally encoded type.
            "fd0001 00",
            // Missing length.
            "fd0101",
            // Length truncated.
            "0f fd",
            "0f fd02",
            // Not minimally encoded length.
            "0f fd0001 00",
            "0f fe00000001 00",
            // Missing value.
            "0f fd2602",
            // Value truncated.
            "0f fd0201 000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
            // Unknown even type.
            "12 00",
            "0a 00",
            "fd0102 00",
            "01020101 0a0101",
            // Invalid TestTlv1.
            "01 01 00", // not minimally-encoded
            "01 02 0001", // not minimally-encoded
            "01 03 000100", // not minimally-encoded
            "01 04 00010000", // not minimally-encoded
            "01 05 0001000000", // not minimally-encoded
            "01 06 000100000000", // not minimally-encoded
            "01 07 00010000000000", // not minimally-encoded
            "01 08 0001000000000000", // not minimally-encoded
            // Invalid TestTlv2.
            "02 07 01010101010101", // invalid length
            "02 09 010101010101010101", // invalid length
            // Invalid TestTlv3.
            "03 21 023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb", // invalid length
            "03 29 023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb0000000000000001", // invalid length
            "03 30 023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb000000000000000100000000000001", // invalid length
            "03 32 023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb0000000000000001000000000000000001", // invalid length
            // Invalid TestTlv254.
            "fd00fe 00", // invalid length
            "fd00fe 01 01", // invalid length
            "fd00fe 03 010101", // invalid length
            // Invalid multi-record streams.
            "01012a 02", // valid tlv record followed by invalid tlv record (length missing)
            "01012a 0208", // valid tlv record followed by invalid tlv record (value missing)
            "01012a 020801010101", // valid tlv record followed by invalid tlv record (value truncated)
            "02080000000000000226 01012a", // valid tlv records but invalid ordering
            "1f00 0f012a", // valid tlv records but invalid ordering
            "02080000000000000231 02080000000000000451", // duplicate tlv type
            "01012a 0b020231 0b020451", // duplicate tlv type
            "1f00 1f012a", // duplicate tlv type
            "01012a 0a020231 0b020451" // valid tlv records but from different namespace
        )

        testCases.forEach {
            assertFails(it) { testTlvStreamSerializer.read(ByteArrayInput(Hex.decode(it))) }
        }
    }

    @Test
    fun `encode - decode length-prefixed tlv stream`() {
        val testCases = listOf(
            "41 01020231 02080000000000000451 033102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661900000000000002310000000000000451",
            "fd014d 01020231 02080000000000000451 033102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661900000000000002310000000000000451 ff6543210987654321 fd0100 10101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010010101010101"
        )

        testCases.forEach {
            val bin = Hex.decode(it)
            val decoded = lengthPrefixedTestTlvStreamSerializer.read(ByteArrayInput(bin))
            val out = ByteArrayOutput()
            lengthPrefixedTestTlvStreamSerializer.write(decoded, out)
            assertArrayEquals(bin, out.toByteArray())
        }
    }

    @Test
    fun `decode invalid length-prefixed tlv stream`() {
        val testCases = listOf(
            // Length too big.
            "42 01020231 02080000000000000451 033102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661900000000000002310000000000000451",
            // Length too short.
            "40 01020231 02080000000000000451 033102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661900000000000002310000000000000451",
            // Missing length.
            "01020231 02080000000000000451 033102eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661900000000000002310000000000000451",
            // Valid length but duplicate types.
            "14 02080000000000000231 02080000000000000451",
            // Valid length but invalid ordering.
            "0e 02080000000000000451 01020231",
            // Valid length but unknown even type.
            "02 0a 00"
        )

        testCases.forEach {
            assertFails(it) { lengthPrefixedTestTlvStreamSerializer.read(Hex.decode(it)) }
        }
    }

    @Test
    fun `encode unordered tlv stream (codec should sort appropriately)`() {
        val stream = TlvStream<TestTlv>(listOf(TestTlv.TestType254(42), TestTlv.TestType1(42)), listOf(GenericTlv(13, ByteVector("2a")), GenericTlv(11, ByteVector("2b"))))
        val out1 = ByteArrayOutput()
        testTlvStreamSerializer.write(stream, out1)
        assertArrayEquals(Hex.decode("01012a 0b012b 0d012a fd00fe02002a"), out1.toByteArray())
        val out2 = ByteArrayOutput()
        lengthPrefixedTestTlvStreamSerializer.write(stream, out2)
        assertArrayEquals(Hex.decode("0f 01012a 0b012b 0d012a fd00fe02002a"), out2.toByteArray())
    }

    @Test
    fun `encode invalid tlv stream`() {
        // Unknown even type.
        assertFails { TlvStream<TestTlv>(listOf(), listOf(GenericTlv(42, ByteVector("2a")))) }
        assertFails { TlvStream<TestTlv>(listOf(TestTlv.TestType1(561), TestTlv.TestType2(ShortChannelId(1105))), listOf(GenericTlv(42, ByteVector("2a")))) }
        // Duplicate type.
        assertFails { TlvStream<TestTlv>(listOf(TestTlv.TestType1(561), TestTlv.TestType1(1105))) }
        assertFails { TlvStream<TestTlv>(listOf(TestTlv.TestType1(561)), listOf(GenericTlv(1, ByteVector("0451")))) }
    }

    @Test
    fun `get optional TLV field`() {
        val stream = TlvStream<TestTlv>(listOf(TestTlv.TestType254(42), TestTlv.TestType1(42)), listOf(GenericTlv(13, ByteVector("2a")), GenericTlv(11, ByteVector("2b"))))
        assertEquals(TestTlv.TestType254(42), stream.get<TestTlv.TestType254>())
        assertEquals(TestTlv.TestType1(42), stream.get<TestTlv.TestType1>())
        assertNull(stream.get<TestTlv.TestType2>())
    }

    // See https://github.com/lightningnetwork/lightning-rfc/blob/master/01-messaging.md#appendix-a-type-length-value-test-vectors
    companion object {
        sealed class TestTlv : Tlv {
            data class TestType1(val v: Long) : TestTlv(), LightningSerializable<TestType1> {
                override val tag: Long get() = TestType1.tag
                override fun serializer(): LightningSerializer<TestType1> = TestType1

                companion object : LightningSerializer<TestType1>() {
                    override val tag: Long get() = 1
                    override fun read(input: Input): TestType1 = TestType1(tu64(input))
                    override fun write(message: TestType1, out: Output) = writeTU64(message.v, out)
                }
            }

            data class TestType2(val shortChannelId: ShortChannelId) : TestTlv(), LightningSerializable<TestType2> {
                override val tag: Long get() = TestType2.tag
                override fun serializer(): LightningSerializer<TestType2> = TestType2

                companion object : LightningSerializer<TestType2>() {
                    override val tag: Long get() = 2
                    override fun read(input: Input): TestType2 {
                        require(input.availableBytes == 8)
                        return TestType2(ShortChannelId(u64(input)))
                    }

                    override fun write(message: TestType2, out: Output) = writeU64(message.shortChannelId.toLong(), out)
                }
            }

            data class TestType3(val nodeId: PublicKey, val value1: Long, val value2: Long) : TestTlv(), LightningSerializable<TestType3> {
                override val tag: Long get() = TestType3.tag
                override fun serializer(): LightningSerializer<TestType3> = TestType3

                companion object : LightningSerializer<TestType3>() {
                    override val tag: Long get() = 3
                    override fun read(input: Input): TestType3 {
                        require(input.availableBytes == 49)
                        return TestType3(PublicKey(bytes(input, 33)), u64(input), u64(input))
                    }

                    override fun write(message: TestType3, out: Output) {
                        writeBytes(message.nodeId.value.toByteArray(), out)
                        writeU64(message.value1, out)
                        writeU64(message.value2, out)
                    }
                }
            }

            data class TestType254(val intValue: Int) : TestTlv(), LightningSerializable<TestType254> {
                override val tag: Long get() = TestType254.tag
                override fun serializer(): LightningSerializer<TestType254> = TestType254

                companion object : LightningSerializer<TestType254>() {
                    override val tag: Long get() = 254
                    override fun read(input: Input): TestType254 {
                        require(input.availableBytes == 2)
                        return TestType254(u16(input))
                    }

                    override fun write(message: TestType254, out: Output) {
                        writeU16(message.intValue, out)
                    }
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        private val testTlvSerializers = mapOf(
            TestTlv.TestType1.tag to TestTlv.TestType1.Companion as LightningSerializer<TestTlv>,
            TestTlv.TestType2.tag to TestTlv.TestType2.Companion as LightningSerializer<TestTlv>,
            TestTlv.TestType3.tag to TestTlv.TestType3.Companion as LightningSerializer<TestTlv>,
            TestTlv.TestType254.tag to TestTlv.TestType254.Companion as LightningSerializer<TestTlv>,
        )
        val testTlvStreamSerializer = TlvStreamSerializer(false, testTlvSerializers)
        val lengthPrefixedTestTlvStreamSerializer = TlvStreamSerializer(true, testTlvSerializers)
    }
}