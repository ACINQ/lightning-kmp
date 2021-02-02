package fr.acinq.eclair.serialization

import fr.acinq.eclair.Eclair.randomKey
import fr.acinq.eclair.channel.TestsHelper
import fr.acinq.eclair.tests.utils.EclairTestSuite
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalSerializationApi::class)
class StateSerializationTestsCommon : EclairTestSuite() {

    @Test
    fun `serialize normal state`() {
        val (alice, bob) = TestsHelper.reachNormal()
        val bytes = Serialization.serialize(alice)
        val check = Serialization.deserialize(bytes, alice.staticParams.nodeParams)
        assertEquals(alice.copy(doCheckForTimedOutHtlcs = false), check)

        val bytes1 = Serialization.serialize(bob)
        val check1 = Serialization.deserialize(bytes1, bob.staticParams.nodeParams)
        assertEquals(bob.copy(doCheckForTimedOutHtlcs = false), check1)
    }

    @Test
    fun `encrypt - decrypt normal state`() {
        val (alice, bob) = TestsHelper.reachNormal()
        val priv = randomKey()
        val bytes = Serialization.encrypt(priv, alice)
        val check = Serialization.decrypt(priv, bytes, alice.staticParams.nodeParams)
        assertEquals(alice.copy(doCheckForTimedOutHtlcs = false), check)

        val bytes1 = Serialization.encrypt(priv, bob)
        val check1 = Serialization.decrypt(priv, bytes1, bob.staticParams.nodeParams)
        assertEquals(bob.copy(doCheckForTimedOutHtlcs = false), check1)
    }
}
