package fr.acinq.lightning.io.peer

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.InvoiceDefaultRoutingFees
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.NodeUri
import fr.acinq.lightning.blockchain.BITCOIN_FUNDING_DEPTHOK
import fr.acinq.lightning.blockchain.WatchEventConfirmed
import fr.acinq.lightning.blockchain.electrum.balance
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.ChannelType
import fr.acinq.lightning.channel.LNChannel
import fr.acinq.lightning.channel.Origin
import fr.acinq.lightning.channel.TestsHelper
import fr.acinq.lightning.channel.TestsHelper.createWallet
import fr.acinq.lightning.channel.states.*
import fr.acinq.lightning.db.InMemoryDatabases
import fr.acinq.lightning.io.*
import fr.acinq.lightning.router.Announcements
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.io.peer.*
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.test.*

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
        0.toByte(),
        TlvStream(ChannelTlv.ChannelTypeTlv(ChannelType.SupportedChannelType.AnchorOutputsZeroReserve))
    )

    @Test
    fun `init peer`() = runSuspendTest {
        val alice = buildPeer(this, TestConstants.Alice.nodeParams, TestConstants.Alice.walletParams)
        val bob = buildPeer(this, TestConstants.Bob.nodeParams, TestConstants.Bob.walletParams)

        // start Init for Alice
        alice.send(MessageReceived(Init(features = TestConstants.Bob.nodeParams.features)))
        // start Init for Bob
        bob.send(MessageReceived(Init(features = TestConstants.Alice.nodeParams.features)))

        // Wait until the Peer is ready
        alice.expectStatus(Connection.ESTABLISHED)
        bob.expectStatus(Connection.ESTABLISHED)
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
        alice.send(OpenChannel(250_000.sat, 50_000_000.msat, wallet, FeeratePerKw(3000.sat), FeeratePerKw(2500.sat), 0, ChannelType.SupportedChannelType.AnchorOutputsZeroReserve))

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

        alice.send(WatchReceived(WatchEventConfirmed(channelId, BITCOIN_FUNDING_DEPTHOK, 50, 0, fundingTx)))
        val channelReadyAlice = alice2bob.expect<ChannelReady>()
        bob.send(WatchReceived(WatchEventConfirmed(channelId, BITCOIN_FUNDING_DEPTHOK, 50, 0, fundingTx)))
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
        alice.send(OpenChannel(250_000.sat, 50_000_000.msat, wallet, FeeratePerKw(3000.sat), FeeratePerKw(2500.sat), 0, ChannelType.SupportedChannelType.AnchorOutputsZeroReserve))

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
        val walletParams = Pair(TestConstants.Alice.walletParams, TestConstants.Bob.walletParams)
        val (alice, bob, alice2bob, bob2alice) = newPeers(this, nodeParams, walletParams, automateMessaging = false)

        val requestId = randomBytes32()
        val walletBob = createWallet(nodeParams.second.keyManager, 260_000.sat).second
        val internalRequestBob = RequestChannelOpen(requestId, walletBob)
        bob.send(internalRequestBob)
        val request = bob2alice.expect<PleaseOpenChannel>()
        assertEquals(request.localFundingAmount, 260_000.sat)

        val miningFee = 500.sat
        val serviceFee = 1_000.sat.toMilliSatoshi()
        val walletAlice = createWallet(nodeParams.first.keyManager, 50_000.sat).second
        val openAlice = OpenChannel(40_000.sat, 0.msat, walletAlice, FeeratePerKw(3500.sat), FeeratePerKw(2500.sat), 0, ChannelType.SupportedChannelType.AnchorOutputsZeroReserve)
        alice.send(openAlice)
        val open = alice2bob.expect<OpenDualFundedChannel>().copy(
            tlvStream = TlvStream(
                ChannelTlv.ChannelTypeTlv(ChannelType.SupportedChannelType.AnchorOutputsZeroReserve),
                ChannelTlv.OriginTlv(Origin.PleaseOpenChannelOrigin(requestId, serviceFee, miningFee, openAlice.pushAmount))
            )
        )
        bob.forward(open)
        val accept = bob2alice.expect<AcceptDualFundedChannel>()
        assertEquals(open.temporaryChannelId, accept.temporaryChannelId)
        val fundingFee = walletBob.balance - accept.fundingAmount
        assertEquals(accept.pushAmount, serviceFee + miningFee.toMilliSatoshi() - fundingFee.toMilliSatoshi())
        alice.forward(accept)

        val txAddInputAlice = alice2bob.expect<TxAddInput>()
        bob.forward(txAddInputAlice)
        val txAddInputBob = bob2alice.expect<TxAddInput>()
        alice.forward(txAddInputBob)
        val txAddOutput = alice2bob.expect<TxAddOutput>()
        bob.forward(txAddOutput)
        val txCompleteBob = bob2alice.expect<TxComplete>()
        alice.forward(txCompleteBob)
        val txCompleteAlice = alice2bob.expect<TxComplete>()
        bob.forward(txCompleteAlice)
        val commitSigBob = bob2alice.expect<CommitSig>()
        alice.forward(commitSigBob)
        val commitSigAlice = alice2bob.expect<CommitSig>()
        bob.forward(commitSigAlice)
        val txSigsAlice = alice2bob.expect<TxSignatures>()
        bob.forward(txSigsAlice)
        val txSigsBob = bob2alice.expect<TxSignatures>()
        alice.forward(txSigsBob)
        val (_, aliceState) = alice.expectState<WaitForFundingConfirmed>()
        assertEquals(aliceState.commitments.latest.localCommit.spec.toLocal, openAlice.fundingAmount.toMilliSatoshi() + serviceFee + miningFee.toMilliSatoshi() - fundingFee.toMilliSatoshi())
        val (_, bobState) = bob.expectState<WaitForFundingConfirmed>()
        // Bob has to deduce from its balance:
        //  - the fees for the channel open (10 000 sat)
        //  - the miner fees for his input(s) in the funding transaction
        assertEquals(bobState.commitments.latest.localCommit.spec.toLocal, walletBob.balance.toMilliSatoshi() - serviceFee - miningFee.toMilliSatoshi())
    }

    @Test
    fun `reject swap-in -- fee too high`() = runSuspendTest {
        val nodeParams = Pair(TestConstants.Alice.nodeParams, TestConstants.Bob.nodeParams)
        val walletParams = Pair(TestConstants.Alice.walletParams, TestConstants.Bob.walletParams)
        val (alice, bob, alice2bob, bob2alice) = newPeers(this, nodeParams, walletParams, automateMessaging = false)

        val requestId = randomBytes32()
        val walletBob = createWallet(nodeParams.second.keyManager, 260_000.sat).second
        val internalRequestBob = RequestChannelOpen(requestId, walletBob)
        bob.send(internalRequestBob)
        val request = bob2alice.expect<PleaseOpenChannel>()
        assertEquals(request.localFundingAmount, 260_000.sat)
        val fundingFee = 100.sat
        val serviceFee = request.localFundingAmount.toMilliSatoshi() * 0.02 // 2% fee is too high
        val walletAlice = createWallet(nodeParams.first.keyManager, 50_000.sat).second
        val openAlice = OpenChannel(40_000.sat, 0.msat, walletAlice, FeeratePerKw(3500.sat), FeeratePerKw(2500.sat), 0, ChannelType.SupportedChannelType.AnchorOutputsZeroReserve)
        alice.send(openAlice)
        val open = alice2bob.expect<OpenDualFundedChannel>().copy(
            tlvStream = TlvStream(
                ChannelTlv.ChannelTypeTlv(ChannelType.SupportedChannelType.AnchorOutputsZeroReserve),
                ChannelTlv.OriginTlv(Origin.PleaseOpenChannelOrigin(requestId, serviceFee, fundingFee, openAlice.pushAmount))
            )
        )
        bob.forward(open)
        bob2alice.expect<Error>()
    }

    @Test
    fun `reject swap-in -- no associated channel request`() = runSuspendTest {
        val nodeParams = Pair(TestConstants.Alice.nodeParams, TestConstants.Bob.nodeParams)
        val walletParams = Pair(TestConstants.Alice.walletParams, TestConstants.Bob.walletParams)
        val (alice, bob, alice2bob, bob2alice) = newPeers(this, nodeParams, walletParams, automateMessaging = false)

        val requestId = randomBytes32()
        val walletAlice = createWallet(nodeParams.first.keyManager, 50_000.sat).second
        val openAlice = OpenChannel(40_000.sat, 0.msat, walletAlice, FeeratePerKw(3500.sat), FeeratePerKw(2500.sat), 0, ChannelType.SupportedChannelType.AnchorOutputsZeroReserve)
        alice.send(openAlice)
        val open = alice2bob.expect<OpenDualFundedChannel>().copy(
            tlvStream = TlvStream(
                ChannelTlv.ChannelTypeTlv(ChannelType.SupportedChannelType.AnchorOutputsZeroReserve),
                ChannelTlv.OriginTlv(Origin.PleaseOpenChannelOrigin(requestId, 50.sat.toMilliSatoshi(), 100.sat, openAlice.pushAmount))
            )
        )
        bob.forward(open)
        bob2alice.expect<Error>()
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
            alice0.staticParams.nodeParams.copy(checkHtlcTimeoutAfterStartupDelaySeconds = 5),
            TestConstants.Alice.walletParams,
            databases = InMemoryDatabases().also { it.channels.addOrUpdateChannel(alice1.state) },
            currentTip = htlc.cltvExpiry.toLong().toInt() to Block.RegtestGenesisBlock.header
        )

        val initChannels = peer.channelsFlow.first { it.values.isNotEmpty() }
        assertEquals(1, initChannels.size)
        assertEquals(channelId, initChannels.keys.first())
        assertIs<Offline>(initChannels.values.first())

        // send Init from remote node
        val theirInit = Init(features = bob0.staticParams.nodeParams.features)
        peer.send(MessageReceived(theirInit))
        // Wait until the Peer is ready
        peer.expectStatus(Connection.ESTABLISHED)

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
        ).withChannelData(commitments.remoteChannelData)

        peer.send(MessageReceived(channelReestablish))

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
        alice.send(MessageReceived(unknownChannelReestablish))
        alice2bob.expect<Error>()
    }

    @Test
    fun `recover channel`() = runSuspendTest {
        val (alice0, bob0) = TestsHelper.reachNormal()
        val (nodes, _, htlc) = TestsHelper.addHtlc(50_000_000.msat, bob0, alice0)
        val (bob1, alice1) = TestsHelper.crossSign(nodes.first, nodes.second)

        val peer = buildPeer(
            this,
            bob0.staticParams.nodeParams.copy(checkHtlcTimeoutAfterStartupDelaySeconds = 5),
            TestConstants.Bob.walletParams,
            databases = InMemoryDatabases(), // NB: empty database (Bob has lost its channel state)
            currentTip = htlc.cltvExpiry.toLong().toInt() to Block.RegtestGenesisBlock.header
        )

        // Simulate a reconnection with Alice.
        peer.send(MessageReceived(Init(features = alice0.staticParams.nodeParams.features)))
        peer.expectStatus(Connection.ESTABLISHED)
        val aliceReestablish = alice1.state.run { alice1.ctx.createChannelReestablish() }
        assertFalse(aliceReestablish.channelData.isEmpty())
        peer.send(MessageReceived(aliceReestablish))

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

        val peer = buildPeer(
            this,
            bob0.staticParams.nodeParams.copy(checkHtlcTimeoutAfterStartupDelaySeconds = 5),
            TestConstants.Bob.walletParams,
            databases = InMemoryDatabases().also { it.channels.addOrUpdateChannel(bob0.state) }, // NB: outdated channel data
            currentTip = htlc.cltvExpiry.toLong().toInt() to Block.RegtestGenesisBlock.header
        )

        // Simulate a reconnection with Alice.
        peer.send(MessageReceived(Init(features = alice0.staticParams.nodeParams.features)))
        peer.expectStatus(Connection.ESTABLISHED)
        val aliceReestablish = alice1.state.run { alice1.ctx.createChannelReestablish() }
        assertFalse(aliceReestablish.channelData.isEmpty())
        peer.send(MessageReceived(aliceReestablish))

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
            val invoice = bob.createInvoice(randomBytes32(), 1.msat, Either.Left("A description: \uD83D\uDE2C"), 3600L * 3)
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
    fun `payment test between two phoenix nodes -- manual mode`() = runSuspendTest {
        val (alice0, bob0) = TestsHelper.reachNormal()
        val nodeParams = Pair(alice0.staticParams.nodeParams, bob0.staticParams.nodeParams)
        val walletParams = Pair(
            // Alice must declare Bob as her trampoline node to enable direct payments.
            TestConstants.Alice.walletParams.copy(trampolineNode = NodeUri(bob0.staticParams.nodeParams.nodeId, "bob.com", 9735)),
            TestConstants.Bob.walletParams
        )
        val (alice, bob, alice2bob, bob2alice) = newPeers(this, nodeParams, walletParams, listOf(alice0 to bob0), automateMessaging = false)

        val invoice = bob.createInvoice(randomBytes32(), 15_000_000.msat, Either.Left("test invoice"), null)

        alice.send(SendPayment(UUID.randomUUID(), invoice.amount!!, alice.remoteNodeId, invoice))

        val updateHtlc = alice2bob.expect<UpdateAddHtlc>()
        val aliceCommitSig = alice2bob.expect<CommitSig>()
        bob.forward(updateHtlc)
        bob.forward(aliceCommitSig)

        val bobRevokeAndAck = bob2alice.expect<RevokeAndAck>()
        val bobCommitSig = bob2alice.expect<CommitSig>()
        alice.forward(bobRevokeAndAck)
        alice.forward(bobCommitSig)

        val aliceRevokeAndAck = alice2bob.expect<RevokeAndAck>()
        bob.forward(aliceRevokeAndAck)

        val updateFulfillHtlc = bob2alice.expect<UpdateFulfillHtlc>()
        val bobCommitSig2 = bob2alice.expect<CommitSig>()
        alice.forward(updateFulfillHtlc)
        alice.forward(bobCommitSig2)

        val aliceRevokeAndAck2 = alice2bob.expect<RevokeAndAck>()
        val aliceCommitSig2 = alice2bob.expect<CommitSig>()
        bob.forward(aliceRevokeAndAck2)
        bob.forward(aliceCommitSig2)

        val bobRevokeAndAck2 = bob2alice.expect<RevokeAndAck>()
        alice.forward(bobRevokeAndAck2)

        alice.expectState<Normal> { commitments.availableBalanceForReceive() > alice0.commitments.availableBalanceForReceive() }
    }

    @Test
    fun `payment test between two phoenix nodes -- automated messaging`() = runSuspendTest {
        val (alice0, bob0) = TestsHelper.reachNormal()
        val nodeParams = Pair(alice0.staticParams.nodeParams, bob0.staticParams.nodeParams)
        val walletParams = Pair(
            // Alice must declare Bob as her trampoline node to enable direct payments.
            TestConstants.Alice.walletParams.copy(trampolineNode = NodeUri(bob0.staticParams.nodeParams.nodeId, "bob.com", 9735)),
            TestConstants.Bob.walletParams
        )
        val (alice, bob) = newPeers(this, nodeParams, walletParams, listOf(alice0 to bob0))

        val invoice = bob.createInvoice(randomBytes32(), 15_000_000.msat, Either.Left("test invoice"), null)

        alice.send(SendPayment(UUID.randomUUID(), invoice.amount!!, alice.remoteNodeId, invoice))

        alice.expectState<Normal> { commitments.availableBalanceForReceive() > alice0.commitments.availableBalanceForReceive() }
    }
}