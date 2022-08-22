package fr.acinq.lightning.tests.utils

import fr.acinq.lightning.utils.setLightningLoggerFactory


abstract class LightningTestSuite {

    init {
        setLogger()
    }

    companion object {
        private var loggerIsSet = false
        fun setLogger() {
            if (loggerIsSet) return
            setLightningLoggerFactory(testLightningLoggerFactory)
            loggerIsSet = true
        }
    }

}
