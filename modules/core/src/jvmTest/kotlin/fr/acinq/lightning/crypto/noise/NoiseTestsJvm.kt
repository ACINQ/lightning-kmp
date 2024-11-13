package fr.acinq.lightning.crypto.noise

import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.secp256k1.Hex
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.junit.Test
import kotlin.test.assertContentEquals

@ExperimentalStdlibApi
class NoiseTestsJvm : LightningTestSuite() {
    @Test
    fun `SymmetricState test`() {
        val name = "Noise_NN_25519_ChaChaPoly_SHA256"
        val symmetricState = SymmetricState.apply(name.encodeToByteArray(), Chacha20Poly1305CipherFunctions, SHA256HashFunctions)
        val symmetricState1 = symmetricState.mixHash("notsecret".encodeToByteArray())
        assertContentEquals(symmetricState1.ck, Hex.decode("4e6f6973655f4e4e5f32353531395f436861436861506f6c795f534841323536"))
        assertContentEquals(symmetricState1.h, Hex.decode("03b71896d90661cfed747aaaca293d1b01365b3f0f6605dbc3c85a7eb9fff519"))
    }

    @Test
    fun `Noise_NN_25519_ChaChaPoly_SHA256 test`() {
        // see https://github.com/trevp/screech/blob/master/vectors.txt
//        val key0 = Hex.decode("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
//        val key1 = Hex.decode("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
//        val key2 = Hex.decode("2122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f40")
        val key3 = Hex.decode("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
        val key4 = Hex.decode("4142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f60")

        val initiator = HandshakeState.initializeWriter(
            handshakePattern = handshakePatternNN,
            prologue = Hex.decode("6e6f74736563726574"),
            s = Pair(ByteArray(0), ByteArray(0)),
            e = Pair(ByteArray(0), ByteArray(0)),
            rs = ByteArray(0),
            re = ByteArray(0),
            dh = Curve25519DHFunctions,
            cipher = Chacha20Poly1305CipherFunctions,
            hash = SHA256HashFunctions,
            byteStream = NoiseTestsCommon.Companion.FixedStream(key3)
        )

        val responder = HandshakeState.initializeReader(
            handshakePattern = handshakePatternNN,
            prologue = Hex.decode("6e6f74736563726574"),
            s = Pair(ByteArray(0), ByteArray(0)),
            e = Pair(ByteArray(0), ByteArray(0)),
            rs = ByteArray(0),
            re = ByteArray(0),
            dh = Curve25519DHFunctions,
            cipher = Chacha20Poly1305CipherFunctions,
            hash = SHA256HashFunctions,
            byteStream = NoiseTestsCommon.Companion.FixedStream(key4)
        )

        val (outputs, foo) = NoiseTestsCommon.handshake(initiator, responder, listOf(ByteArray(0), ByteArray(0)))
        val (enc, dec) = foo
        assertContentEquals(outputs[0], Hex.decode("358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd166254"))
        assertContentEquals(outputs[1], Hex.decode("64b101b1d0be5a8704bd078f9895001fc03e8e9f9522f188dd128d9846d484665cda04f69d491f9bf509e632fc1a20dd"))

        val (_, ciphertext01) = enc.encryptWithAd(ByteArray(0), Hex.decode("79656c6c6f777375626d6172696e65"))
        assertContentEquals(ciphertext01, Hex.decode("96cd46be111804586a935795eeb4ce62bdec121048a10520b00266b22722eb"))
        val (_, ciphertext02) = dec.encryptWithAd(ByteArray(0), Hex.decode("7375626d6172696e6579656c6c6f77"))
        assertContentEquals(ciphertext02, Hex.decode("fe2bc534e31964c0bd56337223e921565e39dbc5f156aa04766ced4689a2a2"))
    }

    @Test
    fun `Noise_NN_25519_ChaChaPoly_SHA256 cacophony test`() {
        // see https://raw.githubusercontent.com/centromere/cacophony/master/vectors/cacophony.txt
        // @formatter:off
        /*
         {
          "name": "Noise_NN_25519_ChaChaPoly_SHA256",
          "pattern": "NN",
          "dh": "25519",
          "cipher": "ChaChaPoly",
          "hash": "SHA256",
          "init_prologue": "4a6f686e2047616c74",
          "init_ephemeral": "893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a",
          "resp_prologue": "4a6f686e2047616c74",
          "resp_ephemeral": "bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b",
          "messages": [
            {
              "payload": "4c756477696720766f6e204d69736573",
              "ciphertext": "ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c79444c756477696720766f6e204d69736573"
            },
            {
              "payload": "4d757272617920526f746862617264",
              "ciphertext": "95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f144808843a0ff96bdf86b579ef7dbf94e812a7470b903c20a85a87e3a1fe863264ae547"
            },
            {
              "payload": "462e20412e20486179656b",
              "ciphertext": "eb1a3e3d80c1792b1bb9cb0e1382f8d8322bfb1ca7c4c8517bb686"
            },
            {
              "payload": "4361726c204d656e676572",
              "ciphertext": "c781b198d2a974eb1da2c7d518c000cf6396de87ca540963c03713"
            },
            {
              "payload": "4a65616e2d426170746973746520536179",
              "ciphertext": "c77048eb6919fdfe8fe45842bfc5b8d1ff50d1e20c717453ccdfe6176d805b996d"
            },
            {
              "payload": "457567656e2042f6686d20766f6e2042617765726b",
              "ciphertext": "61834d7069dcfb7a1adf8d5ac910f83fa04c73a67789895c6f5f995c5db2ce88e49b124178"
            }
          ]
        }
        */
        // @formatter:on

        val initiator = HandshakeState.initializeWriter(
            handshakePattern = handshakePatternNN,
            prologue = Hex.decode("4a6f686e2047616c74"),
            s = Pair(ByteArray(0), ByteArray(0)),
            e = Pair(ByteArray(0), ByteArray(0)),
            rs = ByteArray(0),
            re = ByteArray(0),
            dh = Curve25519DHFunctions,
            cipher = Chacha20Poly1305CipherFunctions,
            hash = SHA256HashFunctions,
            byteStream = NoiseTestsCommon.Companion.FixedStream(Hex.decode("893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a"))
        )

        val responder = HandshakeState.initializeReader(
            handshakePattern = handshakePatternNN,
            prologue = Hex.decode("4a6f686e2047616c74"),
            s = Pair(ByteArray(0), ByteArray(0)),
            e = Pair(ByteArray(0), ByteArray(0)),
            rs = ByteArray(0),
            re = ByteArray(0),
            dh = Curve25519DHFunctions,
            cipher = Chacha20Poly1305CipherFunctions,
            hash = SHA256HashFunctions,
            byteStream = NoiseTestsCommon.Companion.FixedStream(Hex.decode("bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b"))
        )

        val (outputs, foo) = NoiseTestsCommon.handshake(initiator, responder, listOf(Hex.decode("4c756477696720766f6e204d69736573"), Hex.decode("4d757272617920526f746862617264")))
        val (enc, dec) = foo
        assertContentEquals(outputs[0], Hex.decode("ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c79444c756477696720766f6e204d69736573"))
        assertContentEquals(outputs[1], Hex.decode("95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f144808843a0ff96bdf86b579ef7dbf94e812a7470b903c20a85a87e3a1fe863264ae547"))

        val (enc1, ciphertext01) = enc.encryptWithAd(ByteArray(0), Hex.decode("462e20412e20486179656b"))
        assertContentEquals(ciphertext01, Hex.decode("eb1a3e3d80c1792b1bb9cb0e1382f8d8322bfb1ca7c4c8517bb686"))
        val (dec1, ciphertext02) = dec.encryptWithAd(ByteArray(0), Hex.decode("4361726c204d656e676572"))
        assertContentEquals(ciphertext02, Hex.decode("c781b198d2a974eb1da2c7d518c000cf6396de87ca540963c03713"))
        val (_, ciphertext03) = enc1.encryptWithAd(ByteArray(0), Hex.decode("4a65616e2d426170746973746520536179"))
        assertContentEquals(ciphertext03, Hex.decode("c77048eb6919fdfe8fe45842bfc5b8d1ff50d1e20c717453ccdfe6176d805b996d"))
        val (_, ciphertext04) = dec1.encryptWithAd(ByteArray(0), Hex.decode("457567656e2042f6686d20766f6e2042617765726b"))
        assertContentEquals(ciphertext04, Hex.decode("61834d7069dcfb7a1adf8d5ac910f83fa04c73a67789895c6f5f995c5db2ce88e49b124178"))
    }

    @Test
    fun `Noise_XK_25519_ChaChaPoly_SHA256 test`() {

        /*
        see https://github.com/flynn/noise/blob/master/vectors.txt
        handshake=Noise_XK_25519_ChaChaPoly_SHA256
        init_static=000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f
        resp_static=0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20
        gen_init_ephemeral=202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f
        gen_resp_ephemeral=4142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f60
        msg_0_payload=
        msg_0_ciphertext=358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd1662549963aa4003cb0f60f51f7f8b1c0e6a9c
        msg_1_payload=
        msg_1_ciphertext=64b101b1d0be5a8704bd078f9895001fc03e8e9f9522f188dd128d9846d4846630166c893dafe95f71d102a8ac640a52
        msg_2_payload=
        msg_2_ciphertext=24a819b832ab7a11dd1464c2baf72f2c49e0665757911662ab11495a5fd4437e0abe01f5c07176e776e02716c4cb98a005ec4c884c4dc7500d2d9b99e9670ab3
        msg_3_payload=79656c6c6f777375626d6172696e65
        msg_3_ciphertext=e8b0f2fc220f7edc287a91ba45c76f6da1327405789dc61e31a649f57d6d93
        msg_4_payload=7375626d6172696e6579656c6c6f77
        msg_4_ciphertext=ed6901a7cd973e880242b047fc86da03b498e8ed8e9838d6f3d107420dfcd9
         */
        val key0 = Hex.decode("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val key1 = Hex.decode("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
//        val key2 = Hex.decode("2122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f40")
        val key3 = Hex.decode("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
        val key4 = Hex.decode("4142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f60")

        val initiator = HandshakeState.initializeWriter(
            handshakePattern = handshakePatternXK,
            prologue = ByteArray(0),
            s = Curve25519DHFunctions.generateKeyPair(key0),
            e = Pair(ByteArray(0), ByteArray(0)),
            rs = Curve25519DHFunctions.generateKeyPair(key1).first,
            re = ByteArray(0),
            dh = Curve25519DHFunctions,
            cipher = Chacha20Poly1305CipherFunctions,
            hash = SHA256HashFunctions,
            byteStream = NoiseTestsCommon.Companion.FixedStream(key3)
        )

        val responder = HandshakeState.initializeReader(
            handshakePattern = handshakePatternXK,
            prologue = ByteArray(0),
            s = Curve25519DHFunctions.generateKeyPair(key1),
            e = Pair(ByteArray(0), ByteArray(0)),
            rs = ByteArray(0),
            re = ByteArray(0),
            dh = Curve25519DHFunctions,
            cipher = Chacha20Poly1305CipherFunctions,
            hash = SHA256HashFunctions,
            byteStream = NoiseTestsCommon.Companion.FixedStream(key4)
        )

        val (outputs, foo) = NoiseTestsCommon.handshake(initiator, responder, listOf(ByteArray(0), ByteArray(0), ByteArray(0)))
        val (enc, dec) = foo

        assertContentEquals(outputs[0], Hex.decode("358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd1662549963aa4003cb0f60f51f7f8b1c0e6a9c"))
        assertContentEquals(outputs[1], Hex.decode("64b101b1d0be5a8704bd078f9895001fc03e8e9f9522f188dd128d9846d4846630166c893dafe95f71d102a8ac640a52"))
        assertContentEquals(outputs[2], Hex.decode("24a819b832ab7a11dd1464c2baf72f2c49e0665757911662ab11495a5fd4437e0abe01f5c07176e776e02716c4cb98a005ec4c884c4dc7500d2d9b99e9670ab3"))

        val (_, ciphertext01) = enc.encryptWithAd(ByteArray(0), Hex.decode("79656c6c6f777375626d6172696e65"))
        assertContentEquals(ciphertext01, Hex.decode("e8b0f2fc220f7edc287a91ba45c76f6da1327405789dc61e31a649f57d6d93"))
        val (_, ciphertext02) = dec.encryptWithAd(ByteArray(0), Hex.decode("7375626d6172696e6579656c6c6f77"))
        assertContentEquals(ciphertext02, Hex.decode("ed6901a7cd973e880242b047fc86da03b498e8ed8e9838d6f3d107420dfcd9"))
    }

    companion object {
        object Curve25519DHFunctions : DHFunctions {

            override fun name() = "25519"

            override fun generateKeyPair(priv: ByteArray): Pair<ByteArray, ByteArray> {
                val pub = ByteArray(32)
                Curve25519.eval(pub, 0, priv, null)
                return Pair(pub, priv)
            }

            override fun dh(keyPair: Pair<ByteArray, ByteArray>, publicKey: ByteArray): ByteArray {
                val sharedKey = ByteArray(32)
                Curve25519.eval(sharedKey, 0, keyPair.second, publicKey)
                return sharedKey
            }

            override fun dhLen(): Int = 32

            override fun pubKeyLen(): Int = 32
        }
    }
}