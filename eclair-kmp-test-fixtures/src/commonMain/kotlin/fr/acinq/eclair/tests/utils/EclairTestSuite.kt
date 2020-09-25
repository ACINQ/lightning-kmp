package fr.acinq.eclair.tests.utils

import fr.acinq.eclair.utils.EclairLoggerFactory
import kotlin.test.BeforeTest

abstract class EclairTestSuite {

    @BeforeTest
    fun setTestEclairLogger() {
        EclairLoggerFactory = testEclairLoggerFactory
    }

}
