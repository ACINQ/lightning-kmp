package fr.acinq.lightning

import fr.acinq.lightning.Feature.*
import fr.acinq.lightning.Features.Companion.validateFeatureGraph
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.BitField
import kotlin.test.*

class FeaturesTestsCommon : LightningTestSuite() {

    @Test
    fun `'initial_routing_sync' feature`() {
        assertTrue(Features(byteArrayOf(0x08)).hasFeature(InitialRoutingSync, FeatureSupport.Optional))
        assertTrue(!Features(byteArrayOf(0x08)).hasFeature(InitialRoutingSync, FeatureSupport.Mandatory))
    }

    @Test
    fun `'data_loss_protect' feature`() {
        assertTrue(Features(byteArrayOf(0x01)).hasFeature(OptionDataLossProtect, FeatureSupport.Mandatory))
        assertTrue(Features(byteArrayOf(0x02)).hasFeature(OptionDataLossProtect, FeatureSupport.Optional))
    }

    @Test
    fun `'initial_routing_sync', 'data_loss_protect' and 'variable_length_onion' features`() {
        val features = Features(
            InitialRoutingSync to FeatureSupport.Optional,
            OptionDataLossProtect to FeatureSupport.Optional,
            VariableLengthOnion to FeatureSupport.Mandatory
        )
        assertTrue(features.toByteArray().contentEquals(byteArrayOf(0x01, 0x0a)))
        assertTrue(features.hasFeature(OptionDataLossProtect))
        assertTrue(features.hasFeature(InitialRoutingSync))
        assertTrue(features.hasFeature(VariableLengthOnion))
    }

    @Test
    fun `'variable_length_onion' feature`() {
        assertTrue(Features(byteArrayOf(0x01, 0x00)).hasFeature(VariableLengthOnion))
        assertTrue(Features(byteArrayOf(0x01, 0x00)).hasFeature(VariableLengthOnion, FeatureSupport.Mandatory))
        assertTrue(Features(byteArrayOf(0x02, 0x00)).hasFeature(VariableLengthOnion))
        assertTrue(Features(byteArrayOf(0x02, 0x00)).hasFeature(VariableLengthOnion, FeatureSupport.Optional))
    }

    @Test
    fun `features dependencies`() {
        val testCases = mapOf(
            BitField.fromBin("                        ") to true,
            BitField.fromBin("                00000000") to true,
            BitField.fromBin("                01011000") to true,
            // gossip_queries_ex depend on gossip_queries
            BitField.fromBin("000000000000100000000000") to false,
            BitField.fromBin("000000000000010000000000") to false,
            BitField.fromBin("000000000000100010000000") to true,
            BitField.fromBin("000000000000100001000000") to true,
            // payment_secret depends on var_onion_optin
            BitField.fromBin("000000001000000000000000") to false,
            BitField.fromBin("000000000100000000000000") to false,
            BitField.fromBin("000000000100001000000000") to true,
            // basic_mpp depends on payment_secret
            BitField.fromBin("000000100000000000000000") to false,
            BitField.fromBin("000000010000000000000000") to false,
            BitField.fromBin("000000101000000000000000") to false,
            BitField.fromBin("000000011000000000000000") to false,
            BitField.fromBin("000000011000001000000000") to true,
            BitField.fromBin("000000100100000100000000") to true
        )

        for ((testCase, valid) in testCases) {
            if (valid) {
                assertNull(validateFeatureGraph(Features(testCase)))
                assertNull(validateFeatureGraph(Features(testCase.bytes)))
            } else {
                assertNotNull(validateFeatureGraph(Features(testCase)))
                assertNotNull(validateFeatureGraph(Features(testCase.bytes)))
            }
        }
    }

    @Test
    fun `features compatibility`() {

        data class TestCase(val ours: Features, val theirs: Features, val oursSupportTheirs: Boolean, val theirsSupportOurs: Boolean, val compatible: Boolean)

        val testCases = listOf(
            // Empty features
            TestCase(
                Features.empty,
                Features.empty,
                oursSupportTheirs = true,
                theirsSupportOurs = true,
                compatible = true
            ),
            TestCase(
                Features.empty,
                Features(InitialRoutingSync to FeatureSupport.Optional, VariableLengthOnion to FeatureSupport.Optional),
                oursSupportTheirs = true,
                theirsSupportOurs = true,
                compatible = true
            ),
            TestCase(
                Features.empty,
                Features(mapOf(), setOf(UnknownFeature(101), UnknownFeature(103))),
                oursSupportTheirs = true,
                theirsSupportOurs = true,
                compatible = true
            ),
            // Same feature set
            TestCase(
                Features(InitialRoutingSync to FeatureSupport.Optional, VariableLengthOnion to FeatureSupport.Mandatory),
                Features(InitialRoutingSync to FeatureSupport.Optional, VariableLengthOnion to FeatureSupport.Mandatory),
                oursSupportTheirs = true,
                theirsSupportOurs = true,
                compatible = true
            ),
            // Many optional features
            TestCase(
                Features(
                    InitialRoutingSync to FeatureSupport.Optional,
                    VariableLengthOnion to FeatureSupport.Optional,
                    ChannelRangeQueries to FeatureSupport.Optional,
                    PaymentSecret to FeatureSupport.Optional
                ),
                Features(
                    VariableLengthOnion to FeatureSupport.Optional,
                    ChannelRangeQueries to FeatureSupport.Optional,
                    ChannelRangeQueriesExtended to FeatureSupport.Optional
                ),
                oursSupportTheirs = true,
                theirsSupportOurs = true,
                compatible = true
            ),
            // We support their mandatory features
            TestCase(
                Features(VariableLengthOnion to FeatureSupport.Optional),
                Features(InitialRoutingSync to FeatureSupport.Optional, VariableLengthOnion to FeatureSupport.Mandatory),
                oursSupportTheirs = true,
                theirsSupportOurs = true,
                compatible = true
            ),
            // They support our mandatory features
            TestCase(
                Features(VariableLengthOnion to FeatureSupport.Mandatory),
                Features(InitialRoutingSync to FeatureSupport.Optional, VariableLengthOnion to FeatureSupport.Optional),
                oursSupportTheirs = true,
                theirsSupportOurs = true,
                compatible = true
            ),
            // They have unknown optional features
            TestCase(
                Features(VariableLengthOnion to FeatureSupport.Optional),
                Features(mapOf(VariableLengthOnion to FeatureSupport.Optional), setOf(UnknownFeature(141))),
                oursSupportTheirs = true,
                theirsSupportOurs = true,
                compatible = true
            ),
            // They have unknown mandatory features
            TestCase(
                Features(VariableLengthOnion to FeatureSupport.Optional),
                Features(mapOf(VariableLengthOnion to FeatureSupport.Optional), setOf(UnknownFeature(142))),
                oursSupportTheirs = false,
                theirsSupportOurs = true,
                compatible = false
            ),
            // We don't support one of their mandatory features
            TestCase(
                Features(ChannelRangeQueries to FeatureSupport.Optional),
                Features(ChannelRangeQueries to FeatureSupport.Mandatory, VariableLengthOnion to FeatureSupport.Mandatory),
                oursSupportTheirs = false,
                theirsSupportOurs = true,
                compatible = false
            ),
            // They don't support one of our mandatory features
            TestCase(
                Features(VariableLengthOnion to FeatureSupport.Mandatory, PaymentSecret to FeatureSupport.Mandatory),
                Features(VariableLengthOnion to FeatureSupport.Optional),
                oursSupportTheirs = true,
                theirsSupportOurs = false,
                compatible = false
            ),
            // nonreg testing of future features (needs to be updated with every new supported mandatory bit)
            TestCase(Features.empty, Features(mapOf(), setOf(UnknownFeature(22))), oursSupportTheirs = false, theirsSupportOurs = true, compatible = false),
            TestCase(Features.empty, Features(mapOf(), setOf(UnknownFeature(23))), oursSupportTheirs = true, theirsSupportOurs = true, compatible = true),
            TestCase(Features.empty, Features(mapOf(), setOf(UnknownFeature(24))), oursSupportTheirs = false, theirsSupportOurs = true, compatible = false),
            TestCase(Features.empty, Features(mapOf(), setOf(UnknownFeature(25))), oursSupportTheirs = true, theirsSupportOurs = true, compatible = true)
        )

        testCases.forEach { testCase ->
            assertEquals(Features.areCompatible(testCase.ours, testCase.theirs), testCase.compatible, testCase.toString())
            assertEquals(testCase.ours.areSupported(testCase.theirs), testCase.oursSupportTheirs, testCase.toString())
            assertEquals(testCase.theirs.areSupported(testCase.ours), testCase.theirsSupportOurs, testCase.toString())
        }
    }

    @Test
    fun `features to bytes`() {
        val testCases = mapOf(
            byteArrayOf() to Features.empty,
            byteArrayOf(0x01, 0x00) to Features(VariableLengthOnion to FeatureSupport.Mandatory),
            byteArrayOf(0x02, 0x8a.toByte(), 0x8a.toByte()) to Features(
                OptionDataLossProtect to FeatureSupport.Optional,
                InitialRoutingSync to FeatureSupport.Optional,
                ChannelRangeQueries to FeatureSupport.Optional,
                VariableLengthOnion to FeatureSupport.Optional,
                ChannelRangeQueriesExtended to FeatureSupport.Optional,
                PaymentSecret to FeatureSupport.Optional,
                BasicMultiPartPayment to FeatureSupport.Optional
            ),
            byteArrayOf(0x09, 0x00, 0x42, 0x00) to Features(
                mapOf(
                    VariableLengthOnion to FeatureSupport.Optional,
                    PaymentSecret to FeatureSupport.Mandatory,
                    ShutdownAnySegwit to FeatureSupport.Optional
                ),
                setOf(UnknownFeature(24))
            ),
            byteArrayOf(0x52, 0x00, 0x00, 0x00) to Features(
                mapOf(),
                setOf(UnknownFeature(25), UnknownFeature(28), UnknownFeature(30))
            )
        )

        for ((bin, features) in testCases) {
            assertTrue(bin.contentEquals(features.toByteArray()))
            assertEquals(features, Features(bin))
            val notMinimallyEncoded = Features(byteArrayOf(0) + bin)
            assertEquals(features, notMinimallyEncoded)
            assertTrue(bin.contentEquals(notMinimallyEncoded.toByteArray())) // features are minimally-encoded when converting to bytes
        }
    }

}
