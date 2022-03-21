package fr.acinq.lightning.tests.utils

import fr.acinq.lightning.utils.setLightningLoggerFactory
import kotlin.native.concurrent.ThreadLocal


abstract class LightningTestSuite {

    init {
        setLogger()
    }

    @ThreadLocal
    companion object {
        private var loggerIsSet = false
        fun setLogger() {
            if (loggerIsSet) return
            setLightningLoggerFactory(testLightningLoggerFactory)
            loggerIsSet = true
        }
    }

}
