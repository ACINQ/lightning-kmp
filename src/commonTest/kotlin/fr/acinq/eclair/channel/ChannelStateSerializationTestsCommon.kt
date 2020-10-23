package fr.acinq.eclair.channel

import fr.acinq.eclair.io.eclairSerializersModule
import fr.acinq.eclair.tests.utils.EclairTestSuite
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalSerializationApi::class)
class ChannelStateSerializationTestsCommon : EclairTestSuite() {
    val serializationModules = SerializersModule {
        include(eclairSerializersModule)
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
        assertEquals(alice, check)

        val bytes1 = cbor.encodeToByteArray(ChannelStateHolder(bob))
        val check1 = cbor.decodeFromByteArray<ChannelStateHolder>(bytes1).state
        assertEquals(bob, check1)
    }
}