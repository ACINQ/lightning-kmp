package fr.acinq.eklair.crypto.noise

import fr.acinq.eklair.Hex
import fr.acinq.eklair.crypto.assertArrayEquals
import kotlin.test.Test
import kotlin.test.assertEquals

@ExperimentalStdlibApi
class NoiseTestsCommon {
    @Test
    fun `hash tests`() {
        // see https://tools.ietf.org/html/rfc4231
        assertArrayEquals(
                SHA256HashFunctions.hmacHash(Hex.decode("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), Hex.decode("4869205468657265")),
                Hex.decode("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7")
        )
        val (a, b) = SHA256HashFunctions.hkdf(
                Hex.decode("4e6f6973655f4e4e5f32353531395f436861436861506f6c795f534841323536"),
                Hex.decode("37e0e7daacbd6bfbf669a846196fd44d1c8745d33f2be42e31d4674199ad005e"))
        assertArrayEquals(a, Hex.decode("f6f78327c10316fdad06633fb965e03182e9a8b1f755d613f7980fbb85ebf46d"))
        assertArrayEquals(b, Hex.decode("4ee4220f31dbd3c9e2367e66a87f1e98a2433e4b9fbecfd986d156dcf027b937"))
    }

    @Test
    fun `Noise_XK_secp256k1_ChaChaPoly_SHA256 test vectors`() {
        val dh = Secp256k1DHFunctions
        val prologue = "lightning".encodeToByteArray()

        val initiator_s = dh.generateKeyPair(Hex.decode("1111111111111111111111111111111111111111111111111111111111111111"))
        val initiator_e = Hex.decode("1212121212121212121212121212121212121212121212121212121212121212")

        val responder_s = dh.generateKeyPair(Hex.decode("2121212121212121212121212121212121212121212121212121212121212121"))
        val responder_e = Hex.decode("2222222222222222222222222222222222222222222222222222222222222222")

        val initiator = HandshakeState.initializeWriter(
                handshakePattern = handshakePatternXK,
                prologue = prologue,
                s = initiator_s, e = Pair(ByteArray(0), ByteArray(0)), rs = responder_s.first, re = ByteArray(0),
                dh = dh, cipher = Chacha20Poly1305CipherFunctions, hash = SHA256HashFunctions,
                byteStream = FixedStream(initiator_e))

        val responder = HandshakeState.initializeReader(
                handshakePattern = handshakePatternXK,
                prologue = prologue,
                s = responder_s, e = Pair(ByteArray(0), ByteArray(0)), rs = ByteArray(0), re = ByteArray(0),
                dh = dh, cipher = Chacha20Poly1305CipherFunctions, hash = SHA256HashFunctions,
                byteStream = FixedStream(responder_e))

        val (outputs, foo) = handshake(initiator, responder, listOf(ByteArray(0), ByteArray(0), ByteArray(0)))
        val (enc, dec) = foo

        enc as InitializedCipherState
        dec as InitializedCipherState
        assertArrayEquals(enc.k, Hex.decode("969ab31b4d288cedf6218839b27a3e2140827047f2c0f01bf5c04435d43511a9"))
        assertArrayEquals(dec.k, Hex.decode("bb9020b8965f4df047e07f955f3c4b88418984aadc5cdb35096b9ea8fa5c3442"))
        val (enc1, ciphertext01) = enc.encryptWithAd(ByteArray(0), Hex.decode("79656c6c6f777375626d6172696e65"))
        val (dec1, ciphertext02) = dec.encryptWithAd(ByteArray(0), Hex.decode("7375626d6172696e6579656c6c6f77"))
        assertArrayEquals(outputs[0], Hex.decode("036360e856310ce5d294e8be33fc807077dc56ac80d95d9cd4ddbd21325eff73f70df6086551151f58b8afe6c195782c6a"))
        assertArrayEquals(outputs[1], Hex.decode("02466d7fcae563e5cb09a0d1870bb580344804617879a14949cf22285f1bae3f276e2470b93aac583c9ef6eafca3f730ae"))
        assertArrayEquals(outputs[2], Hex.decode("b9e3a702e93e3a9948c2ed6e5fd7590a6e1c3a0344cfc9d5b57357049aa22355361aa02e55a8fc28fef5bd6d71ad0c38228dc68b1c466263b47fdf31e560e139ba"))
        assertArrayEquals(ciphertext01, Hex.decode("b64b348cbb37c88e5b76af12dce00a4a69cbe224a374aad16a4ab1b93741c4"))
        assertArrayEquals(ciphertext02, Hex.decode("289de201e633a43e01ea5b0ec1df9726bd04d0109f530f7172efa5808c3108"))
    }


    companion object {
        /**
         * ByteStream implementation that always returns the same data.
         */
        data class FixedStream(val data: ByteArray) : ByteStream {
            override fun nextBytes(length: Int): ByteArray = data
        }

        /**
         * Performs a Noise handshake. Initiator and responder must use the same handshake pattern.
         *
         * @param init    initiator
         * @param resp    responder
         * @param inputs  inputs messages (can all be empty, but the number of input messages must be equal to the number of
         *                remaining handshake patterns)
         * @param outputs accumulator, for internal use only
         * @return the list of output messages produced during the handshake, and the pair of cipherstates produced during the
         *         final stage of the handshake
         */
        fun handshake(init: HandshakeStateWriter, resp: HandshakeStateReader, inputs: List<ByteArray>, outputs: List<ByteArray> = listOf()): Pair<List<ByteArray>, Pair<CipherState, CipherState>> {
            assertEquals(init.messages, resp.messages)
            assertEquals(init.messages.size, inputs.size)
            when (inputs.size) {
                1 -> {
                    val (_, message, foo) = init.write(inputs[0])
                    val (ics0, ics1, _) = foo!!
                    val (_, _, bar) = resp.read(message)
                    val (rcs0, rcs1, _) = bar!!
                    assertEquals(ics0, rcs0)
                    assertEquals(ics1, rcs1)
                    return Pair(outputs.reversed() + message, Pair(ics0, ics1))
                }
                else -> {
                    val (resp1, message, foo) = init.write(inputs[0])
                    assertEquals(foo, null)
                    val (init1, _, bar) = resp.read(message)
                    assertEquals(bar, null)
                    return handshake(init1, resp1, inputs.drop(1), listOf(message) + outputs)
                }
            }
        }

    }
}