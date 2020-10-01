package fr.acinq.eclair.tests.utils

import fr.acinq.eclair.utils.EclairLoggerFactory

abstract class EclairTestSuite {

    init {
        EclairLoggerFactory = testEclairLoggerFactory
    }

}
