package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.lightning.channel.ChannelVersion
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalKeyManagerTestsCommon : LightningTestSuite() {

    @Test
    fun `generate the same node id from the same seed`() {
        // if this test breaks it means that we will generate a different node id  from
        // the same seed, which could be a problem during an upgrade
        val seed = ByteVector("17b086b228025fa8f4416324b6ba2ec36e68570ae2fc3d392520969f2a9d0c1501")
        val keyManager = LocalKeyManager(seed, Block.TestnetGenesisBlock.hash)
        assertEquals(keyManager.nodeId, PublicKey.fromHex("0392ea6e914abcee840dc8a763b02ba5ac47e0ac3fadcd5294f9516fe353882522"))
    }

    @Test
    fun `generate the same secrets from the same seed`() {
        val seed = ByteVector("17b086b228025fa8f4416324b6ba2ec36e68570ae2fc3d392520969f2a9d0c1501")
        val keyManager = LocalKeyManager(seed, Block.TestnetGenesisBlock.hash)
        assertEquals(keyManager.nodeId, PublicKey.fromHex("0392ea6e914abcee840dc8a763b02ba5ac47e0ac3fadcd5294f9516fe353882522"))
        val keyPath = KeyPath("m/1'/2'/3'/4'")
        assertEquals(keyManager.commitmentSecret(keyPath, 0).value, ByteVector32.fromValidHex("1de1a344a80a6d3416cf11cf1803cb1c01c04506bf9344ba0c17f2867658e796"))
        assertEquals(keyManager.commitmentSecret(keyPath, 1).value, ByteVector32.fromValidHex("9b7a115296720c3b459a630ec0247278c7557575552bd64010a9408aa6af6bcd"))
        assertEquals(keyManager.commitmentSecret(keyPath, 2).value, ByteVector32.fromValidHex("1425f73d3c49d095afb39ca0bc5492f5c0703c8eda5de7ce58cebcd535b3a446"))
        assertEquals(keyManager.commitmentPoint(keyPath, 0).value, ByteVector("03f5c9613f85e097bb8be8251629f6bbddec3210bca1b508b4effa35e9a9813911"))
        assertEquals(DeterministicWallet.encode(keyManager.delayedPaymentPoint(keyPath), DeterministicWallet.tpub), "tpubDKeRVNEjuhXHPMGD5BWKRHfGDs81KVgbDxfEnKy5YVfMz8TkdCAVHDyTzWzCMD9u7CfzbMvydM8oUw31t6jVNCgGkayLQrSQ82Zfgrc2681")
        assertEquals(DeterministicWallet.encode(keyManager.htlcPoint(keyPath), DeterministicWallet.tpub), "tpubDKeRVNEjuhXHTTkXtcwfBWsuctode2nrgxZMCHtQVwxXCiBpGPuVkJxduv3RAVY4omXQrewCJiHjqdXBZ3ms4JUGk6mugeVdp2drrqgypGt")
        assertEquals(DeterministicWallet.encode(keyManager.paymentPoint(keyPath), DeterministicWallet.tpub), "tpubDKeRVNEjuhXHMPWrPZsHcaP2WJUGWC1Xy6xJNZEzCYqHSKsV4Hzhf53kCvwLo8M72RGPQC6zz5xKdtzRjk1c1zrAx75c9xXFZp8e6B5WfZ3")
        assertEquals(DeterministicWallet.encode(keyManager.revocationPoint(keyPath), DeterministicWallet.tpub), "tpubDKeRVNEjuhXHK3dHA96zJnWc7i2NM5Hr1HWMVQyo9VuV1tj3GGZCrJkcDa9f1wWamEikqFYqh4xdCs9HeNc6HwPy6YtmVacpCfQpAT8PaG5")
    }

    @Test
    fun `generate different node ids from the same seed on different chains`() {
        val seed = ByteVector("17b086b228025fa8f4416324b6ba2ec36e68570ae2fc3d392520969f2a9d0c1501")
        val keyManager1 = LocalKeyManager(seed, Block.TestnetGenesisBlock.hash)
        val keyManager2 = LocalKeyManager(seed, Block.LivenetGenesisBlock.hash)
        assertTrue { keyManager1.nodeId != keyManager2.nodeId }
        val keyPath = KeyPath("1")
        assertTrue(keyManager1.fundingPublicKey(keyPath) != keyManager2.fundingPublicKey(keyPath))
        assertTrue(keyManager1.commitmentPoint(keyPath, 1) != keyManager2.commitmentPoint(keyPath, 1))
    }

    @Test
    fun `compute channel key path from funding keys`() {
        // if this test fails it means that we don't generate the same channel key path from the same funding pubkey, which
        // will break existing channels !
        val pub = PrivateKey(ByteVector32.fromValidHex("0101010101010101010101010101010101010101010101010101010101010101")).publicKey()
        val keyPath = KeyManager.channelKeyPath(pub)
        assertEquals(keyPath.toString(), "m/1909530642'/1080788911/847211985'/1791010671/1303008749'/34154019'/723973395/767609665")
    }

    fun makefundingKeyPath(entropy: ByteVector, isFunder: Boolean): KeyPath {
        val items = (0..7).toList().map { Pack.int32BE(entropy.toByteArray(), it * 4).toLong() and 0xFFFFFFFFL }
        val last = DeterministicWallet.hardened(if (isFunder) 1L else 0L)
        return KeyPath(items + last)
    }

    @Test
    fun `test vectors (testnet, funder)`() {
        val seed = ByteVector("17b086b228025fa8f4416324b6ba2ec36e68570ae2fc3d392520969f2a9d0c1501")
        val keyManager = LocalKeyManager(seed, Block.TestnetGenesisBlock.hash)
        val fundingKeyPath = makefundingKeyPath(ByteVector("be4fa97c62b9f88437a3be577b31eb48f2165c7bc252194a15ff92d995778cfb"), isFunder = true)
        val fundingPub = keyManager.fundingPublicKey(fundingKeyPath)

        val localParams = TestConstants.Alice.channelParams.copy(fundingKeyPath = fundingKeyPath)
        val channelKeyPath = keyManager.channelKeyPath(localParams, ChannelVersion.STANDARD)

        assertEquals(fundingPub.publicKey, PrivateKey.fromHex("730c0f99408dbfbff00146acf84183ce539fabeeb22c143212f459d71374f715").publicKey())
        assertEquals(keyManager.revocationPoint(channelKeyPath).publicKey, PrivateKey.fromHex("ef2aa0a9b4d0bdbc5ee5025f0d16285dc9d17228af1b2cc1e1456252c2d9d207").publicKey())
        assertEquals(keyManager.paymentPoint(channelKeyPath).publicKey, PrivateKey.fromHex("e1b76bd22587f88f0903c65aa47f4862152297b4e8dcf3af1f60e762a4ab04e5").publicKey())
        assertEquals(keyManager.delayedPaymentPoint(channelKeyPath).publicKey, PrivateKey.fromHex("93d78a9604571baab6882344747a9372f8d0b9e01b569b431314699e397b73e6").publicKey())
        assertEquals(keyManager.htlcPoint(channelKeyPath).publicKey, PrivateKey.fromHex("b08ab019cfc8a2b28992d3915ed217b71a596bc85dc766e0fb1fee805ef531c1").publicKey())
        assertEquals(keyManager.commitmentSecret(channelKeyPath, 0).value, ShaChain.shaChainFromSeed(ByteVector32.fromValidHex("5de1ddde2a94029007f18676b3e9f0141782b95a4aa84061711e554d4111dbb3"), 0xFFFFFFFFFFFFL))
    }

    @Test
    fun `test vectors (testnet, fundee)`() {
        val seed = ByteVector("aeb3e9b5642cd4523e9e09164047f60adb413633549c3c6189192921311894d501")
        val keyManager = LocalKeyManager(seed, Block.TestnetGenesisBlock.hash)
        val fundingKeyPath = makefundingKeyPath(ByteVector("06535806c1aa73971ec4877a5e2e684fa636136c073810f190b63eefc58ca488"), isFunder = false)
        val fundingPub = keyManager.fundingPublicKey(fundingKeyPath)

        val localParams = TestConstants.Alice.channelParams.copy(fundingKeyPath = fundingKeyPath)
        val channelKeyPath = keyManager.channelKeyPath(localParams, ChannelVersion.STANDARD)

        assertEquals(fundingPub.publicKey, PrivateKey.fromHex("cd85f39fad742e5c742eeab16f5f1acaa9d9c48977767c7daa4708a47b7222ec").publicKey())
        assertEquals(keyManager.revocationPoint(channelKeyPath).publicKey, PrivateKey.fromHex("ee211f583f3b1b1fb10dca7c82708d985fde641e83e28080f669eb496de85113").publicKey())
        assertEquals(keyManager.paymentPoint(channelKeyPath).publicKey, PrivateKey.fromHex("ad635d9d4919e5657a9f306963a5976b533e9d70c8defa454f1bd958fae316c8").publicKey())
        assertEquals(keyManager.delayedPaymentPoint(channelKeyPath).publicKey, PrivateKey.fromHex("0f3c23df3feec614117de23d0b3f014174271826a16e59a17d9ebb655cc55e3f").publicKey())
        assertEquals(keyManager.htlcPoint(channelKeyPath).publicKey, PrivateKey.fromHex("664ca828a0510950f24859b62203af192ccc1188f20eb87de33c76e7e04ab0d4").publicKey())
        assertEquals(keyManager.commitmentSecret(channelKeyPath, 0).value, ShaChain.shaChainFromSeed(ByteVector32.fromValidHex("6255a59ea8155d41e62cddef2c8c63a077f75e23fd3eec1fd4881f6851412518"), 0xFFFFFFFFFFFFL))
    }

    @Test
    fun `test vectors (mainnet, funder)`() {
        val seed = ByteVector("d8d5431487c2b19ee6486aad6c3bdfb99d10b727bade7fa848e2ab7901c15bff01")
        val keyManager = LocalKeyManager(seed, Block.LivenetGenesisBlock.hash)
        val fundingKeyPath = makefundingKeyPath(ByteVector("ec1c41cd6be2b6e4ef46c1107f6c51fbb2066d7e1f7720bde4715af233ae1322"), isFunder = true)
        val fundingPub = keyManager.fundingPublicKey(fundingKeyPath)

        val localParams = TestConstants.Alice.channelParams.copy(fundingKeyPath = fundingKeyPath)
        val channelKeyPath = keyManager.channelKeyPath(localParams, ChannelVersion.STANDARD)

        assertEquals(fundingPub.publicKey, PrivateKey.fromHex("041db93c7f1bd0661a44f90c0843976878d1a6315e87547cdec9c8b0dc0d63a4").publicKey())
        assertEquals(keyManager.revocationPoint(channelKeyPath).publicKey, PrivateKey.fromHex("066041952f72969604d80c02d10781fadbc7b1fcb684b94af74e1af2d1b08d46").publicKey())
        assertEquals(keyManager.paymentPoint(channelKeyPath).publicKey, PrivateKey.fromHex("0f939dbaa75b5e01b30c465661b8d272f5ba582ba8c9f0370d86dd0f63e19f93").publicKey())
        assertEquals(keyManager.delayedPaymentPoint(channelKeyPath).publicKey, PrivateKey.fromHex("3907651b3e5edbe61ec6170e1bb56fd417615d42cc6bf9bba72861a1369258a7").publicKey())
        assertEquals(keyManager.htlcPoint(channelKeyPath).publicKey, PrivateKey.fromHex("479fd504f709ba2ae3b34a0ee46cfb38aecfbb6e205fabcefbca4f23f9ef3fac").publicKey())
        assertEquals(keyManager.commitmentSecret(channelKeyPath, 0).value, ShaChain.shaChainFromSeed(ByteVector32.fromValidHex("e0723ef3ef519dfe32c548258797ddbf8a700ce075fa694cc0a110f77cb61cb5"), 0xFFFFFFFFFFFFL))
    }

    @Test
    fun `test vectors (mainnet, fundee)`() {
        val seed = ByteVector("4b809dd593b36131c454d60c2f7bdfd49d12ec455e5b657c47a9ca0f5dfc5eef01")
        val keyManager = LocalKeyManager(seed, Block.LivenetGenesisBlock.hash)
        val fundingKeyPath = makefundingKeyPath(ByteVector("2b4f045be5303d53f9d3a84a1e70c12251168dc29f300cf9cece0ec85cd8182b"), isFunder = false)
        val fundingPub = keyManager.fundingPublicKey(fundingKeyPath)

        val localParams = TestConstants.Alice.channelParams.copy(fundingKeyPath = fundingKeyPath)
        val channelKeyPath = keyManager.channelKeyPath(localParams, ChannelVersion.STANDARD)

        assertEquals(fundingPub.publicKey, PrivateKey.fromHex("31108cadc3d65215502933353b3dcf389504173b560e4a490df97046772a8ef0").publicKey())
        assertEquals(keyManager.revocationPoint(channelKeyPath).publicKey, PrivateKey.fromHex("d2f450e28244479c084f81356e652506ce8bc7461ca5f5e933f4ef601a88dc43").publicKey())
        assertEquals(keyManager.paymentPoint(channelKeyPath).publicKey, PrivateKey.fromHex("650b4ac88e50d2804c695ce32ce5bc394c75862cb558460b668503a7faff4f1e").publicKey())
        assertEquals(keyManager.delayedPaymentPoint(channelKeyPath).publicKey, PrivateKey.fromHex("0648da92d5d03aa3c38f5d64dc29e975360e2f2f899482c4e0cfb105ef0e18c6").publicKey())
        assertEquals(keyManager.htlcPoint(channelKeyPath).publicKey, PrivateKey.fromHex("90569928f7e4dd0dd953ab03f2f6f157d7f8a74187b9fb41af5d77502c419969").publicKey())
        assertEquals(keyManager.commitmentSecret(channelKeyPath, 0).value, ShaChain.shaChainFromSeed(ByteVector32.fromValidHex("7fcbe6c6020580a2df1e8852fb82686bd7f4315110cef19e6e1a4ed61b4d0755"), 0xFFFFFFFFFFFFL))
    }

}
