package fr.acinq.lightning.utils

import kotlin.native.concurrent.ensureNeverFrozen as nativeEnsureNeverFrozen


actual fun <T : Any> T.ensureNeverFrozen() = nativeEnsureNeverFrozen()
