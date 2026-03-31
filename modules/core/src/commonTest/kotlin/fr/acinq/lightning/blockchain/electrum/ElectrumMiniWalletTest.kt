package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
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
        val wallet = ElectrumMiniWallet(Block.LivenetGenesisBlock.hash, client, this, logger, lookAhead = 1u)
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

    /**
     * mock-up client that uses data returned by real testnet3 electrum servers for the last 2 tests (and which is valid only for the seed used in these tests)
     * testnet3 servers are unreliable and caused CI to fail, using
     */
    private val myClient = object : IElectrumClient {
        private val _notifications = MutableSharedFlow<ElectrumSubscriptionResponse>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
        override val notifications: Flow<ElectrumSubscriptionResponse> get() = _notifications.asSharedFlow()

        private val _connectionStatus = MutableStateFlow<ElectrumConnectionStatus>(ElectrumConnectionStatus.Connected(
            version = ServerVersionResponse("test client", "1.5"),
            height = 4896105,
            header = BlockHeader(version=536870912, hashPreviousBlock= BlockHash("cce736cf28346ad0a8f3be5ae6f4237c452e57f3ddd19cf3beac520100000000"), hashMerkleRoot= ByteVector32("9e5a1031cb1882c028d5a33ab4f69a999e046772445426851e4b67536f47cbbf"), time=1774542563, bits=486604799, nonce=3612030529)
        ))
        override val connectionStatus: StateFlow<ElectrumConnectionStatus> get() = _connectionStatus.asStateFlow()

        override suspend fun getTx(txId: TxId): Transaction? {
            val map = mapOf(
                TxId("b80732530b4dee674cef70f8cced56f4054e9fcbc93fd4a991ee21610ab0d944") to Transaction.read("020000000001016220615638f0bf92547ed12ffcdd76770b78c383eb1cb26e9c359b44926196770000000000fdffffff0200bfd7020000000022002014b08e946a07e142fe0560fcfab833b8191658ad375356ba75c6d17ba939a6ba1027000000000000225120073de725bcb32769e273276b7bc651c81e6ebaec92417f347aab47336137513d040047304402204e5f13e2162e54980001abd07ea2d4eb2b09ed1f1b17a976ab8305df38faabc002204a4913860c480fa43e88fc173b1614843dd938db6c67495a033ada2bda8d64b501473044022019f8294c46c2fe094a5102910bb8ac8fc66fc00fc53ab8aa397dda82b788ec79022072fb86d24b25a2a8df53e464ceb3637ec346efb26b77e6f146480f59b9a4f8970147522102341099e2740144b51e92cc7cd40df1ab58340e92ed431b8021c0c018e25432f821031d9ced8d828c234174740824b8adf55b0d42255003c84f12abd5fa0338ee5ede52ae3dac4600"),
                TxId("95b51ef3fad8788bb01f12c3aba2ae292a379a5323509b09568b9d129f44ed0e") to Transaction.read("0200000000010144d9b00a6121ee91a9d43fc9cb9f4e05f456edccf870ef4c67ee4d0b533207b80000000000fdffffff02f82a000000000000225120073de725bcb32769e273276b7bc651c81e6ebaec92417f347aab47336137513d4593d70200000000220020b09e4146fc3fc79cbd2f7154b65f9e78d272436754f6cb688e047563f1e2476c0400483045022100f788b4da889371df4c9aa3dd058cf643f7f167a7f12ffc5ec8cfd284d1332422022005cac09b8d2bb71496dd9772d6c274bee8c4dea107021168bba6d8aa43e21d3f0147304402205efaa3760647e2cf093968e6d69339f09443755e2abfaaf881f6a2dbc5fbd1e402202432f01236814fa473d58bf0bec172de36db1f6dac6fe99b1fda24371b728baa01475221036e0e9d5555a8b59c523335ff273a67f1309f423339d78a71fa06aa31d31395d521038028e2de359e9af37f6a5f488dfb4b750a98816c46471e231c886fd3b6e8f1f752ae3dac4600"),
                TxId("15a0c277b971a0d6127639f8f83e1253a5e6c8b5d82b2a9fcafdd9ba2076b08b") to Transaction.read("020000000001010eed449f129d8b56099b5023539a372a29aea2abc3121fb08b78d8faf31eb5950100000000fdffffff02a263d7020000000022002082d4c2a879c33394a6f2a20afa617c549bec533e9195a7ccacc934793cda9d9ee02e00000000000022512031bb6c16b94cce1e1e42492dc851c6db88f5dd69a8385db991976812c66b6ee2040047304402204893495b75600bdf79a9820835ce504c1b99e807bf1f0da1b4fa91c1539a311502206448f09e4e505eb970af450ff65060f7bbd653eae8de55e79b03778a67d66f6c014730440220176c7761be9b1a61b70304a2d981b062fe1225be0b2f3fea257be21ec1bf7c7e02200b37fe6af57fc73139594918ed65095aede806678d0a780cee78fdc4a31b3895014752210350d8824915cd7e8610022e24efefa77656cdefab79f0faddcee3bba7b51cbd0621039d3af88950c389a9ba0ccb9b7b3a9bf9a8a4fa9f152a90fc30b254a9736a8c4852ae3dac4600")
            )
            return map[txId]
        }

        override suspend fun getHeader(blockHeight: Int): BlockHeader? {
            TODO("Not yet implemented")
        }

        override suspend fun getHeaders(startHeight: Int, count: Int): List<BlockHeader> {
            TODO("Not yet implemented")
        }

        override suspend fun getMerkle(txId: TxId, blockHeight: Int, contextOpt: Transaction?): GetMerkleResponse? {
            TODO("Not yet implemented")
        }

        override suspend fun getScriptHashHistory(scriptHash: ByteVector32): List<TransactionHistoryItem> {
            TODO("Not yet implemented")
        }

        override suspend fun getScriptHashUnspents(scriptHash: ByteVector32): List<UnspentItem> {
            val map = mapOf<ByteVector32, List<UnspentItem>>(
                ByteVector32("ecc39a8fde5bb83b874322ec90ddb6a801f8a3c995e1c6cee43fce09bedce51b") to listOf(
                    UnspentItem(
                        txid = TxId("b80732530b4dee674cef70f8cced56f4054e9fcbc93fd4a991ee21610ab0d944"),
                        outputIndex = 1,
                        value = 10000,
                        blockHeight = 4631616
                    ), UnspentItem(txid = TxId("95b51ef3fad8788bb01f12c3aba2ae292a379a5323509b09568b9d129f44ed0e"), outputIndex = 0, value = 11000, blockHeight = 4631616)
                ),
                ByteVector32("3bf2234b2d4ed1d73125059b3894633984838631672c1457672712a131e2daf0") to listOf(
                    UnspentItem(
                        txid = TxId("15a0c277b971a0d6127639f8f83e1253a5e6c8b5d82b2a9fcafdd9ba2076b08b"),
                        outputIndex = 1,
                        value = 12000,
                        blockHeight = 4631616
                    )
                ),
                ByteVector32("ecc39a8fde5bb83b874322ec90ddb6a801f8a3c995e1c6cee43fce09bedce51b") to listOf(
                    UnspentItem(
                        txid = TxId("b80732530b4dee674cef70f8cced56f4054e9fcbc93fd4a991ee21610ab0d944"),
                        outputIndex = 1,
                        value = 10000,
                        blockHeight = 4631616
                    ), UnspentItem(txid = TxId("95b51ef3fad8788bb01f12c3aba2ae292a379a5323509b09568b9d129f44ed0e"), outputIndex = 0, value = 11000, blockHeight = 4631616)
                ),

                )
            return map[scriptHash]!!
        }

        override suspend fun broadcastTransaction(tx: Transaction): TxId {
            TODO("Not yet implemented")
        }

        override suspend fun estimateFees(confirmations: Int): FeeratePerKw? {
            TODO("Not yet implemented")
        }

        override suspend fun startScriptHashSubscription(scriptHash: ByteVector32): ScriptHashSubscriptionResponse {
            val map = mapOf(
                ByteVector32("ecc39a8fde5bb83b874322ec90ddb6a801f8a3c995e1c6cee43fce09bedce51b") to ScriptHashSubscriptionResponse(scriptHash = scriptHash, status = "ce12f8bd5ee8d5245e2e6f2353df3df5aef5ebcf33e4fb70d68c33414c671d21"),
                ByteVector32("ed0a456a7a5105f80506aed405b5688bcdb035446f9ca17788e89df471f198a5") to ScriptHashSubscriptionResponse(scriptHash = scriptHash, status = null),
                ByteVector32("3bf2234b2d4ed1d73125059b3894633984838631672c1457672712a131e2daf0") to ScriptHashSubscriptionResponse(scriptHash = scriptHash, status = "f58bd3b3bc9c5095e5bc7b00ef984bd69bdbb906e23d132c91568d19c6ca6421"),
                ByteVector32("cd0387a875136c754253c19eb66308770a328cb6fb7acfc8eadf0e46c76e6e6e") to ScriptHashSubscriptionResponse(scriptHash = scriptHash, status = null)
            )
            return map[scriptHash]!!
        }

        override suspend fun startHeaderSubscription(): HeaderSubscriptionResponse {
            TODO("Not yet implemented")
        }
    }

    @OptIn(FlowPreview::class)
    @Test
    fun `derived addresses with gaps`() = runSuspendTest(timeout = 15.seconds) {
        val chain = Chain.Testnet3
        val wallet = ElectrumMiniWallet(chain.chainHash, myClient, this, logger)

        val mnemonics = "bullet umbrella fringe token whip negative menu drill solid keep vacuum prepare".split(" ")
        val keyManager = LocalKeyManager(MnemonicCode.toSeed(mnemonics, "").toByteVector(), chain, TestConstants.aliceSwapInServerXpub)
        wallet.addAddressGenerator(generator = { index -> keyManager.swapInOnChainWallet.getSwapInProtocol(index).address(chain) })

        // This wallet has:
        // index=0: 10000 sat + 11000 sat
        // index=1: nothing
        // index=2: 12000 sat
        // index=3: nothing <-- will stop there

        val walletState = wallet.walletStateFlow.debounce(5.seconds).first()
        assertEquals(1, walletState.firstUnusedDerivedAddress?.second?.index)
        assertEquals(3, walletState.lastDerivedAddress?.second?.index)
    }

    @OptIn(FlowPreview::class)
    @Test
    fun `derived addresses with gaps and no look-ahead`() = runSuspendTest(timeout = 15.seconds) {
        val chain = Chain.Testnet3
        val wallet = ElectrumMiniWallet(chain.chainHash, myClient, this, logger, lookAhead = 1u)

        val mnemonics = "bullet umbrella fringe token whip negative menu drill solid keep vacuum prepare".split(" ")
        val keyManager = LocalKeyManager(MnemonicCode.toSeed(mnemonics, "").toByteVector(), chain, TestConstants.aliceSwapInServerXpub)
        wallet.addAddressGenerator(generator = { index -> keyManager.swapInOnChainWallet.getSwapInProtocol(index).address(chain) })

        // This wallet has:
        // index=0: 10000 sat + 11000 sat
        // index=1: nothing <-- will stop there
        // index=2: 12000 sat
        // index=3: nothing

        val walletState = wallet.walletStateFlow.debounce(5.seconds).first()
        assertEquals(1, walletState.firstUnusedDerivedAddress?.second?.index)
        assertEquals(1, walletState.lastDerivedAddress?.second?.index)
    }
}
