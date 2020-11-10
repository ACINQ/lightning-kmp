package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.*
import fr.acinq.eclair.*
import fr.acinq.eclair.Eclair.randomKey
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.crypto.Generators
import fr.acinq.eclair.crypto.KeyManager
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.UpdateAddHtlc
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnchorOutputsTestsCommon {
    val logger = EclairLoggerFactory.newEclairLogger()

    val channelVersion = ChannelVersion.STANDARD or ChannelVersion.STATIC_REMOTEKEY
    val commitmentsFormat = CommitmentsFormat.AnchorOutputs

    val local_funding_privkey = PrivateKey.fromHex("30ff4956bbdd3222d44cc5e8a1261dab1e07957bdac5ae88fe3261ef321f374901")
    val local_funding_pubkey = PublicKey.fromHex(" 023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb")
    val remote_funding_pubkey = PublicKey.fromHex("030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c1")
    val local_privkey = PrivateKey.fromHex("bb13b121cdc357cd2e608b0aea294afca36e2b34cf958e2e6451a2f27469449101")
    val localpubkey = PublicKey.fromHex("030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e7")
    val remotepubkey = PublicKey.fromHex("0394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b")
    val local_delayedpubkey = PublicKey.fromHex("03fd5960528dc152014952efdb702a88f71e3c1653b2314431701ec77e57fde83c")
    val local_revocation_pubkey = PublicKey.fromHex("0212a140cd0c6539d07cd08dfe09984dec3251ea808b892efeac3ede9402bf2b19")
    val remote_funding_privkey = PrivateKey.fromHex("1552dfba4f6cf29a62a0af13c8d6981d36d0ef8d61ba10fb0fe90da7634d7e1301")
    val local_payment_basepoint_secret = PrivateKey.fromHex("111111111111111111111111111111111111111111111111111111111111111101")
    val remote_revocation_basepoint_secret = PrivateKey.fromHex("222222222222222222222222222222222222222222222222222222222222222201")
    val local_delayed_payment_basepoint_secret = PrivateKey.fromHex("333333333333333333333333333333333333333333333333333333333333333301")
    val remote_payment_basepoint_secret = PrivateKey.fromHex("444444444444444444444444444444444444444444444444444444444444444401")
    val local_per_commitment_secret = PrivateKey.fromHex("1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a0908070605040302010001")

    // From remote_revocation_basepoint_secret
    val remote_revocation_basepoint = PublicKey.fromHex("02466d7fcae563e5cb09a0d1870bb580344804617879a14949cf22285f1bae3f27")

    // From local_delayed_payment_basepoint_secret
    val local_delayed_payment_basepoint = PublicKey.fromHex("023c72addb4fdf09af94f0c94d7fe92a386a7e70cf8a1d85916386bb2535c7b1b1")
    val local_per_commitment_point = PublicKey.fromHex("025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486")
    val remote_privkey = PrivateKey.fromHex("8deba327a7cc6d638ab0eb025770400a6184afcba6713c210d8d10e199ff2fda01")

    // From local_delayed_payment_basepoint_secret, local_per_commitment_point and local_delayed_payment_basepoint
    val local_delayed_privkey = PrivateKey.fromHex("adf3464ce9c2f230fd2582fda4c6965e4993ca5524e8c9580e3df0cf226981ad01")

    val local_htlc_privkey = Generators.derivePrivKey(local_payment_basepoint_secret, local_per_commitment_point)
    val local_payment_privkey = if (channelVersion.hasStaticRemotekey) local_payment_basepoint_secret else local_htlc_privkey
    val local_delayed_payment_privkey = Generators.derivePrivKey(local_delayed_payment_basepoint_secret, local_per_commitment_point)

    val remote_htlc_privkey = Generators.derivePrivKey(remote_payment_basepoint_secret, local_per_commitment_point)
    val remote_payment_privkey = if (channelVersion.hasStaticRemotekey) remote_payment_basepoint_secret else remote_htlc_privkey

    val funding_tx =
        Transaction.read("0200000001adbb20ea41a8423ea937e76e8151636bf6093b70eaff942930d20576600521fd000000006b48304502210090587b6201e166ad6af0227d3036a9454223d49a1f11839c1a362184340ef0240220577f7cd5cca78719405cbf1de7414ac027f0239ef6e214c90fcaab0454d84b3b012103535b32d5eb0a6ed0982a0479bbadc9868d9836f6ba94dd5a63be16d875069184ffffffff028096980000000000220020c015c4a6be010e21657068fc2e6a9d02b27ebe4d490a25846f7237f104d1a3cd20256d29010000001600143ca33c2e4446f4a305f23c80df8ad1afdcf652f900000000")
    val commitTxInput = Transactions.InputInfo(
        OutPoint(funding_tx, 0),
        funding_tx.txOut[0],
        Scripts.multiSig2of2(local_funding_pubkey, remote_funding_pubkey)
    )
    val preimages = listOf(
        ByteVector32("0000000000000000000000000000000000000000000000000000000000000000"),
        ByteVector32("0101010101010101010101010101010101010101010101010101010101010101"),
        ByteVector32("0202020202020202020202020202020202020202020202020202020202020202"),
        ByteVector32("0303030303030303030303030303030303030303030303030303030303030303"),
        ByteVector32("0404040404040404040404040404040404040404040404040404040404040404"),
    )
    val htlcs = listOf<DirectedHtlc>(
        IncomingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, 1000000.msat, preimages[0].sha256(), CltvExpiry(500), TestConstants.emptyOnionPacket)),
        IncomingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 1, 2000000.msat, preimages[1].sha256(), CltvExpiry(501), TestConstants.emptyOnionPacket)),
        OutgoingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 2, 2000000.msat, preimages[2].sha256(), CltvExpiry(502), TestConstants.emptyOnionPacket)),
        OutgoingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 3, 3000000.msat, preimages[3].sha256(), CltvExpiry(503), TestConstants.emptyOnionPacket)),
        IncomingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 4, 4000000.msat, preimages[4].sha256(), CltvExpiry(504), TestConstants.emptyOnionPacket)),
    )

    // high level tests which calls Commitments methods to generate transactions
    fun runHighLevelTest(testCase: TestCase) {
        val localParams = LocalParams(
            TestConstants.Alice.nodeParams.nodeId,
            KeyPath.empty, 546.sat, 1000000000L, 0.sat, 0.msat, CltvExpiryDelta(144), 1000, true,
            Script.write(Script.pay2wpkh(randomKey().publicKey())).toByteVector(), local_payment_privkey.publicKey(),
            TestConstants.Alice.nodeParams.features,
        )
        val remoteParams = RemoteParams(
            TestConstants.Bob.nodeParams.nodeId,
            546.sat,
            1000000000L,
            0.sat,
            0.msat,
            CltvExpiryDelta(144),
            1000,
            remote_funding_pubkey,
            remote_revocation_basepoint,
            remote_payment_privkey.publicKey(),
            PrivateKey.fromHex("444444444444444444444444444444444444444444444444444444444444444401").publicKey(),
            PrivateKey.fromHex("444444444444444444444444444444444444444444444444444444444444444401").publicKey(),
            TestConstants.Bob.nodeParams.features
        )
        val spec = CommitmentSpec(
            if (testCase.UseTestHtlcs) htlcs.toSet() else setOf(),
            testCase.FeePerKw.toLong(),
            testCase.LocalBalance.msat,
            testCase.RemoteBalance.msat
        )

        /* The test vector values are derived, as per Key Derivation, though it's not
        required for this test. They're included here for completeness and
        in case someone wants to reproduce the test vectors themselves:

        INTERNAL: remote_funding_privkey: 1552dfba4f6cf29a62a0af13c8d6981d36d0ef8d61ba10fb0fe90da7634d7e1301
        INTERNAL: local_payment_basepoint_secret: 111111111111111111111111111111111111111111111111111111111111111101
        INTERNAL: remote_revocation_basepoint_secret: 222222222222222222222222222222222222222222222222222222222222222201
        INTERNAL: local_delayed_payment_basepoint_secret: 333333333333333333333333333333333333333333333333333333333333333301
        INTERNAL: remote_payment_basepoint_secret: 444444444444444444444444444444444444444444444444444444444444444401
        x_local_per_commitment_secret: 1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a0908070605040302010001
        # From remote_revocation_basepoint_secret
                INTERNAL: remote_revocation_basepoint: 02466d7fcae563e5cb09a0d1870bb580344804617879a14949cf22285f1bae3f27
        # From local_delayed_payment_basepoint_secret
                INTERNAL: local_delayed_payment_basepoint: 023c72addb4fdf09af94f0c94d7fe92a386a7e70cf8a1d85916386bb2535c7b1b1
        INTERNAL: local_per_commitment_point: 025f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486
        INTERNAL: remote_privkey: 8deba327a7cc6d638ab0eb025770400a6184afcba6713c210d8d10e199ff2fda01
        # From local_delayed_payment_basepoint_secret, local_per_commitment_point and local_delayed_payment_basepoint
        INTERNAL: local_delayed_privkey: adf3464ce9c2f230fd2582fda4c6965e4993ca5524e8c9580e3df0cf226981ad01
        */
        class MyKeyManager : KeyManager {
            override val nodeKey: DeterministicWallet.ExtendedPrivateKey get() = DeterministicWallet.ExtendedPrivateKey(TestConstants.Alice.nodeParams.nodePrivateKey.value, ByteVector32.Zeroes, 0, KeyPath.empty, 0)
            override val nodeId: PublicKey get() = nodeKey.publicKey

            fun PrivateKey.toExtendedPrivateKey() = DeterministicWallet.ExtendedPrivateKey(this.value, ByteVector32.Zeroes, 0, KeyPath.empty, 0)

            fun PublicKey.toExtendedPublicKey() = DeterministicWallet.ExtendedPublicKey(this.value, ByteVector32.Zeroes, 0, KeyPath.empty, 0)

            override fun closingPubkeyScript(fundingPubKey: PublicKey): Pair<PublicKey, ByteArray> {
                TODO("Not yet implemented")
            }

            override fun fundingPublicKey(keyPath: KeyPath): DeterministicWallet.ExtendedPublicKey = local_funding_pubkey.toExtendedPublicKey()

            override fun revocationPoint(channelKeyPath: KeyPath): DeterministicWallet.ExtendedPublicKey = local_revocation_pubkey.toExtendedPublicKey()

            override fun paymentPoint(channelKeyPath: KeyPath): DeterministicWallet.ExtendedPublicKey = local_payment_basepoint_secret.publicKey().toExtendedPublicKey()

            override fun delayedPaymentPoint(channelKeyPath: KeyPath): DeterministicWallet.ExtendedPublicKey = local_delayed_payment_basepoint.toExtendedPublicKey()

            override fun htlcPoint(channelKeyPath: KeyPath): DeterministicWallet.ExtendedPublicKey = PrivateKey.fromHex("1111111111111111111111111111111111111111111111111111111111111111").publicKey().toExtendedPublicKey()

            override fun commitmentSecret(channelKeyPath: KeyPath, index: Long): PrivateKey = local_per_commitment_secret

            override fun commitmentPoint(channelKeyPath: KeyPath, index: Long): PublicKey = local_per_commitment_point

            override fun newFundingKeyPath(isFunder: Boolean): KeyPath {
                TODO("Not yet implemented")
            }

            override fun sign(tx: Transactions.TransactionWithInputInfo, publicKey: DeterministicWallet.ExtendedPublicKey): ByteVector64 {
                val privateKey = local_funding_privkey
                return Transactions.sign(tx, privateKey)
            }

            override fun sign(tx: Transactions.TransactionWithInputInfo, publicKey: DeterministicWallet.ExtendedPublicKey, remotePoint: PublicKey): ByteVector64 {
                TODO("Not yet implemented")
            }

            override fun sign(tx: Transactions.TransactionWithInputInfo, publicKey: DeterministicWallet.ExtendedPublicKey, remoteSecret: PrivateKey): ByteVector64 {
                TODO("Not yet implemented")
            }

            override fun signChannelAnnouncement(fundingKeyPath: KeyPath, chainHash: ByteVector32, shortChannelId: ShortChannelId, remoteNodeId: PublicKey, remoteFundingKey: PublicKey, features: Features): Pair<ByteVector64, ByteVector64> {
                TODO("Not yet implemented")
            }
        }

        val keyManager = MyKeyManager()
        val (commitTx, htlcTimeoutTxs, htlcSuccessTxs) = Commitments.makeLocalTxs(
            CommitmentsFormat.AnchorOutputs, keyManager, channelVersion, 42, localParams, remoteParams,
            Transactions.InputInfo(OutPoint(funding_tx, 0), funding_tx.txOut[0], Scripts.multiSig2of2(local_funding_pubkey, remote_funding_pubkey)),
            local_per_commitment_point,
            spec
        )

        val localSig = Transactions.sign(commitTx, local_funding_privkey)
        val remoteSig = Transactions.sign(commitTx, remote_funding_privkey)
        val signedTx = Transactions.addSigs(commitTx, local_funding_pubkey, remote_funding_pubkey, localSig, remoteSig)
        assertEquals(Transaction.read(testCase.ExpectedCommitmentTxHex), signedTx.tx)
        val txs = testCase.HtlcDescs.map { Transaction.read(it.ResolutionTxHex).txid to Transaction.read(it.ResolutionTxHex) }.toMap()
        val remoteHtlcSigs = testCase.HtlcDescs.map { Transaction.read(it.ResolutionTxHex).txid to ByteVector(it.RemoteSigHex) }.toMap()
        assertTrue { remoteHtlcSigs.keys.containsAll(htlcTimeoutTxs.map { it.tx.txid }) }
        assertTrue { remoteHtlcSigs.keys.containsAll(htlcSuccessTxs.map { it.tx.txid }) }
        htlcTimeoutTxs.forEach {
            val localHtlcSig = Transactions.sign(it, local_htlc_privkey, SigHash.SIGHASH_ALL)
            val remoteHtlcSig = Crypto.der2compact(remoteHtlcSigs[it.tx.txid]!!.toByteArray())
            val expectedTx = txs[it.tx.txid]
            val signed = Transactions.addSigs(it, localHtlcSig, remoteHtlcSig, commitmentsFormat.htlcTxSighashFlag)
            assertEquals(expectedTx, signed.tx)
        }
        htlcSuccessTxs.forEach {
            val localHtlcSig = Transactions.sign(it, local_htlc_privkey, SigHash.SIGHASH_ALL)
            val remoteHtlcSig = Crypto.der2compact(remoteHtlcSigs[it.tx.txid]!!.toByteArray())
            val expectedTx = txs[it.tx.txid]
            val signed = Transactions.addSigs(it, localHtlcSig, remoteHtlcSig, preimages.find { it1 -> it1.sha256() == it.paymentHash }!!, commitmentsFormat.htlcTxSighashFlag)
            assertEquals(expectedTx, signed.tx)
        }
    }

    // low-leve tests where transactions are built manually using low-level primitives
    fun runLowLevelTest(testCase: TestCase) {
        val spec = CommitmentSpec(
            if (testCase.UseTestHtlcs) htlcs.toSet() else setOf(),
            testCase.FeePerKw.toLong(),
            testCase.LocalBalance.msat,
            testCase.RemoteBalance.msat
        )
        val outputs = Transactions.makeCommitTxOutputs(
            commitmentsFormat,
            local_funding_pubkey,
            remote_funding_pubkey,
            true,
            546.sat,
            local_revocation_pubkey,
            CltvExpiryDelta(144),
            local_delayed_payment_privkey.publicKey(),
            remote_payment_privkey.publicKey(),
            local_htlc_privkey.publicKey(),
            remote_htlc_privkey.publicKey(),
            spec
        )
        val commitTx = Transactions.makeCommitTx(
            commitmentsFormat,
            commitTxInput,
            42L,
            local_payment_basepoint_secret.publicKey(),
            remote_payment_basepoint_secret.publicKey(),
            true,
            outputs
        )
        val localSig = Transactions.sign(commitTx, local_funding_privkey)
        val remoteSig = Transactions.sign(commitTx, remote_funding_privkey)
        val signedTx = Transactions.addSigs(commitTx, local_funding_pubkey, remote_funding_pubkey, localSig, remoteSig)
        assertEquals(Transaction.read(testCase.ExpectedCommitmentTxHex), signedTx.tx)

        val txs = testCase.HtlcDescs.map { Transaction.read(it.ResolutionTxHex).txid to Transaction.read(it.ResolutionTxHex) }.toMap()
        val remoteHtlcSigs = testCase.HtlcDescs.map { Transaction.read(it.ResolutionTxHex).txid to ByteVector(it.RemoteSigHex) }.toMap()
        val (htlcTimeoutTxs, htlcSuccessTxs) = Transactions.makeHtlcTxs(commitmentsFormat, commitTx.tx, 546.sat, local_revocation_pubkey, CltvExpiryDelta(144), local_delayedpubkey, spec.feeratePerKw, outputs)
        assertTrue { remoteHtlcSigs.keys.containsAll(htlcTimeoutTxs.map { it.tx.txid }) }
        assertTrue { remoteHtlcSigs.keys.containsAll(htlcSuccessTxs.map { it.tx.txid }) }
        htlcTimeoutTxs.forEach {
            val localHtlcSig = Transactions.sign(it, local_htlc_privkey, SigHash.SIGHASH_ALL)
            val remoteHtlcSig = Crypto.der2compact(remoteHtlcSigs[it.tx.txid]!!.toByteArray())
            val expectedTx = txs[it.tx.txid]
            val signed = Transactions.addSigs(it, localHtlcSig, remoteHtlcSig, commitmentsFormat.htlcTxSighashFlag)
            assertEquals(expectedTx, signed.tx)
        }
        htlcSuccessTxs.forEach {
            val localHtlcSig = Transactions.sign(it, local_htlc_privkey, SigHash.SIGHASH_ALL)
            val remoteHtlcSig = Crypto.der2compact(remoteHtlcSigs[it.tx.txid]!!.toByteArray())
            val expectedTx = txs[it.tx.txid]
            val signed = Transactions.addSigs(it, localHtlcSig, remoteHtlcSig, preimages.find { it1 -> it1.sha256() == it.paymentHash }!!, commitmentsFormat.htlcTxSighashFlag)
            assertEquals(expectedTx, signed.tx)
        }
    }

    @Test
    fun `BOLT 3 test vectors`() {
        testCases.forEach {
            val result = runTrying { runLowLevelTest(it) }
            if (result is Try.Failure) error("low level test ${it.Name} failed: ${result.error}}")
            val result1 = runTrying { runHighLevelTest(it) }
            if (result1 is Try.Failure) error("high level test ${it.Name} failed: ${result1.error}}")
        }
    }

    companion object {
        @kotlinx.serialization.Serializable
        data class HtlcDesc(val RemoteSigHex: String, val ResolutionTxHex: String)

        @kotlinx.serialization.Serializable
        data class TestCase(val Name: String, val LocalBalance: Long, val RemoteBalance: Long, val FeePerKw: Int, val UseTestHtlcs: Boolean, val HtlcDescs: Array<HtlcDesc>, val ExpectedCommitmentTxHex: String, val RemoteSigHex: String) {
            val ExpectedCommitmentTx get() = Transaction.read(ExpectedCommitmentTxHex)
        }

        val raw = """
            [
    {
        "Name": "simple commitment tx with no HTLCs",
        "LocalBalance": 7000000000,
        "RemoteBalance": 3000000000,
        "FeePerKw": 15000,
        "UseTestHtlcs": false,
        "HtlcDescs": [],
        "ExpectedCommitmentTxHex": "02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b80044a010000000000002200202b1b5854183c12d3316565972c4668929d314d81c5dcdbb21cb45fe8a9a8114f4a01000000000000220020e9e86e4823faa62e222ebc858a226636856158f07e69898da3b0d1af0ddb3994c0c62d0000000000220020f3394e1e619b0eca1f91be2fb5ab4dfc59ba5b84ebe014ad1d43a564d012994a508b6a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e04004830450221008266ac6db5ea71aac3c95d97b0e172ff596844851a3216eb88382a8dddfd33d2022050e240974cfd5d708708b4365574517c18e7ae535ef732a3484d43d0d82be9f701483045022100f89034eba16b2be0e5581f750a0a6309192b75cce0f202f0ee2b4ec0cc394850022076c65dc507fe42276152b7a3d90e961e678adbe966e916ecfe85e64d430e75f301475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220",
        "RemoteSigHex": "3045022100f89034eba16b2be0e5581f750a0a6309192b75cce0f202f0ee2b4ec0cc394850022076c65dc507fe42276152b7a3d90e961e678adbe966e916ecfe85e64d430e75f3"
    },
    {
        "Name": "simple commitment tx with no HTLCs and single anchor",
        "LocalBalance": 7000000000,
        "RemoteBalance": 0,
        "FeePerKw": 15000,
        "UseTestHtlcs": false,
        "HtlcDescs": [],
        "ExpectedCommitmentTxHex": "02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b80024a010000000000002200202b1b5854183c12d3316565972c4668929d314d81c5dcdbb21cb45fe8a9a8114f508b6a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0400483045022100da5310620e72bc23dc57af25d18102cc75479aea0258ab89fe1a66ca176033ec0220339efb450c12872e134c8bda986bb92f3e4eebcaa2d0fee5d9a2b1257d12f12a0147304402200dc30542c9b8b2ff4b8d98f46798b3218a088a07e97b9e786177287dc6a5347b02203d23b1c2bf17262362fdb4cdcc36dbb449a9efcdb10051ad52cfa09fc76842b001475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220",
        "RemoteSigHex": "304402200dc30542c9b8b2ff4b8d98f46798b3218a088a07e97b9e786177287dc6a5347b02203d23b1c2bf17262362fdb4cdcc36dbb449a9efcdb10051ad52cfa09fc76842b0"
    },
    {
        "Name": "commitment tx with seven outputs untrimmed (maximum feerate)",
        "LocalBalance": 6988000000,
        "RemoteBalance": 3000000000,
        "FeePerKw": 644,
        "UseTestHtlcs": true,
        "HtlcDescs": [
            {
                "RemoteSigHex": "304402205912d91c58016f593d9e46fefcdb6f4125055c41a17b03101eaaa034b9028ab60220520d4d239c85c66e4c75c5b413620b62736e227659d7821b308e2b8ced3e728e",
                "ResolutionTxHex": "02000000000101b8cefef62ea66f5178b9361b2371be0759cbc8c689bcfa7a8e6746d497ec221a0200000000010000000122020000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e050047304402205912d91c58016f593d9e46fefcdb6f4125055c41a17b03101eaaa034b9028ab60220520d4d239c85c66e4c75c5b413620b62736e227659d7821b308e2b8ced3e728e834730440220473166a5adcca68550bab80403f410a726b5bd855030527e3fefa8c1e4b4fd7b02203b1dc91d8d69039473036cb5c34398b99e8eb90ae500c22130a557b62294b188012000000000000000000000000000000000000000000000000000000000000000008d76a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c8201208763a914b8bcb07f6344b42ab04250c86a6e8b75d3fdbbc688527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae677502f401b175ac6851b2756800000000"
            },
            {
                "RemoteSigHex": "3045022100c6b4113678039ee1e43a6cba5e3224ed2355ffc05e365a393afe8843dc9a76860220566d01fd52d65a89ba8595023884f9e8f2e9a310a6b9b85281c0bce06863430c",
                "ResolutionTxHex": "02000000000101b8cefef62ea66f5178b9361b2371be0759cbc8c689bcfa7a8e6746d497ec221a0300000000010000000124060000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0500483045022100c6b4113678039ee1e43a6cba5e3224ed2355ffc05e365a393afe8843dc9a76860220566d01fd52d65a89ba8595023884f9e8f2e9a310a6b9b85281c0bce06863430c83483045022100d0d86307ea55d5daa80f453ad6d64b78fe8a6504aac25407c73e8502c0702c1602206a0809a02aa00c8dc4a53d976bb05d4605d8bb0b7b26b973a5c4e2734d8afbb401008876a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c820120876475527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae67a914b43e1b38138a41b37f7cd9a1d274bc63e3a9b5d188ac6851b27568f6010000"
            },
            {
                "RemoteSigHex": "304402203c3a699fb80a38112aafd73d6e3a9b7d40bc2c3ed8b7fbc182a20f43b215172202204e71821b984d1af52c4b8e2cd4c572578c12a965866130c2345f61e4c2d3fef4",
                "ResolutionTxHex": "02000000000101b8cefef62ea66f5178b9361b2371be0759cbc8c689bcfa7a8e6746d497ec221a040000000001000000010a060000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e050047304402203c3a699fb80a38112aafd73d6e3a9b7d40bc2c3ed8b7fbc182a20f43b215172202204e71821b984d1af52c4b8e2cd4c572578c12a965866130c2345f61e4c2d3fef48347304402205bcfa92f83c69289a412b0b6dd4f2a0fe0b0fc2d45bd74706e963257a09ea24902203783e47883e60b86240e877fcbf33d50b1742f65bc93b3162d1be26583b367ee012001010101010101010101010101010101010101010101010101010101010101018d76a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c8201208763a9144b6b2e5444c2639cc0fb7bcea5afba3f3cdce23988527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae677502f501b175ac6851b2756800000000"
            },
            {
                "RemoteSigHex": "304402200f089bcd20f25475216307d32aa5b6c857419624bfba1da07335f51f6ba4645b02206ce0f7153edfba23b0d4b2afc26bb3157d404368cb8ea0ca7cf78590dcdd28cf",
                "ResolutionTxHex": "02000000000101b8cefef62ea66f5178b9361b2371be0759cbc8c689bcfa7a8e6746d497ec221a050000000001000000010c0a0000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e050047304402200f089bcd20f25475216307d32aa5b6c857419624bfba1da07335f51f6ba4645b02206ce0f7153edfba23b0d4b2afc26bb3157d404368cb8ea0ca7cf78590dcdd28cf83483045022100e4516da08f72c7a4f7b2f37aa84a0feb54ae2cc5b73f0da378e81ae0ca8119bf02207751b2628d8e2f62b4b9abccda4866246c1bfcc82e3d416ad562fd212102c28f01008876a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c820120876475527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae67a9148a486ff2e31d6158bf39e2608864d63fefd09d5b88ac6851b27568f7010000"
            },
            {
                "RemoteSigHex": "3045022100aa72cfaf0965020c73a12c77276c6411ca68c4de36ac1998adf86c917a899a43022060da0a159fecfe0bed37c3962d767f12f90e30fed8a8f34b1301775c21a2bd3a",
                "ResolutionTxHex": "02000000000101b8cefef62ea66f5178b9361b2371be0759cbc8c689bcfa7a8e6746d497ec221a06000000000100000001da0d0000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0500483045022100aa72cfaf0965020c73a12c77276c6411ca68c4de36ac1998adf86c917a899a43022060da0a159fecfe0bed37c3962d767f12f90e30fed8a8f34b1301775c21a2bd3a8347304402203cd12065c2a42963c762e6b1a981e17695616ecb6f9fb33d8b0717cdd7ca0ee4022065500005c491c1dcf2fe9c4024f74b1c90785d572527055a491278f901143904012004040404040404040404040404040404040404040404040404040404040404048d76a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c8201208763a91418bc1a114ccf9c052d3d23e28d3b0a9d1227434288527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae677502f801b175ac6851b2756800000000"
            }
        ],
        "ExpectedCommitmentTxHex": "02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b80094a010000000000002200202b1b5854183c12d3316565972c4668929d314d81c5dcdbb21cb45fe8a9a8114f4a01000000000000220020e9e86e4823faa62e222ebc858a226636856158f07e69898da3b0d1af0ddb3994e80300000000000022002010f88bf09e56f14fb4543fd26e47b0db50ea5de9cf3fc46434792471082621aed0070000000000002200203e68115ae0b15b8de75b6c6bc9af5ac9f01391544e0870dae443a1e8fe7837ead007000000000000220020fe0598d74fee2205cc3672e6e6647706b4f3099713b4661b62482c3addd04a5eb80b000000000000220020f96d0334feb64a4f40eb272031d07afcb038db56aa57446d60308c9f8ccadef9a00f000000000000220020ce6e751274836ff59622a0d1e07f8831d80bd6730bd48581398bfadd2bb8da9ac0c62d0000000000220020f3394e1e619b0eca1f91be2fb5ab4dfc59ba5b84ebe014ad1d43a564d012994a4f996a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0400483045022100ef82a405364bfc4007e63a7cc82925a513d79065bdbc216d60b6a4223a323f8a02200716730b8561f3c6d362eaf47f202e99fb30d0557b61b92b5f9134f8e2de368101483045022100e0106830467a558c07544a3de7715610c1147062e7d091deeebe8b5c661cda9402202ad049c1a6d04834317a78483f723c205c9f638d17222aafc620800cc1b6ae3501475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220",
        "RemoteSigHex": "3045022100e0106830467a558c07544a3de7715610c1147062e7d091deeebe8b5c661cda9402202ad049c1a6d04834317a78483f723c205c9f638d17222aafc620800cc1b6ae35"
    },
    {
        "Name": "commitment tx with six outputs untrimmed (minimum feerate)",
        "LocalBalance": 6988000000,
        "RemoteBalance": 3000000000,
        "FeePerKw": 645,
        "UseTestHtlcs": true,
        "HtlcDescs": [
            {
                "RemoteSigHex": "30440220446f9e5c375db6a61d6eeee8b59219a30a4a37372afc2670a1a2889c78e9b943022061895f6088fb48b490ab2140a4842c277b64bf25ff591625dd0356e0c96ab7a8",
                "ResolutionTxHex": "02000000000101104f394af4c4fad78337f95e3e9f802f4c0d86ab231853af09b28534856132000200000000010000000123060000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e05004730440220446f9e5c375db6a61d6eeee8b59219a30a4a37372afc2670a1a2889c78e9b943022061895f6088fb48b490ab2140a4842c277b64bf25ff591625dd0356e0c96ab7a883483045022100c1621ba26a99c263fd885feff5fda5ca2cc73df080b3a49ecf15164ee244d2a5022037f4cc7fd4441af39a83a0e44c3b1db7d64a4c8080e8697f9e952f85421a34d801008876a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c820120876475527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae67a914b43e1b38138a41b37f7cd9a1d274bc63e3a9b5d188ac6851b27568f6010000"
            },
            {
                "RemoteSigHex": "3044022027a3ffcb8a007e3349d75382efbd4b3fb99fcbd479a18555e58697bd1278d5c402205c8303d46211c3ae8975fe84a0df08b4623119fecd03bc93b49d7f7a0c64c710",
                "ResolutionTxHex": "02000000000101104f394af4c4fad78337f95e3e9f802f4c0d86ab231853af09b28534856132000300000000010000000109060000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0500473044022027a3ffcb8a007e3349d75382efbd4b3fb99fcbd479a18555e58697bd1278d5c402205c8303d46211c3ae8975fe84a0df08b4623119fecd03bc93b49d7f7a0c64c71083483045022100b697aca55c6fb15e5348bb7387b584815fd15e8dd306afe0c477cb550d0c2d40022050b0f7e370f7604d2fec781fefe86715dbe95dff4dab88d628f509d62f854de1012001010101010101010101010101010101010101010101010101010101010101018d76a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c8201208763a9144b6b2e5444c2639cc0fb7bcea5afba3f3cdce23988527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae677502f501b175ac6851b2756800000000"
            },
            {
                "RemoteSigHex": "30440220013975ae356e6daf22a86a29f21c4f35aca82ed8f731a1103c60c74f5ed1c5aa02200350d4e5455cdbcacb7ccf174db5bed8286019e509a113f6b4c5e606ee12c9d7",
                "ResolutionTxHex": "02000000000101104f394af4c4fad78337f95e3e9f802f4c0d86ab231853af09b2853485613200040000000001000000010b0a0000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e05004730440220013975ae356e6daf22a86a29f21c4f35aca82ed8f731a1103c60c74f5ed1c5aa02200350d4e5455cdbcacb7ccf174db5bed8286019e509a113f6b4c5e606ee12c9d783483045022100e69a29f78779577830e73f327073c93168896f1b89432124b7846f5def9cd9cb02204433db3697e6ed7ac89574ca066a749640e0c9e114ac2e0ee4545741fcf7b7e901008876a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c820120876475527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae67a9148a486ff2e31d6158bf39e2608864d63fefd09d5b88ac6851b27568f7010000"
            },
            {
                "RemoteSigHex": "304402205257017423644c7e831f30bc0c334eecfe66e9a6d2e92d157c5bece576b2be4f022047b21cf8e955e22b7471940563922d1a5852fb95459ca32905c7d46a19141664",
                "ResolutionTxHex": "02000000000101104f394af4c4fad78337f95e3e9f802f4c0d86ab231853af09b285348561320005000000000100000001d90d0000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e050047304402205257017423644c7e831f30bc0c334eecfe66e9a6d2e92d157c5bece576b2be4f022047b21cf8e955e22b7471940563922d1a5852fb95459ca32905c7d46a191416648347304402204f5de65a624e3f757adffb678bd887eb4e656538c5ea7044922f6ee3eed8a06202206ff6f7bfe73b565343cae76131ac658f1a9c60d3ca2343358cda60b9e35f94c8012004040404040404040404040404040404040404040404040404040404040404048d76a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c8201208763a91418bc1a114ccf9c052d3d23e28d3b0a9d1227434288527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae677502f801b175ac6851b2756800000000"
            }
        ],
        "ExpectedCommitmentTxHex": "02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b80084a010000000000002200202b1b5854183c12d3316565972c4668929d314d81c5dcdbb21cb45fe8a9a8114f4a01000000000000220020e9e86e4823faa62e222ebc858a226636856158f07e69898da3b0d1af0ddb3994d0070000000000002200203e68115ae0b15b8de75b6c6bc9af5ac9f01391544e0870dae443a1e8fe7837ead007000000000000220020fe0598d74fee2205cc3672e6e6647706b4f3099713b4661b62482c3addd04a5eb80b000000000000220020f96d0334feb64a4f40eb272031d07afcb038db56aa57446d60308c9f8ccadef9a00f000000000000220020ce6e751274836ff59622a0d1e07f8831d80bd6730bd48581398bfadd2bb8da9ac0c62d0000000000220020f3394e1e619b0eca1f91be2fb5ab4dfc59ba5b84ebe014ad1d43a564d012994abc996a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0400483045022100d57697c707b6f6d053febf24b98e8989f186eea42e37e9e91663ec2c70bb8f70022079b0715a472118f262f43016a674f59c015d9cafccec885968e76d9d9c5d005101473044022025d97466c8049e955a5afce28e322f4b34d2561118e52332fb400f9b908cc0a402205dc6fba3a0d67ee142c428c535580cd1f2ff42e2f89b47e0c8a01847caffc31201475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220",
        "RemoteSigHex": "3044022025d97466c8049e955a5afce28e322f4b34d2561118e52332fb400f9b908cc0a402205dc6fba3a0d67ee142c428c535580cd1f2ff42e2f89b47e0c8a01847caffc312"
    },
    {
        "Name": "commitment tx with six outputs untrimmed (maximum feerate)",
        "LocalBalance": 6988000000,
        "RemoteBalance": 3000000000,
        "FeePerKw": 2060,
        "UseTestHtlcs": true,
        "HtlcDescs": [
            {
                "RemoteSigHex": "30440220011f999016570bbab9f3125377d0f35096b4dbe155f97c20f71829ead2817d1602201f23f7e17f6928734601c5d8613431eed5c90aa41c3106e8c1cb02ce32aacb5d",
                "ResolutionTxHex": "02000000000101e7f364cf3a554b670767e723ef14b2af7a3eac70bd79dbde9256f384369c062d0200000000010000000175020000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e05004730440220011f999016570bbab9f3125377d0f35096b4dbe155f97c20f71829ead2817d1602201f23f7e17f6928734601c5d8613431eed5c90aa41c3106e8c1cb02ce32aacb5d83473044022017da96dfb0eb4061fa0162dc6fa6b2e07ecc5040ab5e6cb07be59838460b3e58022079371ffc95002cc1dc2891ec38198c9c25aca8164304fe114f1b55e2ffd1ddd501008876a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c820120876475527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae67a914b43e1b38138a41b37f7cd9a1d274bc63e3a9b5d188ac6851b27568f6010000"
            },
            {
                "RemoteSigHex": "304402202d2d9681409b0a0987bd4a268ffeb112df85c4c988ac2a3a2475cb00a61912c302206aa4f4d1388b7d3282bc847871af3cca30766cc8f1064e3a41ec7e82221e10f7",
                "ResolutionTxHex": "02000000000101e7f364cf3a554b670767e723ef14b2af7a3eac70bd79dbde9256f384369c062d0300000000010000000122020000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e050047304402202d2d9681409b0a0987bd4a268ffeb112df85c4c988ac2a3a2475cb00a61912c302206aa4f4d1388b7d3282bc847871af3cca30766cc8f1064e3a41ec7e82221e10f78347304402206426d67911aa6ff9b1cb147b093f3f65a37831a86d7c741d999afc0666e1773d022000bb71821650c70ea58d9bcdd03af736c41a5a8159d436c3ee0408a07394dcce012001010101010101010101010101010101010101010101010101010101010101018d76a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c8201208763a9144b6b2e5444c2639cc0fb7bcea5afba3f3cdce23988527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae677502f501b175ac6851b2756800000000"
            },
            {
                "RemoteSigHex": "3045022100f51cdaa525b7d4304548c642bb7945215eb5ae7d32874517cde67ca23ab0a12202206286d59e4b19926c6ac844be6f3ab8149a1ddb9c70f5026b7e83e40a6c08e6e1",
                "ResolutionTxHex": "02000000000101e7f364cf3a554b670767e723ef14b2af7a3eac70bd79dbde9256f384369c062d040000000001000000015d060000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0500483045022100f51cdaa525b7d4304548c642bb7945215eb5ae7d32874517cde67ca23ab0a12202206286d59e4b19926c6ac844be6f3ab8149a1ddb9c70f5026b7e83e40a6c08e6e18348304502210091b16b1ac63b867e7a5ca0344f7b2aa1cdd49d4b72eac86a31e7ec6f069e20640220402bfb571ba3a9c49e3b0061c89303453803d0241059d899222aaac4799b507601008876a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c820120876475527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae67a9148a486ff2e31d6158bf39e2608864d63fefd09d5b88ac6851b27568f7010000"
            },
            {
                "RemoteSigHex": "304402202f058d99cb5a54f90773d43ba4e7a0089efd9f8269ef2da1b85d48a3e230555402205acc4bd6561830867d45cd7b84bba9fa35ad2b345016471c1737142bc99782c4",
                "ResolutionTxHex": "02000000000101e7f364cf3a554b670767e723ef14b2af7a3eac70bd79dbde9256f384369c062d05000000000100000001f2090000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e050047304402202f058d99cb5a54f90773d43ba4e7a0089efd9f8269ef2da1b85d48a3e230555402205acc4bd6561830867d45cd7b84bba9fa35ad2b345016471c1737142bc99782c48347304402202913f9cacea54efd2316cffa91219def9e0e111977216c1e76e9da80befab14f022000a9a69e8f37ebe4a39107ab50fab0dde537334588f8f412bbaca57b179b87a6012004040404040404040404040404040404040404040404040404040404040404048d76a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c8201208763a91418bc1a114ccf9c052d3d23e28d3b0a9d1227434288527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae677502f801b175ac6851b2756800000000"
            }
        ],
        "ExpectedCommitmentTxHex": "02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b80084a010000000000002200202b1b5854183c12d3316565972c4668929d314d81c5dcdbb21cb45fe8a9a8114f4a01000000000000220020e9e86e4823faa62e222ebc858a226636856158f07e69898da3b0d1af0ddb3994d0070000000000002200203e68115ae0b15b8de75b6c6bc9af5ac9f01391544e0870dae443a1e8fe7837ead007000000000000220020fe0598d74fee2205cc3672e6e6647706b4f3099713b4661b62482c3addd04a5eb80b000000000000220020f96d0334feb64a4f40eb272031d07afcb038db56aa57446d60308c9f8ccadef9a00f000000000000220020ce6e751274836ff59622a0d1e07f8831d80bd6730bd48581398bfadd2bb8da9ac0c62d0000000000220020f3394e1e619b0eca1f91be2fb5ab4dfc59ba5b84ebe014ad1d43a564d012994ab88f6a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e040047304402201ce37a44b95213358c20f44404d6db7a6083bea6f58de6c46547ae41a47c9f8202206db1d45be41373e92f90d346381febbea8c78671b28c153e30ad1db3441a94970147304402206208aeb34e404bd052ce3f298dfa832891c9d42caec99fe2a0d2832e9690b94302201b034bfcc6fa9faec667a9b7cbfe0b8d85e954aa239b66277887b5088aff08c301475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220",
        "RemoteSigHex": "304402206208aeb34e404bd052ce3f298dfa832891c9d42caec99fe2a0d2832e9690b94302201b034bfcc6fa9faec667a9b7cbfe0b8d85e954aa239b66277887b5088aff08c3"
    },
    {
        "Name": "commitment tx with five outputs untrimmed (minimum feerate)",
        "LocalBalance": 6988000000,
        "RemoteBalance": 3000000000,
        "FeePerKw": 2061,
        "UseTestHtlcs": true,
        "HtlcDescs": [
            {
                "RemoteSigHex": "3045022100e10744f572a2cd1d787c969e894b792afaed21217ee0480df0112d2fa3ef96ea02202af4f66eb6beebc36d8e98719ed6b4be1b181659fcb561fc491d8cfebff3aa85",
                "ResolutionTxHex": "02000000000101cf32732fe2d1387ed4e2335f69ddd3c0f337dabc03269e742531f89d35e161d10200000000010000000174020000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0500483045022100e10744f572a2cd1d787c969e894b792afaed21217ee0480df0112d2fa3ef96ea02202af4f66eb6beebc36d8e98719ed6b4be1b181659fcb561fc491d8cfebff3aa8583483045022100c3dc3ea50a0ca20e350f97b50c52c5514717cfa36cb9600918caac5cb556842b022049af018d676dde0c8e28ecf325f3ff5c1594261c4f7511d501f9d62d0594d2a201008876a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c820120876475527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae67a914b43e1b38138a41b37f7cd9a1d274bc63e3a9b5d188ac6851b27568f6010000"
            },
            {
                "RemoteSigHex": "3045022100e1f51fb72fec604b029b348a3bb6363454e1869f5b1e24fd736f860c8039f8070220030a2c90186437d8c9b47d4897798c024521b1274991c4cdc125970b346094b1",
                "ResolutionTxHex": "02000000000101cf32732fe2d1387ed4e2335f69ddd3c0f337dabc03269e742531f89d35e161d1030000000001000000015c060000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0500483045022100e1f51fb72fec604b029b348a3bb6363454e1869f5b1e24fd736f860c8039f8070220030a2c90186437d8c9b47d4897798c024521b1274991c4cdc125970b346094b183483045022100ec7ade6037e531629f24390ca9713782a04d648065d17fbe6b015981cdb296c202202d61049a6ecba2fb5314f3edcda2361cad187a89bea6e5d15185354d80c0c08501008876a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c820120876475527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae67a9148a486ff2e31d6158bf39e2608864d63fefd09d5b88ac6851b27568f7010000"
            },
            {
                "RemoteSigHex": "304402203479f81a1d83c516957679dc98bf91d35deada967739a8e3869e3e8db08246130220053c8e154b97e3019048dcec3d51bfaf396f36861fbda6d33f0e2a57155c8b9f",
                "ResolutionTxHex": "02000000000101cf32732fe2d1387ed4e2335f69ddd3c0f337dabc03269e742531f89d35e161d104000000000100000001f1090000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e050047304402203479f81a1d83c516957679dc98bf91d35deada967739a8e3869e3e8db08246130220053c8e154b97e3019048dcec3d51bfaf396f36861fbda6d33f0e2a57155c8b9f83483045022100a558eb5caa04e35a4417c1f0123ac12eec5f6badee28f5764dc6b69486e594f802201589b12784e242f205832d2d032149bd4e79433ec304c05394241fc7dcba5a71012004040404040404040404040404040404040404040404040404040404040404048d76a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c8201208763a91418bc1a114ccf9c052d3d23e28d3b0a9d1227434288527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae677502f801b175ac6851b2756800000000"
            }
        ],
        "ExpectedCommitmentTxHex": "02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b80074a010000000000002200202b1b5854183c12d3316565972c4668929d314d81c5dcdbb21cb45fe8a9a8114f4a01000000000000220020e9e86e4823faa62e222ebc858a226636856158f07e69898da3b0d1af0ddb3994d0070000000000002200203e68115ae0b15b8de75b6c6bc9af5ac9f01391544e0870dae443a1e8fe7837eab80b000000000000220020f96d0334feb64a4f40eb272031d07afcb038db56aa57446d60308c9f8ccadef9a00f000000000000220020ce6e751274836ff59622a0d1e07f8831d80bd6730bd48581398bfadd2bb8da9ac0c62d0000000000220020f3394e1e619b0eca1f91be2fb5ab4dfc59ba5b84ebe014ad1d43a564d012994a18916a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e040047304402204ab07c659412dd2cd6043b1ad811ab215e901b6b5653e08cb3d2fe63d3e3dc57022031c7b3d130f9380ef09581f4f5a15cb6f359a2e0a597146b96c3533a26d6f4cd01483045022100a2faf2ad7e323b2a82e07dc40b6847207ca6ad7b089f2c21dea9a4d37e52d59d02204c9480ce0358eb51d92a4342355a97e272e3cc45f86c612a76a3fe32fc3c4cb401475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220",
        "RemoteSigHex": "3045022100a2faf2ad7e323b2a82e07dc40b6847207ca6ad7b089f2c21dea9a4d37e52d59d02204c9480ce0358eb51d92a4342355a97e272e3cc45f86c612a76a3fe32fc3c4cb4"
    },
    {
        "Name": "commitment tx with five outputs untrimmed (maximum feerate)",
        "LocalBalance": 6988000000,
        "RemoteBalance": 3000000000,
        "FeePerKw": 2184,
        "UseTestHtlcs": true,
        "HtlcDescs": [
            {
                "RemoteSigHex": "304402202e03ba1390998b3487e9a7fefcb66814c09abea0ef1bcc915dbaefbcf310569a02206bd10493a105ac69048e9bcedcb8e3301ef81b55018d911a4afd297297f98d30",
                "ResolutionTxHex": "020000000001015b03043e20eb467029305a22af4c3b915e793743f192c5d225cf1d3c6e8c03010200000000010000000122020000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e050047304402202e03ba1390998b3487e9a7fefcb66814c09abea0ef1bcc915dbaefbcf310569a02206bd10493a105ac69048e9bcedcb8e3301ef81b55018d911a4afd297297f98d308347304402200c3952ca04be0c60dcc0b7873a0829f560607524943554ae4a27d8d967166199022021a68657b88e22f9bf9ac6065be412685aff643d17049f04f2e99e86197dabb101008876a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c820120876475527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae67a914b43e1b38138a41b37f7cd9a1d274bc63e3a9b5d188ac6851b27568f6010000"
            },
            {
                "RemoteSigHex": "304402201f8a6adda2403bc400c919ea69d72d315337291e00d02cde085ea32953dbc50002202d65230da98df7af8ebefd2b60b457d0945232988ee2d7460a94a77d414a9acc",
                "ResolutionTxHex": "020000000001015b03043e20eb467029305a22af4c3b915e793743f192c5d225cf1d3c6e8c0301030000000001000000010a060000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e050047304402201f8a6adda2403bc400c919ea69d72d315337291e00d02cde085ea32953dbc50002202d65230da98df7af8ebefd2b60b457d0945232988ee2d7460a94a77d414a9acc83483045022100ea69c9273b8914ac62b5b7082d6ac1da2b7b065ebf2ef3cd6403f5305ce3f26802203d98736ea97638895a898dfcc5ee0d0c55eb496b3964df0bb25d223688ea8b8701008876a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c820120876475527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae67a9148a486ff2e31d6158bf39e2608864d63fefd09d5b88ac6851b27568f7010000"
            },
            {
                "RemoteSigHex": "3045022100ea6e4c9b8f56dd9cf5799492a201cdd65b8bc9bc089c3cff34107896ae313f90022034760f7760975cc68e8917a7f62894e25583da7be11af557c4fc402661d0cbf8",
                "ResolutionTxHex": "020000000001015b03043e20eb467029305a22af4c3b915e793743f192c5d225cf1d3c6e8c0301040000000001000000019b090000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0500483045022100ea6e4c9b8f56dd9cf5799492a201cdd65b8bc9bc089c3cff34107896ae313f90022034760f7760975cc68e8917a7f62894e25583da7be11af557c4fc402661d0cbf8834730440220717012f2f7ef6cac590aaf66c2109132c93ffba245959ac62d82e394ba80191302203f00fd9cb37c92c6b0ad4b33e62c3e55b04e5c2cfa0adcca5a9bc49774eeca8a012004040404040404040404040404040404040404040404040404040404040404048d76a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c8201208763a91418bc1a114ccf9c052d3d23e28d3b0a9d1227434288527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae677502f801b175ac6851b2756800000000"
            }
        ],
        "ExpectedCommitmentTxHex": "02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b80074a010000000000002200202b1b5854183c12d3316565972c4668929d314d81c5dcdbb21cb45fe8a9a8114f4a01000000000000220020e9e86e4823faa62e222ebc858a226636856158f07e69898da3b0d1af0ddb3994d0070000000000002200203e68115ae0b15b8de75b6c6bc9af5ac9f01391544e0870dae443a1e8fe7837eab80b000000000000220020f96d0334feb64a4f40eb272031d07afcb038db56aa57446d60308c9f8ccadef9a00f000000000000220020ce6e751274836ff59622a0d1e07f8831d80bd6730bd48581398bfadd2bb8da9ac0c62d0000000000220020f3394e1e619b0eca1f91be2fb5ab4dfc59ba5b84ebe014ad1d43a564d012994a4f906a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e04004730440220555c05261f72c5b4702d5c83a608630822b473048724b08640d6e75e345094250220448950b74a96a56963928ba5db8b457661a742c855e69d239b3b6ab73de307a301473044022013d326f80ff7607cf366c823fcbbcb7a2b10322484825f151e6c4c756af24b8f02201ba05b9d8beb7cea2947f9f4d9e03f90435e93db2dd48b32eb9ca3f3dd042c7901475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220",
        "RemoteSigHex": "3044022013d326f80ff7607cf366c823fcbbcb7a2b10322484825f151e6c4c756af24b8f02201ba05b9d8beb7cea2947f9f4d9e03f90435e93db2dd48b32eb9ca3f3dd042c79"
    },
    {
        "Name": "commitment tx with four outputs untrimmed (minimum feerate)",
        "LocalBalance": 6988000000,
        "RemoteBalance": 3000000000,
        "FeePerKw": 2185,
        "UseTestHtlcs": true,
        "HtlcDescs": [
            {
                "RemoteSigHex": "304502210094480e38afb41d10fae299224872f19c53abe23c7033a1c0642c48713e7863a10220726dd9456407682667dc4bd9c66975acb3744961770b5002f7eb9c0df9ef2f3e",
                "ResolutionTxHex": "02000000000101ac13a7715f80b8e52dda43c6929cade5521bdced3a405da02b443f1ffb1e33cc0200000000010000000109060000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e050048304502210094480e38afb41d10fae299224872f19c53abe23c7033a1c0642c48713e7863a10220726dd9456407682667dc4bd9c66975acb3744961770b5002f7eb9c0df9ef2f3e8347304402203148dac61513dc0361738cba30cb341a1e580f8acd5ab0149bf65bd670688cf002207e5d9a0fcbbea2c263bc714fa9e9c44d7f582ea447f366119fc614a23de32f1f01008876a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c820120876475527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae67a9148a486ff2e31d6158bf39e2608864d63fefd09d5b88ac6851b27568f7010000"
            },
            {
                "RemoteSigHex": "304402200dbde868dbc20c6a2433fe8979ba5e3f966b1c2d1aeb615f1c42e9c938b3495402202eec5f663c8b601c2061c1453d35de22597c137d1907a2feaf714d551035cb6e",
                "ResolutionTxHex": "02000000000101ac13a7715f80b8e52dda43c6929cade5521bdced3a405da02b443f1ffb1e33cc030000000001000000019a090000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e050047304402200dbde868dbc20c6a2433fe8979ba5e3f966b1c2d1aeb615f1c42e9c938b3495402202eec5f663c8b601c2061c1453d35de22597c137d1907a2feaf714d551035cb6e83483045022100b896bded41d7feac7af25c19e35c53037c53b50e73cfd01eb4ba139c7fdf231602203a3be049d3d89396c4dc766d82ce31e237da8bc3a93e2c7d35992d1932d9cfeb012004040404040404040404040404040404040404040404040404040404040404048d76a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c8201208763a91418bc1a114ccf9c052d3d23e28d3b0a9d1227434288527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae677502f801b175ac6851b2756800000000"
            }
        ],
        "ExpectedCommitmentTxHex": "02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b80064a010000000000002200202b1b5854183c12d3316565972c4668929d314d81c5dcdbb21cb45fe8a9a8114f4a01000000000000220020e9e86e4823faa62e222ebc858a226636856158f07e69898da3b0d1af0ddb3994b80b000000000000220020f96d0334feb64a4f40eb272031d07afcb038db56aa57446d60308c9f8ccadef9a00f000000000000220020ce6e751274836ff59622a0d1e07f8831d80bd6730bd48581398bfadd2bb8da9ac0c62d0000000000220020f3394e1e619b0eca1f91be2fb5ab4dfc59ba5b84ebe014ad1d43a564d012994ac5916a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0400483045022100cd8479cfe1edb1e5a1d487391e0451a469c7171e51e680183f19eb4321f20e9b02204eab7d5a6384b1b08e03baa6e4d9748dfd2b5ab2bae7e39604a0d0055bbffdd501473044022040f63a16148cf35c8d3d41827f5ae7f7c3746885bb64d4d1b895892a83812b3e02202fcf95c2bf02c466163b3fa3ced6a24926fbb4035095a96842ef516e86ba54c001475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220",
        "RemoteSigHex": "3044022040f63a16148cf35c8d3d41827f5ae7f7c3746885bb64d4d1b895892a83812b3e02202fcf95c2bf02c466163b3fa3ced6a24926fbb4035095a96842ef516e86ba54c0"
    },
    {
        "Name": "commitment tx with four outputs untrimmed (maximum feerate)",
        "LocalBalance": 6988000000,
        "RemoteBalance": 3000000000,
        "FeePerKw": 3686,
        "UseTestHtlcs": true,
        "HtlcDescs": [
            {
                "RemoteSigHex": "304402202cfe6618926ca9f1574f8c4659b425e9790b4677ba2248d77901290806130ffe02204ab37bb0287abcdb8b750b018d41a09effe37cb65ff801fa70d3f1a416599841",
                "ResolutionTxHex": "020000000001012c32e55722e4b96324d8e5b398d583a20780b25202816adc32dc3157dee731c90200000000010000000122020000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e050047304402202cfe6618926ca9f1574f8c4659b425e9790b4677ba2248d77901290806130ffe02204ab37bb0287abcdb8b750b018d41a09effe37cb65ff801fa70d3f1a41659984183473044022030b318139715e3b34f19be852cc01c1c0e1599e8b926a73df2bfb70dd186ddee022062a2b7398aed9f563b4014da04a1a99debd0ff663ceece68a547df5982dc2d7201008876a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c820120876475527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae67a9148a486ff2e31d6158bf39e2608864d63fefd09d5b88ac6851b27568f7010000"
            },
            {
                "RemoteSigHex": "30440220687af8544d335376620a6f4b5412bfd0da48de047c1785674f26e669d4a3ff82022058591c1e3a6c50017427d38a8f756eb685bdab88ec73838eed3530048861f9d5",
                "ResolutionTxHex": "020000000001012c32e55722e4b96324d8e5b398d583a20780b25202816adc32dc3157dee731c90300000000010000000176050000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e05004730440220687af8544d335376620a6f4b5412bfd0da48de047c1785674f26e669d4a3ff82022058591c1e3a6c50017427d38a8f756eb685bdab88ec73838eed3530048861f9d5834730440220109f1a62b5a13d28d5b7634dd7693b1d5994eb404c4bb4a9a80aa540d3984d170220307251107ff8499a23e99abce7dda4f1c707c98abddb9405a83de0081cde8ace012004040404040404040404040404040404040404040404040404040404040404048d76a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c8201208763a91418bc1a114ccf9c052d3d23e28d3b0a9d1227434288527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae677502f801b175ac6851b2756800000000"
            }
        ],
        "ExpectedCommitmentTxHex": "02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b80064a010000000000002200202b1b5854183c12d3316565972c4668929d314d81c5dcdbb21cb45fe8a9a8114f4a01000000000000220020e9e86e4823faa62e222ebc858a226636856158f07e69898da3b0d1af0ddb3994b80b000000000000220020f96d0334feb64a4f40eb272031d07afcb038db56aa57446d60308c9f8ccadef9a00f000000000000220020ce6e751274836ff59622a0d1e07f8831d80bd6730bd48581398bfadd2bb8da9ac0c62d0000000000220020f3394e1e619b0eca1f91be2fb5ab4dfc59ba5b84ebe014ad1d43a564d012994a29896a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0400483045022100c268496aad5c3f97f25cf41c1ba5483a12982de29b222051b6de3daa2229413b02207f3c82d77a2c14f0096ed9bb4c34649483bb20fa71f819f71af44de6593e8bb2014730440220784485cf7a0ad7979daf2c858ffdaf5298d0020cea7aea466843e7948223bd9902206031b81d25e02a178c64e62f843577fdcdfc7a1decbbfb54cd895de692df85ca01475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220",
        "RemoteSigHex": "30440220784485cf7a0ad7979daf2c858ffdaf5298d0020cea7aea466843e7948223bd9902206031b81d25e02a178c64e62f843577fdcdfc7a1decbbfb54cd895de692df85ca"
    },
    {
        "Name": "commitment tx with three outputs untrimmed (minimum feerate)",
        "LocalBalance": 6988000000,
        "RemoteBalance": 3000000000,
        "FeePerKw": 3687,
        "UseTestHtlcs": true,
        "HtlcDescs": [
            {
                "RemoteSigHex": "3045022100b287bb8e079a62dcb3aaa8b6c67c0f434a87ebf64ab0bcfb2fc14b55576b859f02206d37c2eb5fd04cfc9eb0534c76a28a98da251b84a931377cce307af39dfaed74",
                "ResolutionTxHex": "02000000000101542562b326c08e3a076d9cfca2be175041366591da334d8d513ff1686fd95a600200000000010000000175050000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0500483045022100b287bb8e079a62dcb3aaa8b6c67c0f434a87ebf64ab0bcfb2fc14b55576b859f02206d37c2eb5fd04cfc9eb0534c76a28a98da251b84a931377cce307af39dfaed7483483045022100a497c64faea286ec4221f48628086dc6403fd7b60a23c4176e8ebbca15ae70dc0220754e20e968e96cf6421fd2a672c8c26d3bc6e19218cfc8fc2aa51fce026c14b1012004040404040404040404040404040404040404040404040404040404040404048d76a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c8201208763a91418bc1a114ccf9c052d3d23e28d3b0a9d1227434288527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae677502f801b175ac6851b2756800000000"
            }
        ],
        "ExpectedCommitmentTxHex": "02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b80054a010000000000002200202b1b5854183c12d3316565972c4668929d314d81c5dcdbb21cb45fe8a9a8114f4a01000000000000220020e9e86e4823faa62e222ebc858a226636856158f07e69898da3b0d1af0ddb3994a00f000000000000220020ce6e751274836ff59622a0d1e07f8831d80bd6730bd48581398bfadd2bb8da9ac0c62d0000000000220020f3394e1e619b0eca1f91be2fb5ab4dfc59ba5b84ebe014ad1d43a564d012994aa28b6a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0400483045022100c970799bcb33f43179eb43b3378a0a61991cf2923f69b36ef12548c3df0e6d500220413dc27d2e39ee583093adfcb7799be680141738babb31cc7b0669a777a31f5d01483045022100ad6c71569856b2d7ff42e838b4abe74a713426b37f22fa667a195a4c88908c6902202b37272b02a42dc6d9f4f82cab3eaf84ac882d9ed762859e1e75455c2c22837701475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220",
        "RemoteSigHex": "3045022100ad6c71569856b2d7ff42e838b4abe74a713426b37f22fa667a195a4c88908c6902202b37272b02a42dc6d9f4f82cab3eaf84ac882d9ed762859e1e75455c2c228377"
    },
    {
        "Name": "commitment tx with three outputs untrimmed (maximum feerate)",
        "LocalBalance": 6988000000,
        "RemoteBalance": 3000000000,
        "FeePerKw": 4893,
        "UseTestHtlcs": true,
        "HtlcDescs": [
            {
                "RemoteSigHex": "30450221008db80f8531104820b3e894492b4463f074f965b542e1b5c153ddfb108a5ea642022030b203d857a2b3581c2087a7bf17c95d04fadc1c6cdae88c620477f2dccb1ee4",
                "ResolutionTxHex": "02000000000101d515a15e9175fd315bb8d4e768f28684801a9e5a9acdfeba34f7b3b3b3a9ba1d0200000000010000000122020000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e05004830450221008db80f8531104820b3e894492b4463f074f965b542e1b5c153ddfb108a5ea642022030b203d857a2b3581c2087a7bf17c95d04fadc1c6cdae88c620477f2dccb1ee483483045022100e5fbae857c47dbfc050a05924bd449fc9804798bd6442002c578437dc34450810220296589bc387645512345299e307116aaac4ce9fc752abcd1936b802d03526312012004040404040404040404040404040404040404040404040404040404040404048d76a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c8201208763a91418bc1a114ccf9c052d3d23e28d3b0a9d1227434288527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae677502f801b175ac6851b2756800000000"
            }
        ],
        "ExpectedCommitmentTxHex": "02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b80054a010000000000002200202b1b5854183c12d3316565972c4668929d314d81c5dcdbb21cb45fe8a9a8114f4a01000000000000220020e9e86e4823faa62e222ebc858a226636856158f07e69898da3b0d1af0ddb3994a00f000000000000220020ce6e751274836ff59622a0d1e07f8831d80bd6730bd48581398bfadd2bb8da9ac0c62d0000000000220020f3394e1e619b0eca1f91be2fb5ab4dfc59ba5b84ebe014ad1d43a564d012994a87856a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e04004730440220086288faceab47461eb2d808e9e9b0cb3ffc24a03c2f18db7198247d38f10e58022031d1c2782a58c8c6ce187d0019eb47a83babdf3040e2caff299ab48f7e12b1fa01483045022100a8771147109e4d3f44a5976c3c3de98732bbb77308d21444dbe0d76faf06480e02200b4e916e850c3d1f918de87bbbbb07843ffea1d4658dfe060b6f9ccd96d34be801475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220",
        "RemoteSigHex": "3045022100a8771147109e4d3f44a5976c3c3de98732bbb77308d21444dbe0d76faf06480e02200b4e916e850c3d1f918de87bbbbb07843ffea1d4658dfe060b6f9ccd96d34be8"
    },
    {
        "Name": "commitment tx with two outputs untrimmed (minimum feerate)",
        "LocalBalance": 6988000000,
        "RemoteBalance": 3000000000,
        "FeePerKw": 4894,
        "UseTestHtlcs": true,
        "HtlcDescs": [],
        "ExpectedCommitmentTxHex": "02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b80044a010000000000002200202b1b5854183c12d3316565972c4668929d314d81c5dcdbb21cb45fe8a9a8114f4a01000000000000220020e9e86e4823faa62e222ebc858a226636856158f07e69898da3b0d1af0ddb3994c0c62d0000000000220020f3394e1e619b0eca1f91be2fb5ab4dfc59ba5b84ebe014ad1d43a564d012994ad0886a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e04004830450221009f16ac85d232e4eddb3fcd750a68ebf0b58e3356eaada45d3513ede7e817bf4c02207c2b043b4e5f971261975406cb955219fa56bffe5d834a833694b5abc1ce4cfd01483045022100e784a66b1588575801e237d35e510fd92a81ae3a4a2a1b90c031ad803d07b3f3022021bc5f16501f167607d63b681442da193eb0a76b4b7fd25c2ed4f8b28fd35b9501475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220",
        "RemoteSigHex": "3045022100e784a66b1588575801e237d35e510fd92a81ae3a4a2a1b90c031ad803d07b3f3022021bc5f16501f167607d63b681442da193eb0a76b4b7fd25c2ed4f8b28fd35b95"
    },
    {
        "Name": "commitment tx with one output untrimmed (minimum feerate)",
        "LocalBalance": 6988000000,
        "RemoteBalance": 3000000000,
        "FeePerKw": 6216010,
        "UseTestHtlcs": true,
        "HtlcDescs": [],
        "ExpectedCommitmentTxHex": "02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b80024a01000000000000220020e9e86e4823faa62e222ebc858a226636856158f07e69898da3b0d1af0ddb3994c0c62d0000000000220020f3394e1e619b0eca1f91be2fb5ab4dfc59ba5b84ebe014ad1d43a564d012994a04004830450221009ad80792e3038fe6968d12ff23e6888a565c3ddd065037f357445f01675d63f3022018384915e5f1f4ae157e15debf4f49b61c8d9d2b073c7d6f97c4a68caa3ed4c1014830450221008fd5dbff02e4b59020d4cd23a3c30d3e287065fda75a0a09b402980adf68ccda022001e0b8b620cd915ddff11f1de32addf23d81d51b90e6841b2cb8dcaf3faa5ecf01475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220",
        "RemoteSigHex": "30450221008fd5dbff02e4b59020d4cd23a3c30d3e287065fda75a0a09b402980adf68ccda022001e0b8b620cd915ddff11f1de32addf23d81d51b90e6841b2cb8dcaf3faa5ecf"
    }
]
    """

        val format = Json { ignoreUnknownKeys = true }
        val testCases = format.decodeFromString<Array<TestCase>>(raw)
    }
}