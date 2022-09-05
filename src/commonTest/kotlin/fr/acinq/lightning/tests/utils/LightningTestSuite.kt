package fr.acinq.lightning.tests.utils

import fr.acinq.lightning.utils.setLightningLoggerFactory


abstract class LightningTestSuite {

    companion object {
        init {
            setLightningLoggerFactory(testLightningLoggerFactory)
        }
    }

}
