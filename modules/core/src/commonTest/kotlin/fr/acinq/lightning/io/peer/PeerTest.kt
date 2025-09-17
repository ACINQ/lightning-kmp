package fr.acinq.lightning.io.peer

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.blockchain.WatchConfirmed
import fr.acinq.lightning.blockchain.WatchConfirmedTriggered
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.channel.TestsHelper.createWallet
import fr.acinq.lightning.channel.states.*
import fr.acinq.lightning.db.InMemoryDatabases
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.io.*
import fr.acinq.lightning.logging.MDCLogger
import fr.acinq.lightning.payment.LiquidityPolicy
import fr.acinq.lightning.router.Announcements
import fr.acinq.lightning.serialization.channel.Encryption.from
import fr.acinq.lightning.serialization.channel.Encryption.fromEncryptedPeerStorage
import fr.acinq.lightning.serialization.channel.Serialization
import fr.acinq.lightning.serialization.channel.Serialization.PeerStorageDeserializationResult
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.io.peer.*
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.tests.utils.testLoggerFactory
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.*
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class PeerTest : LightningTestSuite() {

    private fun buildOpenChannel() = OpenDualFundedChannel(
        Block.RegtestGenesisBlock.hash,
        randomBytes32(),
        TestConstants.feeratePerKw,
        TestConstants.feeratePerKw,
        100_000.sat,
        483.sat,
        10_000,
        1.msat,
        CltvExpiryDelta(144),
        100,
        0,
        randomKey().publicKey(),
        randomKey().publicKey(),
        randomKey().publicKey(),
        randomKey().publicKey(),
        randomKey().publicKey(),
        randomKey().publicKey(),
        randomKey().publicKey(),
        ChannelFlags(announceChannel = false, nonInitiatorPaysCommitFees = false),
        TlvStream(ChannelTlv.ChannelTypeTlv(ChannelType.SupportedChannelType.AnchorOutputsZeroReserve))
    )

    @Test
    fun `init peer`() = runSuspendTest {
        val alice = buildPeer(this, TestConstants.Alice.nodeParams, TestConstants.Alice.walletParams)
        val bob = buildPeer(this, TestConstants.Bob.nodeParams, TestConstants.Bob.walletParams)

        // Alice receives Bob's init.
        val bobInit = Init(TestConstants.Bob.nodeParams.features.initFeatures())
        alice.send(MessageReceived(connectionId = 0, bobInit))
        alice.nodeParams.nodeEvents.first { it == PeerConnected(bob.nodeParams.nodeId, bobInit) }
        // Bob receives Alice's init.
        val aliceInit = Init(TestConstants.Alice.nodeParams.features.initFeatures())
        bob.send(MessageReceived(connectionId = 0, aliceInit))
        bob.nodeParams.nodeEvents.first { it == PeerConnected(alice.nodeParams.nodeId, aliceInit) }
    }

    @Test
    fun `init peer -- bundled`() = runSuspendTest {
        newPeers(this, Pair(TestConstants.Alice.nodeParams, TestConstants.Bob.nodeParams), Pair(TestConstants.Alice.walletParams, TestConstants.Bob.walletParams))
    }

    @Test
    fun `ignore duplicate temporary channel ids`() = runSuspendTest {
        val nodeParams = Pair(TestConstants.Alice.nodeParams, TestConstants.Bob.nodeParams)
        val walletParams = Pair(TestConstants.Alice.walletParams, TestConstants.Bob.walletParams)
        val (alice, _, alice2bob, _) = newPeers(this, nodeParams, walletParams, automateMessaging = false)
        val open = buildOpenChannel()
        alice.forward(open)
        alice2bob.expect<AcceptDualFundedChannel>()
        // bob tries to open another channel with the same temporaryChannelId
        alice.forward(open.copy(firstPerCommitmentPoint = randomKey().publicKey()))
        assertEquals(1, alice.channels.size)
    }

    @Test
    fun `generate random funding keys`() = runSuspendTest {
        val nodeParams = Pair(TestConstants.Alice.nodeParams, TestConstants.Bob.nodeParams)
        val walletParams = Pair(TestConstants.Alice.walletParams, TestConstants.Bob.walletParams)
        val (alice, _, alice2bob, _) = newPeers(this, nodeParams, walletParams, automateMessaging = false)
        val open1 = buildOpenChannel()
        alice.forward(open1)
        alice2bob.expect<AcceptDualFundedChannel>()

        val open2 = buildOpenChannel()
        alice.forward(open2)
        alice2bob.expect<AcceptDualFundedChannel>()

        val open3 = buildOpenChannel()
        alice.forward(open3)
        alice2bob.expect<AcceptDualFundedChannel>()

        assertEquals(3, alice.channels.values.filterIsInstance<WaitForFundingCreated>().map { it.localParams.fundingKeyPath }.toSet().size)
    }

    @Test
    fun `open channel`() = runSuspendTest {
        val nodeParams = Pair(TestConstants.Alice.nodeParams, TestConstants.Bob.nodeParams)
        val walletParams = Pair(TestConstants.Alice.walletParams, TestConstants.Bob.walletParams)
        val (alice, bob, alice2bob, bob2alice) = newPeers(this, nodeParams, walletParams, automateMessaging = false)

        val wallet = createWallet(nodeParams.first.keyManager, 300_000.sat).second
        alice.send(OpenChannel(250_000.sat, wallet, FeeratePerKw(3000.sat), FeeratePerKw(2500.sat), ChannelType.SupportedChannelType.AnchorOutputsZeroReserve))

        val open = alice2bob.expect<OpenDualFundedChannel>()
        bob.forward(open)
        val accept = bob2alice.expect<AcceptDualFundedChannel>()
        assertEquals(open.temporaryChannelId, accept.temporaryChannelId)
        assertTrue(accept.minimumDepth > 0)
        alice.forward(accept)
        val txAddInput = alice2bob.expect<TxAddInput>()
        assertNotEquals(txAddInput.channelId, open.temporaryChannelId) // we now have the final channel_id
        bob.forward(txAddInput)
        val txCompleteBob = bob2alice.expect<TxComplete>()
        alice.forward(txCompleteBob)
        val txAddOutput = alice2bob.expect<TxAddOutput>()
        bob.forward(txAddOutput)
        bob2alice.expect<TxComplete>()
        alice.forward(txCompleteBob)
        val txCompleteAlice = alice2bob.expect<TxComplete>()
        bob.forward(txCompleteAlice)
        val commitSigBob = bob2alice.expect<CommitSig>()
        alice.forward(commitSigBob)
        val commitSigAlice = alice2bob.expect<CommitSig>()
        bob.forward(commitSigAlice)
        val txSigsBob = bob2alice.expect<TxSignatures>()
        alice.forward(txSigsBob)
        val txSigsAlice = alice2bob.expect<TxSignatures>()
        bob.forward(txSigsAlice)
        val (channelId, aliceState) = alice.expectState<WaitForFundingConfirmed> { latestFundingTx.signedTx != null }
        assertEquals(channelId, txAddInput.channelId)
        bob.expectState<WaitForFundingConfirmed>()
        val fundingTx = aliceState.latestFundingTx.signedTx
        assertNotNull(fundingTx)

        alice.send(WatchReceived(WatchConfirmedTriggered(channelId, WatchConfirmed.ChannelFundingDepthOk, 50, 0, fundingTx)))
        val channelReadyAlice = alice2bob.expect<ChannelReady>()
        bob.send(WatchReceived(WatchConfirmedTriggered(channelId, WatchConfirmed.ChannelFundingDepthOk, 50, 0, fundingTx)))
        val channelReadyBob = bob2alice.expect<ChannelReady>()
        alice.forward(channelReadyBob)
        bob.forward(channelReadyAlice)
        alice.expectState<Normal>()
        assertEquals(alice.channels.size, 1)
        assertTrue(alice.channels.containsKey(channelId))
        bob.expectState<Normal>()
        assertEquals(bob.channels.size, 1)
        assertTrue(bob.channels.containsKey(channelId))
    }

    @Test
    fun `open channel -- zero-conf`() = runSuspendTest {
        val nodeParams = Pair(
            TestConstants.Alice.nodeParams.copy(zeroConfPeers = setOf(TestConstants.Bob.nodeParams.nodeId)),
            TestConstants.Bob.nodeParams.copy(zeroConfPeers = setOf(TestConstants.Alice.nodeParams.nodeId))
        )
        val walletParams = Pair(
            TestConstants.Alice.walletParams.copy(trampolineNode = NodeUri(TestConstants.Bob.nodeParams.nodeId, "bob.com", 9735)),
            TestConstants.Bob.walletParams.copy(trampolineNode = NodeUri(TestConstants.Alice.nodeParams.nodeId, "alice.com", 9735))
        )
        val (alice, bob, alice2bob, bob2alice) = newPeers(this, nodeParams, walletParams, automateMessaging = false)

        val wallet = createWallet(nodeParams.first.keyManager, 300_000.sat).second
        alice.send(OpenChannel(250_000.sat, wallet, FeeratePerKw(3000.sat), FeeratePerKw(2500.sat), ChannelType.SupportedChannelType.AnchorOutputsZeroReserve))

        val open = alice2bob.expect<OpenDualFundedChannel>()
        bob.forward(open)
        val accept = bob2alice.expect<AcceptDualFundedChannel>()
        assertEquals(open.temporaryChannelId, accept.temporaryChannelId)
        assertEquals(0, accept.minimumDepth)
        alice.forward(accept)
        val txAddInput = alice2bob.expect<TxAddInput>()
        assertNotEquals(txAddInput.channelId, open.temporaryChannelId) // we now have the final channel_id
        bob.forward(txAddInput)
        val txCompleteBob = bob2alice.expect<TxComplete>()
        alice.forward(txCompleteBob)
        val txAddOutput = alice2bob.expect<TxAddOutput>()
        bob.forward(txAddOutput)
        bob2alice.expect<TxComplete>()
        alice.forward(txCompleteBob)
        val txCompleteAlice = alice2bob.expect<TxComplete>()
        bob.forward(txCompleteAlice)
        val commitSigBob = bob2alice.expect<CommitSig>()
        alice.forward(commitSigBob)
        val commitSigAlice = alice2bob.expect<CommitSig>()
        bob.forward(commitSigAlice)
        val txSigsBob = bob2alice.expect<TxSignatures>()
        alice.forward(txSigsBob)
        val txSigsAlice = alice2bob.expect<TxSignatures>()
        val channelReadyAlice = alice2bob.expect<ChannelReady>()
        bob.forward(txSigsAlice)
        val channelReadyBob = bob2alice.expect<ChannelReady>()
        alice.forward(channelReadyBob)
        bob.forward(channelReadyAlice)
        alice.expectState<Normal>()
        assertEquals(alice.channels.size, 1)
        assertTrue(alice.channels.containsKey(txAddInput.channelId))
        bob.expectState<Normal>()
        assertEquals(bob.channels.size, 1)
        assertTrue(bob.channels.containsKey(txAddInput.channelId))
    }

    @Test
    fun `swap funds into a channel`() = runSuspendTest {
        val nodeParams = Pair(TestConstants.Alice.nodeParams, TestConstants.Bob.nodeParams)
        nodeParams.second.liquidityPolicy.emit(LiquidityPolicy.Auto(inboundLiquidityTarget = 100_000.sat, maxAbsoluteFee = 20_000.sat, maxRelativeFeeBasisPoints = 1000, skipAbsoluteFeeCheck = false, maxAllowedFeeCredit = 0.msat))
        val walletParams = Pair(TestConstants.Alice.walletParams, TestConstants.Bob.walletParams)
        val (_, bob, _, bob2alice) = newPeers(this, nodeParams, walletParams, automateMessaging = false)

        val walletBob = createWallet(nodeParams.second.keyManager, 500_000.sat).second
        bob.send(AddWalletInputsToChannel(walletBob))

        val open = bob2alice.expect<OpenDualFundedChannel>()
        assertTrue(open.fundingAmount < 500_000.sat) // we pay the mining fees
        assertTrue(open.channelFlags.nonInitiatorPaysCommitFees)
        assertEquals(open.requestFunding?.requestedAmount, 100_000.sat) // we always request funds from the remote, because we ask them to pay the commit tx fees
        assertEquals(open.channelType, ChannelType.SupportedChannelType.AnchorOutputsZeroReserve)
        // We cannot test the rest of the flow as lightning-kmp doesn't implement the LSP side that responds to the liquidity ads request.
    }

    @Test
    fun `reject swap-in -- fee too high`() = runSuspendTest {
        val nodeParams = Pair(TestConstants.Alice.nodeParams, TestConstants.Bob.nodeParams)
        val walletParams = Pair(TestConstants.Alice.walletParams, TestConstants.Bob.walletParams)
        val (_, bob) = newPeers(this, nodeParams, walletParams, automateMessaging = false)

        // Bob's liquidity policy is too restrictive.
        val bobPolicy = LiquidityPolicy.Auto(
            inboundLiquidityTarget = 500_000.sat,
            maxAbsoluteFee = 100.sat,
            maxRelativeFeeBasisPoints = 10,
            skipAbsoluteFeeCheck = false,
            maxAllowedFeeCredit = 0.msat,
        )
        nodeParams.second.liquidityPolicy.emit(bobPolicy)
        val walletBob = createWallet(nodeParams.second.keyManager, 1_000_000.sat).second
        bob.send(AddWalletInputsToChannel(walletBob))

        val rejected = bob.nodeParams.nodeEvents.filterIsInstance<LiquidityEvents.Rejected>().first()
        assertEquals(1_500_000_000.msat, rejected.amount)
        assertEquals(LiquidityEvents.Source.OnChainWallet, rejected.source)
        assertEquals(LiquidityEvents.Rejected.Reason.TooExpensive.OverRelativeFee(maxRelativeFeeBasisPoints = 10), rejected.reason)
    }

    @Test
    fun `restore channel`() = runSuspendTest {
        val (alice0, bob0) = TestsHelper.reachNormal()
        val (nodes, _, htlc) = TestsHelper.addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, _) = TestsHelper.crossSign(nodes.first, nodes.second)
        assertIs<LNChannel<Normal>>(alice1)
        val channelId = alice0.channelId

        val peer = buildPeer(
            this,
            alice0.staticParams.nodeParams.copy(checkHtlcTimeoutAfterStartupDelay = 5.seconds),
            TestConstants.Alice.walletParams,
            databases = InMemoryDatabases().also { it.channels.addOrUpdateChannel(alice1.state) },
            currentTip = htlc.cltvExpiry.toLong().toInt()
        )

        val initChannels = peer.channelsFlow.first { it.values.isNotEmpty() }
        assertEquals(1, initChannels.size)
        assertEquals(channelId, initChannels.keys.first())
        assertIs<Offline>(initChannels.values.first())

        // send Init from remote node
        val theirInit = Init(features = bob0.staticParams.nodeParams.features)
        peer.send(MessageReceived(connectionId = 0, theirInit))

        // Wait until the channels are Syncing
        val syncChannels = peer.channelsFlow
            .first { it.values.size == 1 && it.values.all { channelState -> channelState is Syncing } }
            .map { it.value as Syncing }
        assertEquals(channelId, syncChannels.first().state.channelId)

        val syncState = syncChannels.first()
        assertIs<Normal>(syncState.state)
        val commitments = (syncState.state as Normal).commitments
        val yourLastPerCommitmentSecret = ByteVector32.Zeroes
        val myCurrentPerCommitmentPoint = peer.nodeParams.keyManager.channelKeys(commitments.params.localParams.fundingKeyPath).commitmentPoint(commitments.localCommitIndex)

        val channelReestablish = ChannelReestablish(
            channelId = syncState.state.channelId,
            nextLocalCommitmentNumber = commitments.localCommitIndex + 1,
            nextRemoteRevocationNumber = commitments.remoteCommitIndex,
            yourLastCommitmentSecret = PrivateKey(yourLastPerCommitmentSecret),
            myCurrentPerCommitmentPoint = myCurrentPerCommitmentPoint
        )

        peer.send(MessageReceived(connectionId = 0, channelReestablish))

        // Wait until the channels are Reestablished(=Normal)
        val reestablishChannels = peer.channelsFlow.first { it.values.isNotEmpty() && it.values.all { channelState -> channelState is Normal } }
        assertEquals(channelId, reestablishChannels.keys.first())

        // Wait until alice detects the HTLC-timeout and closes
        val closingChannels = peer.channelsFlow.first { it.values.isNotEmpty() && it.values.all { channelState -> channelState is Closing } }
        assertEquals(channelId, closingChannels.keys.first())
    }

    @Test
    fun `restore channel -- bundled`() = runSuspendTest {
        val (alice0, bob0) = TestsHelper.reachNormal()
        newPeers(this, Pair(alice0.staticParams.nodeParams, bob0.staticParams.nodeParams), Pair(TestConstants.Alice.walletParams, TestConstants.Bob.walletParams), listOf(alice0 to bob0))
    }

    @Test
    fun `restore channel -- unknown channel`() = runSuspendTest {
        val (alice, _, alice2bob) = newPeers(this, Pair(TestConstants.Alice.nodeParams, TestConstants.Bob.nodeParams), Pair(TestConstants.Alice.walletParams, TestConstants.Bob.walletParams))
        val unknownChannelReestablish = ChannelReestablish(randomBytes32(), 1, 0, randomKey(), randomKey().publicKey())
        alice.send(MessageReceived(connectionId = 0, unknownChannelReestablish))
        alice2bob.expect<Error>()
    }

    @Test
    fun `recover channel`() = runSuspendTest {
        val (alice0, bob0) = TestsHelper.reachNormal()
        val (nodes, _, htlc) = TestsHelper.addHtlc(50_000_000.msat, bob0, alice0)
        val (bob1, alice1) = TestsHelper.crossSign(nodes.first, nodes.second)

        val backup = EncryptedPeerStorage.from(TestConstants.Bob.nodeParams.nodePrivateKey, listOf(bob1.state))

        val peer = buildPeer(
            this,
            bob0.staticParams.nodeParams.copy(checkHtlcTimeoutAfterStartupDelay = 5.seconds),
            TestConstants.Bob.walletParams,
            databases = InMemoryDatabases(), // NB: empty database (Bob has lost its channel state)
            currentTip = htlc.cltvExpiry.toLong().toInt()
        )

        // Simulate a reconnection with Alice.
        peer.send(MessageReceived(connectionId = 0, Init(features = alice0.staticParams.nodeParams.features)))
        peer.send(MessageReceived(connectionId = 0, PeerStorageRetrieval(backup)))
        val aliceReestablish = alice1.state.run { alice1.ctx.createChannelReestablish() }
        peer.send(MessageReceived(connectionId = 0, aliceReestablish))

        // Wait until the channels are Syncing
        val restoredChannel = peer.channelsFlow
            .first { it.size == 1 }
            .values
            .first()
        assertEquals(bob1.state, restoredChannel)
        assertEquals(peer.db.channels.listLocalChannels(), listOf(restoredChannel))
    }

    @Test
    fun `recover channel -- outdated local data`() = runSuspendTest {
        val (alice0, bob0) = TestsHelper.reachNormal()
        val (nodes, _, htlc) = TestsHelper.addHtlc(50_000_000.msat, bob0, alice0)
        val (bob1, alice1) = TestsHelper.crossSign(nodes.first, nodes.second)

        val backup = EncryptedPeerStorage.from(TestConstants.Bob.nodeParams.nodePrivateKey, listOf(bob1.state))

        val peer = buildPeer(
            this,
            bob0.staticParams.nodeParams.copy(checkHtlcTimeoutAfterStartupDelay = 5.seconds),
            TestConstants.Bob.walletParams,
            databases = InMemoryDatabases().also { it.channels.addOrUpdateChannel(bob0.state) }, // NB: outdated channel data
            currentTip = htlc.cltvExpiry.toLong().toInt()
        )

        // Simulate a reconnection with Alice.
        peer.send(MessageReceived(connectionId = 0, Init(features = alice0.staticParams.nodeParams.features)))
        peer.send(MessageReceived(connectionId = 0, PeerStorageRetrieval(backup)))
        val aliceReestablish = alice1.state.run { alice1.ctx.createChannelReestablish() }
        peer.send(MessageReceived(connectionId = 0, aliceReestablish))

        // Wait until the channels are Syncing
        val restoredChannel = peer.channelsFlow
            .first { it.size == 1 && it.values.first() is Normal }
            .values
            .first()
        assertEquals(bob1.state, restoredChannel)
        assertEquals(peer.db.channels.listLocalChannels(), listOf(restoredChannel))
    }

    @Test
    fun `invoice parameters`() = runSuspendTest {
        val nodeParams = TestConstants.Bob.nodeParams
        val walletParams = TestConstants.Bob.walletParams
        val bob = newPeer(nodeParams, walletParams)

        run {
            val invoice = bob.createInvoice(randomBytes32(), 1.msat, Either.Left("A description: \uD83D\uDE2C"), 3.hours)
            assertEquals(1.msat, invoice.amount)
            assertEquals(3600L * 3, invoice.expirySeconds)
            assertEquals("A description: \uD83D\uDE2C", invoice.description)
        }
    }

    @Test
    fun `invoice routing hints`() = runSuspendTest {
        val (alice0, bob0) = TestsHelper.reachNormal()
        val nodeParams = Pair(alice0.staticParams.nodeParams, bob0.staticParams.nodeParams)
        val walletParams = Pair(TestConstants.Alice.walletParams, TestConstants.Bob.walletParams)

        val (_, bob, _, _) = newPeers(this, nodeParams, walletParams, listOf(alice0 to bob0), automateMessaging = false)

        run {
            val invoice = bob.createInvoice(randomBytes32(), 15_000_000.msat, Either.Left("default routing hints"), null)
            // The routing hint uses default values since no channel update has been sent by Alice yet.
            assertEquals(1, invoice.routingInfo.size)
            assertEquals(1, invoice.routingInfo[0].hints.size)
            val extraHop = invoice.routingInfo[0].hints[0]
            assertEquals(TestConstants.Bob.walletParams.invoiceDefaultRoutingFees, InvoiceDefaultRoutingFees(extraHop.feeBase, extraHop.feeProportionalMillionths, extraHop.cltvExpiryDelta))
        }
        run {
            val aliceUpdate =
                Announcements.makeChannelUpdate(alice0.staticParams.nodeParams.chainHash, alice0.ctx.privateKey, alice0.staticParams.remoteNodeId, alice0.state.shortChannelId, CltvExpiryDelta(48), 100.msat, 50.msat, 250, 150_000.msat)
            bob.forward(aliceUpdate)
            // wait until the update is processed
            bob.channelsFlow
                .map { it.values.first() }
                .first { it is Normal && it.remoteChannelUpdate?.feeBaseMsat == 50.msat }

            val invoice = bob.createInvoice(randomBytes32(), 5_000_000.msat, Either.Left("updated routing hints"), null)
            // The routing hint uses values from Alice's channel update.
            assertEquals(1, invoice.routingInfo.size)
            assertEquals(1, invoice.routingInfo[0].hints.size)
            val extraHop = invoice.routingInfo[0].hints[0]
            assertEquals(50.msat, extraHop.feeBase)
            assertEquals(250, extraHop.feeProportionalMillionths)
            assertEquals(CltvExpiryDelta(48), extraHop.cltvExpiryDelta)
        }
    }

    @Test
    fun `payment between two nodes -- manual mode`() = runSuspendTest {
        val (alice0, bob0) = TestsHelper.reachNormal()
        val nodeParams = Pair(alice0.staticParams.nodeParams, bob0.staticParams.nodeParams)
        val walletParams = Pair(
            // Alice must declare Bob as her trampoline node to enable direct payments.
            TestConstants.Alice.walletParams.copy(trampolineNode = NodeUri(bob0.staticParams.nodeParams.nodeId, "bob.com", 9735)),
            TestConstants.Bob.walletParams
        )
        val (alice, bob, alice2bob, bob2alice) = newPeers(this, nodeParams, walletParams, listOf(alice0 to bob0), automateMessaging = false)

        val invoice = bob.createInvoice(randomBytes32(), 15_000_000.msat, Either.Left("test invoice"), null)

        alice.send(PayInvoice(UUID.randomUUID(), invoice.amount!!, LightningOutgoingPayment.Details.Normal(invoice)))

        val updateHtlc = alice2bob.expectStrict<UpdateAddHtlc>()
        val aliceCommitSig = alice2bob.expectStrict<CommitSig>()
        bob.forward(updateHtlc)
        bob.forward(aliceCommitSig)

        bob2alice.expectStrict<PeerStorageStore>()
        val bobRevokeAndAck = bob2alice.expectStrict<RevokeAndAck>()
        bob2alice.expectStrict<PeerStorageStore>()
        val bobCommitSig = bob2alice.expectStrict<CommitSig>()
        alice.forward(bobRevokeAndAck)
        alice.forward(bobCommitSig)

        val aliceRevokeAndAck = alice2bob.expectStrict<RevokeAndAck>()
        bob.forward(aliceRevokeAndAck)

        val updateFulfillHtlc = bob2alice.expectStrict<UpdateFulfillHtlc>()
        bob2alice.expectStrict<PeerStorageStore>()
        val bobCommitSig2 = bob2alice.expectStrict<CommitSig>()
        alice.forward(updateFulfillHtlc)
        alice.forward(bobCommitSig2)

        val aliceRevokeAndAck2 = alice2bob.expectStrict<RevokeAndAck>()
        val aliceCommitSig2 = alice2bob.expectStrict<CommitSig>()
        bob.forward(aliceRevokeAndAck2)
        bob.forward(aliceCommitSig2)

        bob2alice.expectStrict<PeerStorageStore>()
        val bobRevokeAndAck2 = bob2alice.expectStrict<RevokeAndAck>()
        alice.forward(bobRevokeAndAck2)

        alice.expectState<Normal> { commitments.availableBalanceForReceive() > alice0.commitments.availableBalanceForReceive() }
    }

    @Test
    fun `payment between two nodes -- with disconnection`() = runSuspendTest {
        val (alice0, bob0) = TestsHelper.reachNormal(bobFundingAmount = 300_000.sat)
        val nodeParams = Pair(alice0.staticParams.nodeParams, bob0.staticParams.nodeParams)
        val walletParams = Pair(
            // Alice must declare Bob as her trampoline node to enable direct payments.
            TestConstants.Alice.walletParams.copy(trampolineNode = NodeUri(bob0.staticParams.nodeParams.nodeId, "bob.com", 9735)),
            TestConstants.Bob.walletParams
        )
        val (alice, bob, alice2bob1, bob2alice1) = newPeers(this, nodeParams, walletParams, listOf(alice0 to bob0), automateMessaging = false)
        val invoice = alice.createInvoice(randomBytes32(), 150_000_000.msat, Either.Left("test invoice"), null)
        bob.send(PayInvoice(UUID.randomUUID(), invoice.amount!!, LightningOutgoingPayment.Details.Normal(invoice)))

        // Bob sends an HTLC to Alice.
        alice.forward(bob2alice1.expectStrict<UpdateAddHtlc>())
        bob2alice1.expectStrict<PeerStorageStore>()
        alice.forward(bob2alice1.expectStrict<CommitSig>())

        // We start cross-signing the HTLC.
        bob.forward(alice2bob1.expectStrict<RevokeAndAck>())
        bob.forward(alice2bob1.expectStrict<CommitSig>())
        bob2alice1.expectStrict<PeerStorageStore>()
        bob2alice1.expectStrict<RevokeAndAck>() // Alice doesn't receive Bob's revocation.

        // We disconnect before Alice receives Bob's revocation.
        alice.disconnect()
        alice.send(Disconnected)
        bob.disconnect()
        bob.send(Disconnected)

        // On reconnection, Bob retransmits its revocation.
        val (_, _, alice2bob2, bob2alice2) = connect(this, connectionId = 1, alice, bob, channelsCount = 1, expectChannelReady = false, automateMessaging = false)
        bob2alice2.expectStrict<PeerStorageStore>()
        alice.forward(bob2alice2.expectStrict<RevokeAndAck>(), connectionId = 1)

        // Alice has now received the complete payment and fulfills it.
        bob.forward(alice2bob2.expectStrict<UpdateFulfillHtlc>(), connectionId = 1)
        bob.forward(alice2bob2.expectStrict<CommitSig>(), connectionId = 1)
        bob2alice2.expectStrict<PeerStorageStore>()
        alice.forward(bob2alice2.expectStrict<RevokeAndAck>(), connectionId = 1)
        bob2alice2.expectStrict<PeerStorageStore>()
        bob2alice2.expectStrict<CommitSig>() // Alice doesn't receive Bob's signature.

        // We disconnect before Alice receives Bob's signature.
        alice.disconnect()
        alice.send(Disconnected)
        bob.disconnect()
        bob.send(Disconnected)

        // On reconnection, Bob retransmits its signature.
        val (_, _, alice2bob3, bob2alice3) = connect(this, connectionId = 2, alice, bob, channelsCount = 1, expectChannelReady = false, automateMessaging = false)
        bob2alice3.expectStrict<PeerStorageStore>()
        alice.forward(bob2alice3.expectStrict<CommitSig>(), connectionId = 2)
        bob.forward(alice2bob3.expectStrict<RevokeAndAck>(), connectionId = 2)

        assertEquals(invoice.amount, alice.db.payments.getLightningIncomingPayment(invoice.paymentHash)?.amount)
    }

    @Test
    fun `payment between two nodes -- automated messaging`() = runSuspendTest {
        val (alice0, bob0) = TestsHelper.reachNormal()
        val nodeParams = Pair(alice0.staticParams.nodeParams, bob0.staticParams.nodeParams)
        val walletParams = Pair(
            // Alice must declare Bob as her trampoline node to enable direct payments.
            TestConstants.Alice.walletParams.copy(trampolineNode = NodeUri(bob0.staticParams.nodeParams.nodeId, "bob.com", 9735)),
            TestConstants.Bob.walletParams
        )
        val (alice, bob) = newPeers(this, nodeParams, walletParams, listOf(alice0 to bob0))

        val invoice = bob.createInvoice(randomBytes32(), 15_000_000.msat, Either.Left("test invoice"), null)

        alice.send(PayInvoice(UUID.randomUUID(), invoice.amount!!, LightningOutgoingPayment.Details.Normal(invoice)))

        alice.expectState<Normal> { commitments.availableBalanceForReceive() > alice0.commitments.availableBalanceForReceive() }
    }

    @Test
    fun `update peer storage when closing channel`() = runSuspendTest {
        val (alice0, bob0) = TestsHelper.reachNormal()
        val nodeParams = Pair(alice0.staticParams.nodeParams, bob0.staticParams.nodeParams)
        val walletParams = Pair(TestConstants.Alice.walletParams, TestConstants.Bob.walletParams)
        val (alice, bob, alice2bob, bob2alice) = newPeers(this, nodeParams, walletParams, listOf(alice0 to bob0), automateMessaging = false)

        val replyTo = CompletableDeferred<ChannelCloseResponse>()
        alice.send(WrappedChannelCommand(alice0.channelId, ChannelCommand.Close.MutualClose(replyTo, Script.write(Script.pay2pkh(randomKey().publicKey())).toByteVector(), FeeratePerKw.CommitmentFeerate)))

        bob.forward(alice2bob.expectStrict<Shutdown>())
        bob2alice.expectStrict<PeerStorageStore>()
        alice.forward(bob2alice.expectStrict<Shutdown>())
        bob.forward(alice2bob.expectStrict<ClosingComplete>())
        bob2alice.expectStrict<PeerStorageStore>()
        alice.forward(bob2alice.expectStrict<ClosingSig>())
    }

    @Test
    fun `update peer storage`() = runSuspendTest {
        val peerConnection = PeerConnection(0L, Channel(1), MDCLogger(TestConstants.Bob.nodeParams.loggerFactory.newLogger("PeerConnection")))
        val normal = (Serialization.deserialize(Hex.decode("040fe4e83ab49eb64d3eb67d0580496f25c5c2b18ee61f81d51aa1ebb81514a3754f010104101010000362b19a83930389b4468be40308efb3f352b23142ae25e6aba0465a8220f95b0609fea717aec2feec3e97d5fe7f669cbffe27052d57fe06cae290fe822afa07fe1cb290f4fe8e7daa84fe80000000fd03e8fe59682f00fd03e890640016001434947cfb2e8f6054ddf12daed4308cbe342580d1470a000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000008200022000000000000000010001004162a5102037108815ff0128f7ed22640485c226d9ad64a9fd6d8b41b6623565aed6b34812cfd044cfe59682f006490640378dfba317476e7e0480c49b5746923163cee59f1a52459923749d8cf3c3df8c40303e991f942755ecd51d2eff138b00a0dd815e23557369339a959e7094191e3fa024c0a8af486d7d5b01d62e236e565cbb476444289970c5796fb4f8e2911373ca0025aaa789952946ed4ba55101788cdf25d993b1811999aaeb73a98c42ace22df604702000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000008200080000000000000000010001804161a5102000000000000000000000100034014722779d6bff4c590688a9e652fcee1fb6ad8665b3c321224a9043c2bc0a403fd010d0200000000010253b25e79b2bbbd63c2de6236f9661d31f26b0a93050cb83551a44d0165805c050000000000fdffffff953fc2c630bdabf1497d4b90f32e594e47547e44a92de7e9c80b8070bcf60f3c0000000000fdffffff0140420f0000000000220020a7bbec3fd19e4da3f2f69ea3f80735fab6305a0c8879badd97da07e14a31739301404a6fa738c4dca26d4240a08137fd6f4f73478f9dc1d490b6940a6f2969f13ae03cd26471f1f8837eddd8107b0754ba49f2e544259d54bb3654b359b765484d5a0140088c67d8d7dc96a7af6ce732f96fb5f19f49c53e05cf5e95176df974f85bc23f3827111e01aa90469ff3ba28237f041f071814e76eac17f3ff23b2dfdab98939801a0600fd1388fd01940047e4e83ab49eb64d3eb67d0580496f25c5c2b18ee61f81d51aa1ebb81514a3754f9808122294c64b4bfaf1aa57525814443cfadfb0562b3fb4d59be4d29606fcb10000fd025fa4fb770e987f580cce6fc813f6263d4c19a293e3531cb0c36c6613bfc70435245b02a20895d56edd728e79e64139546614766a339c31ce8cd4748bcd12d2be4a1c2b03765181d7ce14b4e6b162e278c38594b57efb08e1e8d63ea07ff3e4c442a9b9c2023c8eac917efb01236a9228aadc45180fca7238d0e66f3800d39557c6597a5fca03923dc3cde85ef6215bca8152794cfa219c7954292045f7442a4d03e642336231fd0261a42204e1d9c9529c5bebcc6aaa609f1de382b1b19b97142aba91b479a76607e189032c819db7a34a9b760312512935c0231307b0f868f00ab2801c85104114c0bc1e03108f0177b63c79b9f5c075ff3989ce31e44f20290ca0dad6b0e2a491af8eb45a0336c847816017de20af0a3e94677cd11af25dfb96823d2096bcfb34a8c7c21296023c2b94c00871dc178c40b2bedb437480fc5287044883b8b72cd7fdaa516d56b5ff00002a0000000000000000fd1388fe08f0d180fe32a9f88000249808122294c64b4bfaf1aa57525814443cfadfb0562b3fb4d59be4d29606fcb1000000002b40420f0000000000220020a7bbec3fd19e4da3f2f69ea3f80735fab6305a0c8879badd97da07e14a31739347522102479473d3f50777e4dd5c1c76d8293463ca21b6efdf239be429ba59899ad7045d21034014722779d6bff4c590688a9e652fcee1fb6ad8665b3c321224a9043c2bc0a452aefd01bc020000000001019808122294c64b4bfaf1aa57525814443cfadfb0562b3fb4d59be4d29606fcb100000000004e978680044a01000000000000220020a49538cbc4c8ba648f4d54ab75af7a8232cbaa5ce28aee37d1c463a602ff17734a01000000000000220020e4ec385950288763602b978f5f2708481a440e558d348435eb48c2c34d2d7ae5f049020000000000220020be02cd9576130b004167ea3d20e6802780550a5145b60fab4284d3e0c38c8875c8df0c0000000000220020542fe2e968ebac924f550277621275f8492b1595fc2f1c72dadc27543bf17b0d0400483045022100cb79406d91119d566653e142608c4fd145783e18b31b888fb1bc7d547521eec702207e6fdb0ae4adc87efbdab1d85d6e133501a8b7a0a99198b247c737cb329cc466014730440220781dc63f4f8c4bf83405cc35bcfb358a286f8ca6958e931149ee7ab1bf08048b022049f6d104c2d5bd696d098f2e3eacb52101921880959ac369636f587bffe8bb9a0147522102479473d3f50777e4dd5c1c76d8293463ca21b6efdf239be429ba59899ad7045d21034014722779d6bff4c590688a9e652fcee1fb6ad8665b3c321224a9043c2bc0a452aeaca3f320000000fd1388fe32a9f880fe08f0d180ae8242cae1560ba622e54a604e45b2dad95915e0d0170f71bef83dd91ceb776802348520379a886f352215b530f83f3a4beb6d69e88ea5633aeb86f1be7f5952a2000000010225e8dac68c08b2e0c7c8579c6d156c54a9937e6127dbadf03de1cf7a35d4c10c000000ff00002a00000000008a01025bae38343705f420ae50fe3f6d0cf62464f24dd2193154cfb58d9e18cf008bbb74e81e18785add47d37dc26fdc0c367c8c9ac5414473f6e92917e7251410e40206226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f00002a00000000006810d43e0100009000000000000000640000000a0000000a000000003b9aca000000000000")) as Serialization.DeserializationResult.Success).state
        val closed = (Serialization.deserialize(Hex.decode("04073de66f67d0299abce5fc9bda90f04dadd4f30f8839085f58fd54732e40bf5b0701010410101000037108815ff0128f7ed22640485c226d9ad64a9fd6d8b41b6623565aed6b34812c09fe93dd6428fea71e4e93fefe44318bfe421ee4a1fe2d184f0cfe62f7a392fe1e383f0afe33562044fe80000001fd044cfe59682f006490640316001405e0104aa726e34ff5cd3a6320d05c0862b5b01c4702000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000008200080000000000000000010001804161a51020362b19a83930389b4468be40308efb3f352b23142ae25e6aba0465a8220f95b06fd03e8fe59682f00fd03e89064039bbddd6dd9c1496d6140596d8e48dd9488c8109c1306b3310f7af0d9bf19ad350290499f7d11f90b242f47e13d5da72ab627211e172e521556f1feaac21c03635502eee4b6e0fcadb8537e11708771d835e7c795d3fa7554ebcccce9a4f72d32a91503f788b74ef83e829c3f6da831b3f1e61323c2f9eb2d4dbad5ffdcf61200228131470a000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000008200022000000000000000010001004162a5102000000000000000000000100038e42a04c503ba0b0d8ff7adad8f1d95e55b592dac4f1152facd29f5863f868a303fd010d02000000000102710ee99c723665c00fcdf4d0fee9a6d0053c2ea566c9ecb03acb9466be6d00080000000000fdffffffda09a91aa5e2d4a1bd5b9f3bc8c1d282c2fe0d0d6720b026d337a844b61cbcf20000000000fdffffff0140420f00000000002200203e39874c71cbe526aa69952b743dea737046b986a472d75af627bc1ee6b3665e014068661220864dda254a01cf3f95f0f354d612a50d2fb162dbbd7b9271ab69917fd2918b4adb60b4d42a40f5f20aa56377713367e2d1f3d90a7b2813997bb03d3e01401df43b8c44bde532441e5e75bfddd55491c1fd0110a5c8244cbad28dc58d9103e96f16c6515659cd6c8319422c3b18c91415f2ba06d9db89cf8aa1613841f83e801a0600fd1388fd019400473de66f67d0299abce5fc9bda90f04dadd4f30f8839085f58fd54732e40bf5b07998e4f01298f8809f55f1eebae66fd9b4d27712e629a5fb2830b54dc0471634c0000fd025fa4e834e45e7e48d2a31a56a2367b5514a12d387e79ab2604e4e398717775bd33a7024404be0fa7136e1bb020e152d62646730a7dcc96d7b7bf94245478ead7e44527026aec6ac096605035a5ff9c9ce4d8dd85dd7b8ae39aba72fc10a902d925667c1703912be68dfb905d104eca952492d296eae1b376513fcdf58367c1ba2718d9a2aa022ad4d14973674a186f809f053b9c3c681ad8d81675c29db2f5e374152f50e1dcfd0261a449645516c07272b5805265023f13675198f4af5020a9721a917a7e07ad67cd5e038fc6a5d5026bca8cea48df4745c35ca668f6c9d1503a069cb66a234d44d4dfb503361fcc3e073b9c5b4e02d9aa93ea08b99c00114a5d62b62256f212f2d5b9b74a03f6a2ff65b9a60aac69c276254a0191d13d916905cfc50697a4f75f0e69f8a48f0340f9af89de43f4fab522ccfef34314a4c643b20cdc0089a094c6d46acf33313fff00002a0000000000000000fd1388fe32a9f880fe08f0d1800024998e4f01298f8809f55f1eebae66fd9b4d27712e629a5fb2830b54dc0471634c000000002b40420f00000000002200203e39874c71cbe526aa69952b743dea737046b986a472d75af627bc1ee6b3665e47522103457b4ac99448324bb2b572960ca7f92de88a1a83063b86ba52e1d348e3ceddd121038e42a04c503ba0b0d8ff7adad8f1d95e55b592dac4f1152facd29f5863f868a352aefd01bc02000000000101998e4f01298f8809f55f1eebae66fd9b4d27712e629a5fb2830b54dc0471634c0000000000e7694480044a010000000000002200201ef6856f02439e5d52aa223bba797bfc89c1873cb986a2b8421179e9ce4623ab4a010000000000002200207027673e1c42fe51c36c6ccae676374c401acf6de790747fabc67521e6db0f4df04902000000000022002063c9f303ee4cf5ae24c614dc449f581860002261533cad76e202861c4f2d22eec8df0c0000000000220020cce870cf0de50f62e1ad0a5ee101f9fe6e5a3be89cce5d6648809f477698a28f0400483045022100e545bca139fd17705bbaebc52063b6f2c8a981a94cab630cccc02a6fc338af20022053458917f40dc11780ad7dbadc431b3088b6020044a75ae27f194894a097483e014730440220296368a1fee2cd77e213f95f012b83bf7f64122b41d0c47b86f8c1dd1ac4d2910220395c79bd93653754463e82d638cea3a04e377523674030ec1dd31acbe56aadad0147522103457b4ac99448324bb2b572960ca7f92de88a1a83063b86ba52e1d348e3ceddd121038e42a04c503ba0b0d8ff7adad8f1d95e55b592dac4f1152facd29f5863f868a352aed32e2f20000000fd1388fe08f0d180fe32a9f880fff01fed080a668c37515567b7fd5b3804bc8b6bc9acba38e99512b11defded1020d5f58a847b196035ddaa3ad48ff977b9f8c1b56ba7e0b387f5144235dc3492100000001022cd9f325b167ec1a93ad7abd0ec7f7fb68d41e212d15d99a588a8698cfe90b5c000000fe00061a80000001fd01bc02000000000101998e4f01298f8809f55f1eebae66fd9b4d27712e629a5fb2830b54dc0471634c0000000000e7694480044a010000000000002200201ef6856f02439e5d52aa223bba797bfc89c1873cb986a2b8421179e9ce4623ab4a010000000000002200207027673e1c42fe51c36c6ccae676374c401acf6de790747fabc67521e6db0f4df04902000000000022002063c9f303ee4cf5ae24c614dc449f581860002261533cad76e202861c4f2d22eec8df0c0000000000220020cce870cf0de50f62e1ad0a5ee101f9fe6e5a3be89cce5d6648809f477698a28f0400483045022100e545bca139fd17705bbaebc52063b6f2c8a981a94cab630cccc02a6fc338af20022053458917f40dc11780ad7dbadc431b3088b6020044a75ae27f194894a097483e014730440220296368a1fee2cd77e213f95f012b83bf7f64122b41d0c47b86f8c1dd1ac4d2910220395c79bd93653754463e82d638cea3a04e377523674030ec1dd31acbe56aadad0147522103457b4ac99448324bb2b572960ca7f92de88a1a83063b86ba52e1d348e3ceddd121038e42a04c503ba0b0d8ff7adad8f1d95e55b592dac4f1152facd29f5863f868a352aed32e2f20010724cc016c59838ee2798e97d249fef1bd2aeea2ef029b3a607dd8abf9a51f47870f030000002bc8df0c0000000000220020cce870cf0de50f62e1ad0a5ee101f9fe6e5a3be89cce5d6648809f477698a28f4d6321031aafbdd02ebc0a6d502fe9b4e7562bb742cc7cc5343b4ab0f8365457fd43374e67029000b27521029bea298221b167959bae810de83689e1a9a073a15e690dca1c33184d4c21585768acec02000000000101cc016c59838ee2798e97d249fef1bd2aeea2ef029b3a607dd8abf9a51f47870f0300000000900000000159d60c000000000016001405e0104aa726e34ff5cd3a6320d05c0862b5b01c0347304402205fa8b7a610af622805fb4beb000eaf2b5a7c07133ca30f1e4b976da19c36cbe502203df03d6c77e34c30ee1804e48c5c918deca9c09452c934007e5e61b9fb1dc7e501004d6321031aafbdd02ebc0a6d502fe9b4e7562bb742cc7cc5343b4ab0f8365457fd43374e67029000b27521029bea298221b167959bae810de83689e1a9a073a15e690dca1c33184d4c21585768ac000000000000000000000000")) as Serialization.DeserializationResult.Success).state
        assertIs<Normal>(normal)
        assertIs<Closed>(closed)

        Peer.updatePeerStorage(TestConstants.Bob.nodeParams, mapOf(normal.channelId to normal, closed.channelId to closed), peerConnection, TestConstants.Alice.nodeParams.features, null)
        val peerStorage = peerConnection.output.tryReceive().getOrThrow()
        assertIs<PeerStorageStore>(peerStorage)
        val backup = PersistedChannelState.fromEncryptedPeerStorage(TestConstants.Bob.nodeParams.nodePrivateKey, peerStorage.eps, null).getOrThrow()
        assertIs<PeerStorageDeserializationResult.Success>(backup)
        assertEquals(listOf(normal), backup.states) // the backup contains only the Normal channel

        // usePeerStorage = false
        Peer.updatePeerStorage(TestConstants.Bob.nodeParams.copy(usePeerStorage = false), mapOf(normal.channelId to normal, closed.channelId to closed), peerConnection, Features(Feature.ProvideStorage to FeatureSupport.Optional), null)
        assertTrue(peerConnection.output.tryReceive().isFailure)

        // remote peer doesn't support peer storage
        Peer.updatePeerStorage(TestConstants.Bob.nodeParams, mapOf(normal.channelId to normal, closed.channelId to closed), peerConnection, Features.empty, null)
        assertTrue(peerConnection.output.tryReceive().isFailure)
    }
}