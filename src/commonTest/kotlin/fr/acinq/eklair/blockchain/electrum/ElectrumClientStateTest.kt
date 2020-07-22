package fr.acinq.eklair.blockchain.electrum

import fr.acinq.bitcoin.BlockHeader
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ElectrumClientStateTest {

    @Test
    fun `ClientClosed state`() {
        ClientClosed.process(Connected).let {
            assertEquals(WaitingForVersion, it.first)
            assertTrue {it.second.contains(SendRequest(ElectrumClient.version.asJsonRPCRequest())) }
        }

        ClientClosed.process(RegisterStatusListener(Channel())).let {
            assertEquals(ClientClosed, it.first)
            assertEquals (1, it.second.size)
            assertTrue { it.second.first() is AddStatusListener }
        }

        ClientClosed.process(SendElectrumRequest(Ping)).let {
            assertEquals(ClientClosed, it.first)
            assertTrue { it.second.isEmpty() }
        }
    }

    @Test fun `unhandled events`() {
        val states = listOf(ClientRunning(0, BlockHeader.read("000000203e343602423924ffc4bccdc08ef468c3ba80187c2200dcb6b60e82d71cdbae236f4a9fd886c3d1ae9659a571f8d0d697c78cb0d6e23112859ba5fc26e46d6744a4f4175fffff7f2000000000")), WaitingForVersion, WaitingForTip, ClientClosed)
        states.forEach { state ->
            state.process(UnregisterListener(Channel())).let {
                assertEquals(state, it.first)
                assertEquals(3, it.second.size)
                assertTrue { it.second[0] is RemoveStatusListener }
                assertTrue { it.second[1] is RemoveHeaderListener }
                assertTrue { it.second[2] is RemoveScriptHashListener }
            }

            state.process(Disconnected).let {
                assertEquals(ClientClosed, it.first)
                assertTrue { it.second.isEmpty() }
            }
        }
    }
}