package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.utils.sat
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ElectrumMiniWalletTest : LightningTestSuite() {

    private val logger = loggerFactory.newLogger(this::class)

    @Test
    fun `single address with no utxos`() = runSuspendTest(timeout = 15.seconds) {
        val client = connectToMainnetServer()
        val wallet = ElectrumMiniWallet(Block.LivenetGenesisBlock.hash, client, this, logger)
        val address = "bc1qyjmhaptq78vh5j7tnzu7ujayd8sftjahphxppz"
        wallet.addAddress(address)

        val walletState = wallet.walletStateFlow.drop(1).first() // first emitted wallet is empty

        assertEquals(
            expected = WalletState(mapOf(address to WalletState.AddressState(WalletState.AddressMeta.Single, alreadyUsed = false, utxos = emptyList()))),
            actual = walletState
        )

        wallet.stop()
        client.stop()
    }

    @Test
    fun `single address with no utxos -- already used`() = runSuspendTest(timeout = 15.seconds) {
        val client = connectToMainnetServer()
        val wallet = ElectrumMiniWallet(Block.LivenetGenesisBlock.hash, client, this, logger)
        val address = "15NixkHzN4qW5h2kkmUwpUTf5jCEJNCw9o"
        wallet.addAddress(address)

        val walletState = wallet.walletStateFlow.drop(1).first() // first emitted wallet is empty

        assertEquals(
            expected = WalletState(mapOf(address to WalletState.AddressState(WalletState.AddressMeta.Single, alreadyUsed = true, utxos = emptyList()))),
            actual = walletState
        )

        wallet.stop()
        client.stop()
    }

    @Test
    fun `single address with existing utxos`() = runSuspendTest(timeout = 15.seconds) {
        val client = connectToMainnetServer()
        val wallet = ElectrumMiniWallet(Block.LivenetGenesisBlock.hash, client, this, logger)
        wallet.addAddress("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2")

        val walletState = wallet.walletStateFlow.drop(1).first() // first emitted wallet is empty

        // This address has 3 transactions confirmed at block 100 002 and 3 transactions confirmed at block 100 003.
        assertEquals(6, walletState.utxos.size)
        assertEquals(30_000_000.sat, walletState.totalBalance)
        val swapInParams = SwapInParams(minConfirmations = 3, maxConfirmations = 10, refundDelay = 11)

        run {
            val withConf = walletState.withConfirmations(currentBlockHeight = 100_004, swapInParams)
            assertEquals(0, withConf.unconfirmed.size)
            assertEquals(3, withConf.weaklyConfirmed.size)
            assertEquals(3, withConf.deeplyConfirmed.size)
            assertEquals(0, withConf.lockedUntilRefund.size)
            assertEquals(0, withConf.readyForRefund.size)
            assertEquals(15_000_000.sat, withConf.weaklyConfirmed.balance)
            assertEquals(15_000_000.sat, withConf.deeplyConfirmed.balance)
        }
        run {
            val withConf = walletState.withConfirmations(currentBlockHeight = 100_005, swapInParams)
            assertEquals(0, withConf.unconfirmed.size)
            assertEquals(0, withConf.weaklyConfirmed.size)
            assertEquals(6, withConf.deeplyConfirmed.size)
            assertEquals(0, withConf.lockedUntilRefund.size)
            assertEquals(0, withConf.readyForRefund.size)
            assertEquals(30_000_000.sat, withConf.deeplyConfirmed.balance)
        }
        run {
            val withConf = walletState.withConfirmations(currentBlockHeight = 100_011, swapInParams)
            assertEquals(0, withConf.unconfirmed.size)
            assertEquals(0, withConf.weaklyConfirmed.size)
            assertEquals(3, withConf.deeplyConfirmed.size)
            assertEquals(3, withConf.lockedUntilRefund.size)
            assertEquals(0, withConf.readyForRefund.size)
            assertEquals(15_000_000.sat, withConf.deeplyConfirmed.balance)
            assertEquals(15_000_000.sat, withConf.lockedUntilRefund.balance)
        }
        run {
            val withConf = walletState.withConfirmations(currentBlockHeight = 100_012, swapInParams)
            assertEquals(0, withConf.unconfirmed.size)
            assertEquals(0, withConf.weaklyConfirmed.size)
            assertEquals(0, withConf.deeplyConfirmed.size)
            assertEquals(3, withConf.lockedUntilRefund.size)
            assertEquals(3, withConf.readyForRefund.size)
            assertEquals(15_000_000.sat, withConf.lockedUntilRefund.balance)
            assertEquals(15_000_000.sat, withConf.readyForRefund.balance)
        }
        run {
            val withConf = walletState.withConfirmations(currentBlockHeight = 100_013, swapInParams)
            assertEquals(0, withConf.unconfirmed.size)
            assertEquals(0, withConf.weaklyConfirmed.size)
            assertEquals(0, withConf.deeplyConfirmed.size)
            assertEquals(0, withConf.lockedUntilRefund.size)
            assertEquals(6, withConf.readyForRefund.size)
            assertEquals(30_000_000.sat, withConf.readyForRefund.balance)
        }

        wallet.stop()
        client.stop()
    }

    @Test
    fun `multiple addresses`() = runSuspendTest(timeout = 15.seconds) {
        val client = connectToMainnetServer()
        val wallet = ElectrumMiniWallet(Block.LivenetGenesisBlock.hash, client, this, logger)
        wallet.addAddress("16MmJT8VqW465GEyckWae547jKVfMB14P8")
        wallet.addAddress("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2")
        wallet.addAddress("1NHFyu1uJ1UoDjtPjqZ4Et3wNCyMGCJ1qV")

        val walletState = wallet.walletStateFlow.first { it.utxos.size == 11 }

        // this has been checked on the blockchain
        assertEquals(4 + 6 + 1, walletState.utxos.size)
        assertEquals(72_000_000.sat + 30_000_000.sat + 2_000_000.sat, walletState.totalBalance)
        // make sure txid is correct has electrum api is confusing
        assertContains(
            walletState.utxos,
            WalletState.Utxo(
                previousTx = Transaction.read("0100000001758713310361270b5ec4cae9b0196cb84fdb2f174d29f9367ad341963fa83e56010000008b483045022100d7b8759aeffe9d829a5df062420eb25017d7341244e49cfede16136a0c0b8dd2022031b42048e66b1f82f7fa99a22954e2709269838ef587c20118e493ced0d63e21014104b9251638d1475b9c62e1cf03129c835bcd5ab843aa0016412e8b39e3f8f7188d3b59023bce2002a2e409ea070c7070392b65d9ae8c8631ae2672a8fbb4f62bbdffffffff02404b4c00000000001976a9143675767783fdf1922f57ab4bb783f3a88dfa609488ac404b4c00000000001976a9142b6ba7c9d796b75eef7942fc9288edd37c32f5c388ac00000000"),
                outputIndex = 1,
                blockHeight = 100_003,
                txId = TxId("971af80218684017722429be08548d1f30a2f1f220abc064380cbca5cabf7623"),
                addressMeta = WalletState.AddressMeta.Single
            )
        )

        assertEquals(
            expected = setOf(
                Triple("16MmJT8VqW465GEyckWae547jKVfMB14P8", TxId("c1e943938e0bf2e9e6feefe22af0466514a58e9f7ed0f7ada6fd8e6dbeca0742") to 1, 39_000_000.sat),
                Triple("16MmJT8VqW465GEyckWae547jKVfMB14P8", TxId("2cf392ecf573a638f01f72c276c3b097d05eb58f39e165eacc91b8a8df09fbd8") to 0, 12_000_000.sat),
                Triple("16MmJT8VqW465GEyckWae547jKVfMB14P8", TxId("149a098d6261b7f9359a572d797c4a41b62378836a14093912618b15644ba402") to 1, 11_000_000.sat),
                Triple("16MmJT8VqW465GEyckWae547jKVfMB14P8", TxId("2dd9cb7bcebb74b02efc85570a462f22a54a613235bee11d0a2c791342a26007") to 1, 10_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", TxId("71b3dbaca67e9f9189dad3617138c19725ab541ef0b49c05a94913e9f28e3f4e") to 0, 5_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", TxId("21d2eb195736af2a40d42107e6abd59c97eb6cffd4a5a7a7709e86590ae61987") to 0, 5_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", TxId("74d681e0e03bafa802c8aa084379aa98d9fcd632ddc2ed9782b586ec87451f20") to 1, 5_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", TxId("563ea83f9641d37a36f9294d172fdb4fb86c19b0e9cac45e0b27610331138775") to 0, 5_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", TxId("971af80218684017722429be08548d1f30a2f1f220abc064380cbca5cabf7623") to 1, 5_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", TxId("b1ec9c44009147f3cee26caba45abec2610c74df9751fad14074119b5314da21") to 0, 5_000_000.sat),
                Triple("1NHFyu1uJ1UoDjtPjqZ4Et3wNCyMGCJ1qV", TxId("602839d82ac6c9aafd1a20fff5b23e11a99271e7cc238d2e48b352219b2b87ab") to 1, 2_000_000.sat),
            ),
            actual = walletState.utxos.map {
                val txOut = it.previousTx.txOut[it.outputIndex]
                val address = Bitcoin.addressFromPublicKeyScript(Block.LivenetGenesisBlock.hash, txOut.publicKeyScript.toByteArray()).right!!
                Triple(address, it.previousTx.txid to it.outputIndex, txOut.amount)
            }.toSet()
        )

        wallet.stop()
        client.stop()
    }

    @Test
    fun `multiple addresses with generator`() = runSuspendTest(timeout = 15.seconds) {
        val client = connectToMainnetServer()
        val wallet = ElectrumMiniWallet(Block.LivenetGenesisBlock.hash, client, this, logger)
        wallet.addAddressGenerator {
            when (it) {
                0 -> "16MmJT8VqW465GEyckWae547jKVfMB14P8" // has utxos
                1 -> "15NixkHzN4qW5h2kkmUwpUTf5jCEJNCw9o" // no utxo, but already used
                2 -> "16hBdohKfkzPnDAA1ne3RPX8jjNhWW3eox" // no utxo, but already used
                3 -> "14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2" // has utxos
                4 -> "1NHFyu1uJ1UoDjtPjqZ4Et3wNCyMGCJ1qV" // has utxos
                5 -> "12yyz3bCKjUoQQPaYbrmzYVM4h7bYa9QYj" // no utxo, but already used
                6 -> "1NtgeYfAGMwt1vqdJrNTRuPu4Hnqpd4sKX" // fresh unused address
                else -> error("should not go that far")
            }
        }

        val walletState = wallet.walletStateFlow.drop(1).first() // first emitted wallet is empty

        // this has been checked on the blockchain
        assertEquals(4 + 6 + 1, walletState.utxos.size)
        assertEquals(72_000_000.sat + 30_000_000.sat + 2_000_000.sat, walletState.totalBalance)
        // make sure txid is correct as electrum api is confusing
        walletState.utxos.forEach { assertEquals(it.txId, it.previousTx.txid) }
        assertContains(
            walletState.utxos,
            WalletState.Utxo( // utxo for 14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2
                txId = TxId("971af80218684017722429be08548d1f30a2f1f220abc064380cbca5cabf7623"),
                previousTx = Transaction.read("0100000001758713310361270b5ec4cae9b0196cb84fdb2f174d29f9367ad341963fa83e56010000008b483045022100d7b8759aeffe9d829a5df062420eb25017d7341244e49cfede16136a0c0b8dd2022031b42048e66b1f82f7fa99a22954e2709269838ef587c20118e493ced0d63e21014104b9251638d1475b9c62e1cf03129c835bcd5ab843aa0016412e8b39e3f8f7188d3b59023bce2002a2e409ea070c7070392b65d9ae8c8631ae2672a8fbb4f62bbdffffffff02404b4c00000000001976a9143675767783fdf1922f57ab4bb783f3a88dfa609488ac404b4c00000000001976a9142b6ba7c9d796b75eef7942fc9288edd37c32f5c388ac00000000"),
                outputIndex = 1,
                blockHeight = 100_003,
                addressMeta = WalletState.AddressMeta.Derived(3)
            )
        )
        assertContains(
            walletState.addresses.toList(),
            "12yyz3bCKjUoQQPaYbrmzYVM4h7bYa9QYj" to WalletState.AddressState(
                meta = WalletState.AddressMeta.Derived(5),
                alreadyUsed = true,
                utxos = emptyList()
            )
        )
        assertContains(
            walletState.addresses.toList(),
            "1NtgeYfAGMwt1vqdJrNTRuPu4Hnqpd4sKX" to WalletState.AddressState(
                meta = WalletState.AddressMeta.Derived(6),
                alreadyUsed = false,
                utxos = emptyList()
            )
        )

        assertEquals(
            expected = setOf(
                Triple("16MmJT8VqW465GEyckWae547jKVfMB14P8", TxId("c1e943938e0bf2e9e6feefe22af0466514a58e9f7ed0f7ada6fd8e6dbeca0742") to 1, 39_000_000.sat),
                Triple("16MmJT8VqW465GEyckWae547jKVfMB14P8", TxId("2cf392ecf573a638f01f72c276c3b097d05eb58f39e165eacc91b8a8df09fbd8") to 0, 12_000_000.sat),
                Triple("16MmJT8VqW465GEyckWae547jKVfMB14P8", TxId("149a098d6261b7f9359a572d797c4a41b62378836a14093912618b15644ba402") to 1, 11_000_000.sat),
                Triple("16MmJT8VqW465GEyckWae547jKVfMB14P8", TxId("2dd9cb7bcebb74b02efc85570a462f22a54a613235bee11d0a2c791342a26007") to 1, 10_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", TxId("71b3dbaca67e9f9189dad3617138c19725ab541ef0b49c05a94913e9f28e3f4e") to 0, 5_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", TxId("21d2eb195736af2a40d42107e6abd59c97eb6cffd4a5a7a7709e86590ae61987") to 0, 5_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", TxId("74d681e0e03bafa802c8aa084379aa98d9fcd632ddc2ed9782b586ec87451f20") to 1, 5_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", TxId("563ea83f9641d37a36f9294d172fdb4fb86c19b0e9cac45e0b27610331138775") to 0, 5_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", TxId("971af80218684017722429be08548d1f30a2f1f220abc064380cbca5cabf7623") to 1, 5_000_000.sat),
                Triple("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2", TxId("b1ec9c44009147f3cee26caba45abec2610c74df9751fad14074119b5314da21") to 0, 5_000_000.sat),
                Triple("1NHFyu1uJ1UoDjtPjqZ4Et3wNCyMGCJ1qV", TxId("602839d82ac6c9aafd1a20fff5b23e11a99271e7cc238d2e48b352219b2b87ab") to 1, 2_000_000.sat),
            ),
            actual = walletState.utxos.map {
                val txOut = it.previousTx.txOut[it.outputIndex]
                val address = Bitcoin.addressFromPublicKeyScript(Block.LivenetGenesisBlock.hash, txOut.publicKeyScript.toByteArray()).right!!
                Triple(address, it.previousTx.txid to it.outputIndex, txOut.amount)
            }.toSet()
        )

        wallet.stop()
        client.stop()
    }

    //@Test
    fun `perf test generator`() = runSuspendTest(timeout = 45.seconds) {
        val client = connectToMainnetServer()
        val wallet = ElectrumMiniWallet(Block.LivenetGenesisBlock.hash, client, this, logger)
        val addresses = listOf(
            "114uCKBNBxp4g1fZrcka2M69oV4BVePkad",
            "12CY87ECoGY8LJ6VvSUUYjqpHyYPHJnqj1",
            "12VzzUpnDwWpWxChB6yR4rLLXqLQ5m7sU5",
            "1322UMyRef9rZ87vkciVsgnuBTwBmCPFRe",
            "137hvJS73Sztn9DUz8RchURHpkWXSmik8G",
            "13BVsQidbmZhaMRgXe8V7sAV5KBwats8rC",
            "13Z3AAqKaTQDFxWC4v9Fu3Qvpvh96YSAVM",
            "13dCDhE9tfyVjDNHtM7Fs9jb5JCfrPVjn3",
            "14348op8fK4W6W4nX1J9yZUGokJrRYECik",
            "145UHBQAmGRpdsXVKhdHtFndnJ8XN8fLjq",
            "14SanaYZzhyD5FHXxnXauayxfQBdcXLd5U",
            "15NixkHzN4qW5h2kkmUwpUTf5jCEJNCw9o",
            "15V8o9ejz5voGj3WK5Y1mDm3FZCDuquss2",
            "162Tz9M9GN6F6v8i5eSdULffP36QZh8ZUN",
            "16cXz1ecwDk65cawVEU6VmJ4ow64jRaL9n",
            "16gBiciGuMRbqvbPvv2cA6GmE22yo6LuRj",
            "16nFkG1Y38ufZxZdA9F5gRppAQbe6ymMux",
            "172r9DykshKtD5AsXptYuB4f8j8xr1whJx",
            "17Eo7qeohz9igYsg6haip7HHfd9irQaGLp",
            "17YiV3fymmrsHTY5ZgWGvuHiBWgTDxweo1",
            "17eTgKJkvkGGQpuXynCtPFw4aq1vZZhbiC",
            "17rVcPa5eSBKMogUDtSATsvD31XN7TK727",
            "182dAVj6n99LiVjL65GxBHDaoqL4D4eupx",
            "187vwp8PtBiuMwcTeJcZKyM8LubBjU3ytu",
            "18EarThzGxJPmjik8dcnfCiczdQ5VFpLAv",
            "18HzHv4kPxJ1nSG1b8d67dB9TRy9DAKBVu",
            "18TG9ubxK3MU6DrCFgKiv3XTAE6SH3aDXA",
            "18jnQ3E4P3yA7mBMizmnN1h7TStojwufQ1",
            "18qBZXpyzvf1RSUPAd6AYHZvKR83PUEwzx",
            "18vEXh45QgF4KUZhbTAu9baq188ETMfNBj",
            "19B2FMsUevkGq8W4ZapS6LPvcotDNhrkaA",
            "19QspZMDQg6r5n1JM5VP7DaTwq3HD3ZSq4",
            "1A5YxdfPY4JJWqwBcCeZY2FhiQ2afTbQBa",
            "1AYx4g8BSeVkCmLSGD1VeVeAseCzLkkoHj",
            "1Ah3eUSz3a7hWQQHbTKC4GYicVZdwwaj3y",
            "1C2WyWKSEGUCzw9HrJecJtxBiaEAaaH45b",
            "1C6vvX6mEbSUWCaEdKFE3Z5HWiyWzveEz4",
            "1CFre5K8Htx4Emao3D3mia813vP32EtCGB",
            "1CMvZqzZEfQVSwWhuYT1LL4iLze2bFFBA",
            "1CUjJSYzfeLsRRTC1mogZ9vmqwqQGJarxr",
            "1CbPns3oQZ7fzEVHmj32nM2CA1tJNqzNxf",
            "1CiYN9JPAoHt7oumuEPqpRDWeDDTFdqsrW",
            "1CjHxcwoLSGQCJZk4U9XWuvfpYF7F4ppDG",
            "1D49cHVy6m6iA5Ba7KT3sAjKGKEPxNJ6Ab",
            "1D7qwTKonm3ocoUkb9wcCxX3nwrhfLZSG2",
            "1DfS5wjxWo46U8J9okABrVAXs2UyuCCSZW",
            "1ECENFEGVQfNkchkGD66TDWrhmCfw4VmMQ",
            "1EjqA74tRDpu5YUEwk2yann6oQvcqeqUrt",
            "1F39Up1U9Wz9a9YpQRQY7Vo4SXVkY17hoq",
            "1FHQkdk3A4JXBK1GTGGHK8h1n6brFHfY3E",
            "1FPXH5QCZHBbjN7C2P8Mi2pG3j96GPuYQE",
            "1FrXcD6SczqhUXFcihQaR6gGFm9PFWSrTu",
            "1Fwy2Kz7hHA5euKEUCmafjAomy8bVYXap7",
            "1G9tzuNYXys8wJeJQzUfYQh13iHaWUmPVa",
            "1GDxkebMi5hTcpF619r3VW5KRzLa9N2qPM",
            "1GYgimY73Zqt3RzByhqbWGwJo5WJ3NpLRs",
            "1GtUbGFBNav9GQoFE8RakHmApRW4y2a6v6",
            "1HKwVwbnQNphz8JsEp9kJoZ9LZxQFETzQQ",
            "1HLBi58BQvBvLvmkFCiJdxdUUapGiFMWEL",
            "1HMyzjG9Xk5A98oDgDcDuZcDypRPvgzFhk",
            "1HSS3RVVDLzACWU4VYXKXWcLhaytU7rZmH",
            "1HbS9EWzEMQUP5aEtEHbYaA75QT8e2syJo",
            "1HjfdwDEngtPgoGyTChwqDAb94NFUNAFU1",
            "1HomrXLp1PSjJ4ztKT41whsLCGunPytV5o",
            "1Hrxj68qi3ma19FrjZxAtF569skNu157AJ",
            "1JPiiKYLeFsoqQs57Fsk1AB2WaaapL4Deu",
            "1JyJkw5bSXfFxLjVr1znKhbohSQu9L5Zej",
            "1K2N6ra6FR2mQsCpXTC8aC7Ltte2FvGRav",
            "1K6JwqW3D4uF54X3XKVMK83TcA6USj4EAa",
            "1KBGvNUkhZeVFkMXm63cTrMcqwXMomBhSF",
            "1KGpPb3UtfVAsCCWVHH7xDNkJ9o3dkcuLG",
            "1KaEafBf6rnNhEVfA9MMz97HyBfEb1n92",
            "1KfzGnxQ2DVAtuDvaeQfEAUsGaJeEdjGdx",
            "1L6azLTYi4jBmFMVDGMdD6wVC6c2A5VzY6",
            "1LTrAV1sF75F7FT4TyLszwqpBoGPoaQVkj",
            "1LcgGunavVU4ssbDfpyy65oqrn1sb4mgfW",
            "1LjE3iQ3G1DzdpJKvbfDUAzHx1DR2ZF3YX",
            "1LqTafjDWgEmdi4fNX1oK9NPmR3dCZ2WrF",
            "1MFzTqdiSrRsxP3wWFvVuBMvGHsuy1tW82",
            "1MGwaMGvCi7VZ5RBqBPfALFv1JXa1PayAr",
            "1MvpAZgGnWStTXDzgseZJgCjXZZ4k9Ss5W",
            "1NGCVyaJHLrKhPK5pNHvaoVqbsrVUiobcp",
            "1NpCHRwmc7CcKVP1o6kQiRRBf7o8Gtnq8k",
            "1PGucoekd9R5ALVKkPCHzBgww55Lk7K7B2",
            "1PH2tuYqJE5AYRU2YyXJVkKKSsi5u347H7",
            "1QFkC7PTtpNp5k6pdkgjpKBwGQP96akFTE",
            "1X67cVbXYDwETmymUUneGGmstV248vwwe",
            "1eBc1GnsXK7sxPqGpDq4HJyjacgG2R37t",
            "1russ5GJDodxVJY59Au7MDYwA8mRV5EC9",
            "1tFEamDdQV93gmLLQx4s2yFwedLqcNaPa",
            "1tN3AJhBjC4TmE2vw1wmFaD8dfG9Cn2eF",
            "1NtgeYfAGMwt1vqdJrNTRuPu4Hnqpd4sKX"
        )
        wallet.addAddressGenerator {
            if (it < addresses.size) addresses[it] else error("should not go that far")
        }
        wallet.walletStateFlow.drop(1).first() // first emitted wallet is empty
    }

    @Test
    fun `parallel wallets`() = runSuspendTest(timeout = 15.seconds) {
        val client = connectToMainnetServer()
        val wallet1 = ElectrumMiniWallet(Block.LivenetGenesisBlock.hash, client, this, logger)
        val wallet2 = ElectrumMiniWallet(Block.LivenetGenesisBlock.hash, client, this, logger)
        wallet1.addAddress("16MmJT8VqW465GEyckWae547jKVfMB14P8")
        wallet2.addAddress("14xb2HATmkBzrHf4CR2hZczEtjYpTh92d2")

        val walletState1 = wallet1.walletStateFlow.drop(1).first() // first emitted wallet is empty
        val walletState2 = wallet2.walletStateFlow.drop(1).first() // first emitted wallet is empty

        assertEquals(7_200_0000.sat, walletState1.totalBalance)
        assertEquals(3_000_0000.sat, walletState2.totalBalance)

        assertEquals(4, walletState1.utxos.size)
        assertEquals(6, walletState2.utxos.size)

        wallet1.stop()
        wallet2.stop()
        client.stop()
    }
}
