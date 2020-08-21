package fr.acinq.eklair.channel

import fr.acinq.eklair.blockchain.fee.TestFeeEstimator
import fr.acinq.eklair.crypto.KeyManager
import fr.acinq.eklair.wire.Tlv
import fr.acinq.eklair.wire.UpdateMessage
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelStateSerializationTestsCommon {
    val serializationModules = SerializersModule {
        include(Tlv.serializationModule)
        include(KeyManager.serializationModule)
        include(UpdateMessage.serializationModule)
        include(TestFeeEstimator.testSerializationModule)
    }

    val cbor = Cbor {
        serializersModule = serializationModules
    }
    @Test
    fun `serialize normal state`() {
        val (alice, bob) = TestsHelper.reachNormal()
        val bytes = cbor.encodeToByteArray(ChannelState.serializer(), alice)
        val check = cbor.decodeFromByteArray<ChannelState>(bytes)
        assertEquals(alice,  check)

        val bytes1 = cbor.encodeToByteArray(ChannelState.serializer(), bob)
        val check1 = cbor.decodeFromByteArray<ChannelState>(bytes1)
        assertEquals(bob,  check1)
    }
}