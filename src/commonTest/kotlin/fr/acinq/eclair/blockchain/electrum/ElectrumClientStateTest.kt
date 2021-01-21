package fr.acinq.eclair.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import fr.acinq.eclair.io.TcpSocket
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.Connection
import fr.acinq.eclair.utils.ServerAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ElectrumClientStateTest : EclairTestSuite() {
    private val testBlockHeader = BlockHeader.read("000000203e343602423924ffc4bccdc08ef468c3ba80187c2200dcb6b60e82d71cdbae236f4a9fd886c3d1ae9659a571f8d0d697c78cb0d6e23112859ba5fc26e46d6744a4f4175fffff7f2000000000")

    @Test
    fun `WaitingForConnection state`() {
        WaitingForConnection.process(Connected).let { (newState, actions) ->
            assertEquals(WaitingForVersion, newState)
            assertEquals(2, actions.size)
            assertTrue(actions[0] is StartPing)
            assertTrue(actions[1] is SendRequest)
        }

        WaitingForConnection.process(AskForStatus).let { (newState, actions) ->
            assertEquals(WaitingForConnection, newState)
            assertTrue { actions.isEmpty() }
        }

        WaitingForConnection.process(Start(ServerAddress("electrum.acinq.co", 50002, TcpSocket.TLS.UNSAFE_CERTIFICATES))).let { (newState, actions) ->
            assertEquals(WaitingForConnection, newState)
            assertTrue { actions.isEmpty() }
        }
    }

    @Test
    fun `WaitingForTip state`() {
        // TODO
        WaitingForTip.process(AskForStatus).let { (newState, actions) ->
            assertEquals(WaitingForTip, newState)
            assertTrue(actions.isEmpty())
        }
    }

    @Test
    fun `ClientRunning state`() {
        // TODO
        ClientRunning(0, testBlockHeader).process(AskForStatus).let { (newState, actions) ->
            assertTrue(newState is ClientRunning)
            assertEquals(1, actions.size)
            assertTrue(actions[0] is BroadcastStatus)
        }
    }

    @Test
    fun `ClientClosed state`() {
        ClientClosed.process(Start(ServerAddress("electrum.acinq.co", 50002, TcpSocket.TLS.UNSAFE_CERTIFICATES))).let { (newState, actions) ->
            assertEquals(WaitingForConnection, newState)
            assertEquals(2, actions.size)
            assertTrue(actions[0] is BroadcastStatus)
            assertTrue(actions[1] is ConnectionAttempt)
        }

        ClientClosed.process(AskForStatus).let { (newState, actions) ->
            assertEquals(ClientClosed, newState)
            assertTrue(actions.isEmpty())
        }

        ClientClosed.process(Connected).let {  (newState, actions) ->
            assertEquals(ClientClosed, newState)
            assertTrue(actions.isEmpty())
        }
    }

    @Test
    fun `unhandled events`() {
        listOf(
            WaitingForConnection, WaitingForVersion, WaitingForTip, ClientRunning(0, testBlockHeader), ClientClosed
        ).forEach { state ->
            listOf(Stop, Disconnected).forEach { event ->
                state.process(event).let { (nextState, actions) ->
                    assertEquals(ClientClosed, nextState)
                    assertEquals(2, actions.size)
                    assertTrue(actions[0] is BroadcastStatus)
                    assertEquals(Connection.CLOSED, (actions[0] as BroadcastStatus).connection)
                    assertTrue(actions[1] is Shutdown)
                }
            }

            if (state !is ClientRunning)
                listOf(AskForStatus, AskForHeader).forEach { event ->
                    state.process(event).let { (nextState, actions) ->
                        assertEquals(state, nextState)
                        assertTrue(actions.isEmpty())
                    }
                }
        }
    }
}
