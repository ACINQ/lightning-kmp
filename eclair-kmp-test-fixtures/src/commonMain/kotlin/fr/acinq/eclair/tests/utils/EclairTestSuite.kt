package fr.acinq.eclair.tests.utils

import fr.acinq.eclair.utils.setEclairLoggerFactory
import kotlin.native.concurrent.ThreadLocal


abstract class EclairTestSuite {

    init {
        setLogger()
    }

    @ThreadLocal
    companion object {
        private var loggerIsSet = false
        fun setLogger() {
            if (loggerIsSet) return
            setEclairLoggerFactory(testEclairLoggerFactory)
            loggerIsSet = true
        }
    }

}
