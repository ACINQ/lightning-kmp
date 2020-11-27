package fr.acinq.eclair

import fr.acinq.eclair.Feature.*
import fr.acinq.eclair.Features.Companion.validateFeatureGraph
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.BitField
import kotlin.test.*

class FeaturesTestsCommon : EclairTestSuite() {

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
            setOf(
                ActivatedFeature(InitialRoutingSync, FeatureSupport.Optional),
                ActivatedFeature(OptionDataLossProtect, FeatureSupport.Optional),
                ActivatedFeature(VariableLengthOnion, FeatureSupport.Mandatory)
            )
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
                Features(setOf(ActivatedFeature(InitialRoutingSync, FeatureSupport.Optional), ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional))),
                oursSupportTheirs = true,
                theirsSupportOurs = true,
                compatible = true
            ),
            TestCase(
                Features.empty,
                Features(setOf(), setOf(UnknownFeature(101), UnknownFeature(103))),
                oursSupportTheirs = true,
                theirsSupportOurs = true,
                compatible = true
            ),
            // Same feature set
            TestCase(
                Features(setOf(ActivatedFeature(InitialRoutingSync, FeatureSupport.Optional), ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Mandatory))),
                Features(setOf(ActivatedFeature(InitialRoutingSync, FeatureSupport.Optional), ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Mandatory))),
                oursSupportTheirs = true,
                theirsSupportOurs = true,
                compatible = true
            ),
            // Many optional features
            TestCase(
                Features(
                    setOf(
                        ActivatedFeature(InitialRoutingSync, FeatureSupport.Optional),
                        ActivatedFeature(VariableLengthOnion, FeatureSupport.Optional),
                        ActivatedFeature(ChannelRangeQueries, FeatureSupport.Optional),
                        ActivatedFeature(PaymentSecret, FeatureSupport.Optional)
                    )
                ),
                Features(
                    setOf(
                        ActivatedFeature(VariableLengthOnion, FeatureSupport.Optional),
                        ActivatedFeature(ChannelRangeQueries, FeatureSupport.Optional),
                        ActivatedFeature(ChannelRangeQueriesExtended, FeatureSupport.Optional)
                    )
                ),
                oursSupportTheirs = true,
                theirsSupportOurs = true,
                compatible = true
            ),
            // We support their mandatory features
            TestCase(
                Features(setOf(ActivatedFeature(VariableLengthOnion, FeatureSupport.Optional))),
                Features(setOf(ActivatedFeature(InitialRoutingSync, FeatureSupport.Optional), ActivatedFeature(VariableLengthOnion, FeatureSupport.Mandatory))),
                oursSupportTheirs = true,
                theirsSupportOurs = true,
                compatible = true
            ),
            // They support our mandatory features
            TestCase(
                Features(setOf(ActivatedFeature(VariableLengthOnion, FeatureSupport.Mandatory))),
                Features(setOf(ActivatedFeature(InitialRoutingSync, FeatureSupport.Optional), ActivatedFeature(VariableLengthOnion, FeatureSupport.Optional))),
                oursSupportTheirs = true,
                theirsSupportOurs = true,
                compatible = true
            ),
            // They have unknown optional features
            TestCase(
                Features(setOf(ActivatedFeature(VariableLengthOnion, FeatureSupport.Optional))),
                Features(setOf(ActivatedFeature(VariableLengthOnion, FeatureSupport.Optional)), setOf(UnknownFeature(141))),
                oursSupportTheirs = true,
                theirsSupportOurs = true,
                compatible = true
            ),
            // They have unknown mandatory features
            TestCase(
                Features(setOf(ActivatedFeature(VariableLengthOnion, FeatureSupport.Optional))),
                Features(setOf(ActivatedFeature(VariableLengthOnion, FeatureSupport.Optional)), setOf(UnknownFeature(142))),
                oursSupportTheirs = false,
                theirsSupportOurs = true,
                compatible = false
            ),
            // We don't support one of their mandatory features
            TestCase(
                Features(setOf(ActivatedFeature(ChannelRangeQueries, FeatureSupport.Optional))),
                Features(setOf(ActivatedFeature(ChannelRangeQueries, FeatureSupport.Mandatory), ActivatedFeature(VariableLengthOnion, FeatureSupport.Mandatory))),
                oursSupportTheirs = false,
                theirsSupportOurs = true,
                compatible = false
            ),
            // They don't support one of our mandatory features
            TestCase(
                Features(setOf(ActivatedFeature(VariableLengthOnion, FeatureSupport.Mandatory), ActivatedFeature(PaymentSecret, FeatureSupport.Mandatory))),
                Features(setOf(ActivatedFeature(VariableLengthOnion, FeatureSupport.Optional))),
                oursSupportTheirs = true,
                theirsSupportOurs = false,
                compatible = false
            ),
            // nonreg testing of future features (needs to be updated with every new supported mandatory bit)
            TestCase(Features.empty, Features(setOf(), setOf(UnknownFeature(22))), oursSupportTheirs = false, theirsSupportOurs = true, compatible = false),
            TestCase(Features.empty, Features(setOf(), setOf(UnknownFeature(23))), oursSupportTheirs = true, theirsSupportOurs = true, compatible = true),
            TestCase(Features.empty, Features(setOf(), setOf(UnknownFeature(24))), oursSupportTheirs = false, theirsSupportOurs = true, compatible = false),
            TestCase(Features.empty, Features(setOf(), setOf(UnknownFeature(25))), oursSupportTheirs = true, theirsSupportOurs = true, compatible = true)
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
            byteArrayOf(0x01, 0x00) to Features(setOf(ActivatedFeature(VariableLengthOnion, FeatureSupport.Mandatory))),
            byteArrayOf(0x02, 0x8a.toByte(), 0x8a.toByte()) to Features(
                setOf(
                    ActivatedFeature(OptionDataLossProtect, FeatureSupport.Optional),
                    ActivatedFeature(InitialRoutingSync, FeatureSupport.Optional),
                    ActivatedFeature(ChannelRangeQueries, FeatureSupport.Optional),
                    ActivatedFeature(VariableLengthOnion, FeatureSupport.Optional),
                    ActivatedFeature(ChannelRangeQueriesExtended, FeatureSupport.Optional),
                    ActivatedFeature(PaymentSecret, FeatureSupport.Optional),
                    ActivatedFeature(BasicMultiPartPayment, FeatureSupport.Optional)
                )
            ),
            byteArrayOf(0x09, 0x00, 0x42, 0x00) to Features(
                setOf(
                    ActivatedFeature(VariableLengthOnion, FeatureSupport.Optional),
                    ActivatedFeature(PaymentSecret, FeatureSupport.Mandatory)
                ),
                setOf(UnknownFeature(24), UnknownFeature(27))
            ),
            byteArrayOf(0x52, 0x00, 0x00, 0x00) to Features(
                emptySet(),
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

//    @Test fun `parse features from configuration`() {
//        {
//            val conf = ConfigFactory.parseString(
//                """
//          |features {
//          |  option_data_loss_protect = optional
//          |  initial_routing_sync = optional
//          |  gossip_queries = optional
//          |  gossip_queries_ex = optional
//          |  var_onion_optin = optional
//          |  payment_secret = optional
//          |  basic_mpp = optional
//          |}
//      """.stripMargin)
//
//            val features = fromConfiguration(conf)
//            assertTrue(features.toByteVector === hex"028a8a")
//            assertTrue(Features(hex"028a8a") === features)
//            assertTrue(areSupported(features))
//            assertTrue(validateFeatureGraph(features) === None)
//            assertTrue(features.hasFeature(OptionDataLossProtect, Some(Optional)))
//            assertTrue(features.hasFeature(InitialRoutingSync, Some(Optional)))
//            assertTrue(features.hasFeature(ChannelRangeQueries, Some(Optional)))
//            assertTrue(features.hasFeature(ChannelRangeQueriesExtended, Some(Optional)))
//            assertTrue(features.hasFeature(VariableLengthOnion, Some(Optional)))
//            assertTrue(features.hasFeature(PaymentSecret, Some(Optional)))
//            assertTrue(features.hasFeature(BasicMultiPartPayment, Some(Optional)))
//        }
//
//        {
//            val conf = ConfigFactory.parseString(
//                """
//          |  features {
//          |    initial_routing_sync = optional
//          |    option_data_loss_protect = optional
//          |    gossip_queries = optional
//          |    gossip_queries_ex = mandatory
//          |    var_onion_optin = optional
//          |  }
//          |
//      """.stripMargin
//            )
//
//            val features = fromConfiguration(conf)
//            assertTrue(features.toByteVector === hex"068a")
//            assertTrue(Features(hex"068a") === features)
//            assertTrue(areSupported(features))
//            assertTrue(validateFeatureGraph(features) === None)
//            assertTrue(features.hasFeature(OptionDataLossProtect, Some(Optional)))
//            assertTrue(features.hasFeature(InitialRoutingSync, Some(Optional)))
//            assertTrue(!features.hasFeature(InitialRoutingSync, Some(Mandatory)))
//            assertTrue(features.hasFeature(ChannelRangeQueries, Some(Optional)))
//            assertTrue(features.hasFeature(ChannelRangeQueriesExtended, Some(Mandatory)))
//            assertTrue(features.hasFeature(VariableLengthOnion, Some(Optional)))
//            assertTrue(!features.hasFeature(PaymentSecret))
//        }
//
//        {
//            val confWithUnknownFeatures = ConfigFactory.parseString(
//                """
//          |features {
//          |  option_non_existent = mandatory # this is ignored
//          |  gossip_queries = optional
//          |  payment_secret = mandatory
//          |}
//      """.stripMargin)
//
//            val features = fromConfiguration(confWithUnknownFeatures)
//            assertTrue(features.toByteVector === hex"4080")
//            assertTrue(Features(hex"4080") === features)
//            assertTrue(areSupported(features))
//            assertTrue(features.hasFeature(ChannelRangeQueries, Some(Optional)))
//            assertTrue(features.hasFeature(PaymentSecret, Some(Mandatory)))
//        }
//
//        {
//            val confWithUnknownSupport = ConfigFactory.parseString(
//                """
//          |features {
//          |  option_data_loss_protect = what
//          |  gossip_queries = optional
//          |  payment_secret = mandatory
//          |}
//      """.stripMargin)
//
//            assertThrows[RuntimeException](fromConfiguration(confWithUnknownSupport))
//        }
//    }
//
//    @Test fun `'knownFeatures' contains all our known features (reflection test)`() {
//        import scala.reflect.runtime.universe._
//                import scala.reflect.runtime.{ universe => runtime }
//        val mirror = runtime.runtimeMirror(ClassLoader.getSystemClassLoader)
//        val subclasses = typeOf[Feature].typeSymbol.asClass.knownDirectSubclasses
//        val knownFeatures = subclasses.map({ desc =>
//            val mod = mirror.staticModule(desc.asClass.fullName)
//            mirror.reflectModule(mod).instance.asInstanceOf[Feature]
//        })
//
//        assertTrue((knownFeatures -- Features.knownFeatures).isEmpty)
//    }

}
