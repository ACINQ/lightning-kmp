package fr.acinq.eclair

import fr.acinq.eclair.Features.Companion.areSupported
import fr.acinq.eclair.Features.Companion.validateFeatureGraph
import fr.acinq.eclair.utils.BitField
import kotlin.test.*

class FeaturesTests {

    @Test
    fun `'initial_routing_sync' feature`() {
        assertTrue(Features(byteArrayOf(0x08)).hasFeature(Feature.InitialRoutingSync, FeatureSupport.Optional))
        assertTrue(!Features(byteArrayOf(0x08)).hasFeature(Feature.InitialRoutingSync, FeatureSupport.Mandatory))
    }

    @Test
    fun `'data_loss_protect' feature`() {
        assertTrue(Features(byteArrayOf(0x01)).hasFeature(Feature.OptionDataLossProtect, FeatureSupport.Mandatory))
        assertTrue(Features(byteArrayOf(0x02)).hasFeature(Feature.OptionDataLossProtect, FeatureSupport.Optional))
    }

    @Test
    fun `'initial_routing_sync', 'data_loss_protect' and 'variable_length_onion' features`() {
        val features = Features(
            setOf(
                ActivatedFeature(Feature.InitialRoutingSync, FeatureSupport.Optional),
                ActivatedFeature(Feature.OptionDataLossProtect, FeatureSupport.Optional),
                ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Mandatory)
            )
        )
        assertTrue(features.toByteArray().contentEquals(byteArrayOf(0x01, 0x0a)))
        assertTrue(areSupported(features))
        assertTrue(features.hasFeature(Feature.OptionDataLossProtect))
        assertTrue(features.hasFeature(Feature.InitialRoutingSync))
        assertTrue(features.hasFeature(Feature.VariableLengthOnion))
    }

    @Test
    fun `'variable_length_onion' feature`() {
        assertTrue(Features(byteArrayOf(0x01, 0x00)).hasFeature(Feature.VariableLengthOnion))
        assertTrue(Features(byteArrayOf(0x01, 0x00)).hasFeature(Feature.VariableLengthOnion, FeatureSupport.Mandatory))
        assertTrue(Features(byteArrayOf(0x02, 0x00)).hasFeature(Feature.VariableLengthOnion))
        assertTrue(Features(byteArrayOf(0x02, 0x00)).hasFeature(Feature.VariableLengthOnion, FeatureSupport.Optional))
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
        assertTrue(areSupported(Features(setOf(ActivatedFeature(Feature.InitialRoutingSync, FeatureSupport.Optional)))))
        assertTrue(areSupported(Features(setOf(ActivatedFeature(Feature.OptionDataLossProtect, FeatureSupport.Mandatory)))))
        assertTrue(areSupported(Features(setOf(ActivatedFeature(Feature.OptionDataLossProtect, FeatureSupport.Optional)))))
        assertTrue(areSupported(Features(setOf(ActivatedFeature(Feature.ChannelRangeQueries, FeatureSupport.Mandatory)))))
        assertTrue(areSupported(Features(setOf(ActivatedFeature(Feature.ChannelRangeQueries, FeatureSupport.Optional)))))
        assertTrue(areSupported(Features(setOf(ActivatedFeature(Feature.ChannelRangeQueriesExtended, FeatureSupport.Mandatory)))))
        assertTrue(areSupported(Features(setOf(ActivatedFeature(Feature.ChannelRangeQueriesExtended, FeatureSupport.Optional)))))
        assertTrue(areSupported(Features(setOf(ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Mandatory)))))
        assertTrue(areSupported(Features(setOf(ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional)))))
        assertTrue(areSupported(Features(setOf(ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Mandatory)))))
        assertTrue(areSupported(Features(setOf(ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Optional)))))
        assertTrue(areSupported(Features(setOf(ActivatedFeature(Feature.BasicMultiPartPayment, FeatureSupport.Mandatory)))))
        assertTrue(areSupported(Features(setOf(ActivatedFeature(Feature.BasicMultiPartPayment, FeatureSupport.Optional)))))
        assertTrue(areSupported(Features(setOf(ActivatedFeature(Feature.Wumbo, FeatureSupport.Mandatory)))))
        assertTrue(areSupported(Features(setOf(ActivatedFeature(Feature.Wumbo, FeatureSupport.Optional)))))

        val testCases = mapOf(
            BitField.fromBin("            00000000000000001011") to true,
            BitField.fromBin("            00010000100001000000") to true,
            BitField.fromBin("            00100000100000100000") to true,
            BitField.fromBin("            00010100000000001000") to true,
            BitField.fromBin("            00011000001000000000") to true,
            BitField.fromBin("            00101000000000000000") to true,
            BitField.fromBin("            00000000010001000000") to true,
            BitField.fromBin("            01000000000000000000") to true,
            BitField.fromBin("            10000000000000000000") to true,
            // unknown optional feature bits
            BitField.fromBin("        001000000000000000000000") to true,
            BitField.fromBin("        100000000000000000000000") to true,
            // those are useful for nonreg testing of the areSupported method (which needs to be updated with every new supported mandatory bit)
            BitField.fromBin("        000100000000000000000000") to false,
            BitField.fromBin("        010000000000000000000000") to false,
            BitField.fromBin("    0001000000000000000000000000") to false,
            BitField.fromBin("    0100000000000000000000000000") to false,
            BitField.fromBin("00010000000000000000000000000000") to false,
            BitField.fromBin("01000000000000000000000000000000") to false
        )
        for ((testCase, expected) in testCases) {
            assertEquals(expected, areSupported(Features(testCase)), testCase.toBinaryString())
        }
    }

    @Test
    fun `features to bytes`() {
        val testCases = mapOf(
            byteArrayOf() to Features.empty,
            byteArrayOf(0x01, 0x00) to Features(setOf(ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Mandatory))),
            byteArrayOf(0x02, 0x8a.toByte(), 0x8a.toByte()) to Features(
                setOf(
                    ActivatedFeature(Feature.OptionDataLossProtect, FeatureSupport.Optional),
                    ActivatedFeature(Feature.InitialRoutingSync, FeatureSupport.Optional),
                    ActivatedFeature(Feature.ChannelRangeQueries, FeatureSupport.Optional),
                    ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional),
                    ActivatedFeature(Feature.ChannelRangeQueriesExtended, FeatureSupport.Optional),
                    ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Optional),
                    ActivatedFeature(Feature.BasicMultiPartPayment, FeatureSupport.Optional)
                )
            ),
            byteArrayOf(0x09, 0x00, 0x42, 0x00) to Features(
                setOf(
                    ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional),
                    ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Mandatory)
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
