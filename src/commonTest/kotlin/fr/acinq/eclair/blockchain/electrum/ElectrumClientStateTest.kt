package fr.acinq.eclair.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ElectrumClientStateTest {
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

        assertFails { WaitingForConnection.process(Start) }
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
        ClientClosed.process(Start).let { (newState, actions) ->
            assertEquals(WaitingForConnection, newState)
            assertEquals(2, actions.size)
            assertTrue(actions[0] is BroadcastStatus)
            assertTrue(actions[1] is ConnectionAttempt)
        }

        ClientClosed.process(AskForStatus).let { (newState, actions) ->
            assertEquals(ClientClosed, newState)
            assertTrue(actions.isEmpty())
        }

        assertFails { ClientClosed.process(Connected) }
    }

    @Test
    fun `unhandled events`() {
        val states = listOf(
            ClientRunning(0, testBlockHeader), WaitingForVersion, WaitingForTip, ClientClosed
        )
        states.forEach { state ->
            state.process(Disconnected).let { (newState, actions) ->
                assertEquals(ClientClosed, newState)
                assertEquals(2, actions.size)
                assertTrue { actions[0] is BroadcastStatus }
                assertTrue { actions[1] is Shutdown }
            }
        }
    }
}