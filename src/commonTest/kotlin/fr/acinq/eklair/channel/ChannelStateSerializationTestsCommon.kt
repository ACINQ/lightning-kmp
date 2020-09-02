package fr.acinq.eklair.channel

import fr.acinq.eklair.blockchain.fee.ConstantFeeEstimator
import fr.acinq.eklair.crypto.KeyManager
import fr.acinq.eklair.wire.Tlv
import fr.acinq.eklair.wire.UpdateMessage
import kotlinx.serialization.*
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalSerializationApi::class)
class ChannelStateSerializationTestsCommon {
    val serializationModules = SerializersModule {
        include(Tlv.serializationModule)
        include(KeyManager.serializationModule)
        include(UpdateMessage.serializationModule)
        include(ConstantFeeEstimator.testSerializationModule)
    }

    val cbor = Cbor {
        serializersModule = serializationModules
    }

    @Serializable
    data class ChannelStateHolder(val state: ChannelState)

    @Test
    fun `serialize normal state`() {
        val (alice, bob) = TestsHelper.reachNormal()
        val bytes = cbor.encodeToByteArray(ChannelStateHolder(alice))
        val check = cbor.decodeFromByteArray<ChannelStateHolder>(bytes).state
        assertEquals(alice,  check)

        val bytes1 = cbor.encodeToByteArray(ChannelStateHolder(bob))
        val check1 = cbor.decodeFromByteArray<ChannelStateHolder>(bytes1).state
        assertEquals(bob,  check1)
    }
}