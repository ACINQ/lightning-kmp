package fr.acinq.eclair.utils

import kotlin.native.concurrent.ensureNeverFrozen as nativeEnsureNeverFrozen


actual fun <T : Any> T.ensureNeverFrozen() = nativeEnsureNeverFrozen()
